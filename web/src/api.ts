/** Operator REST calls the status page depends on. */

import type {
  AlertSummary,
  AlertsResponse,
  ApprovalStatus,
  ApprovalSummary,
  ApprovalsResponse,
  CommandsResponse,
  ControlCommandSummary,
  ControlStatus,
  QuestionSummary,
  QuestionsResponse,
  SearchResponse,
  SearchResult,
  SessionDetail,
  SessionSummary,
  SessionsResponse,
  StatusResponse,
  TranscriptMessage,
  TranscriptResponse,
} from './types';

/** TanStack Query key for the seeded `/status` snapshot. */
export const STATUS_QUERY_KEY = ['status'] as const;
/** TanStack Query key for the seeded `/sessions` list. */
export const SESSIONS_QUERY_KEY = ['sessions'] as const;
/** TanStack Query key for the seeded `/alerts` inbox. */
export const ALERTS_QUERY_KEY = ['alerts'] as const;
/** TanStack Query key for the seeded `/approvals` inbox. */
export const APPROVALS_QUERY_KEY = ['approvals'] as const;
/** TanStack Query key for the seeded `/commands` control activity strip. */
export const COMMANDS_QUERY_KEY = ['commands'] as const;
/** TanStack Query key for the seeded `/questions` inbox. */
export const QUESTIONS_QUERY_KEY = ['questions'] as const;
/** TanStack Query key factory for a single session's detail. */
export const sessionDetailQueryKey = (id: string) =>
  ['session', id] as const;
/** TanStack Query key factory for a single session's managed transcript. */
export const transcriptQueryKey = (id: string) =>
  ['transcript', id] as const;
/** TanStack Query key factory for a cross-session search term. */
export const searchQueryKey = (term: string) =>
  ['search', term] as const;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * Seeds the initial status. A `401` here means the operator is not authenticated
 * (the surface is session-protected — rest-api.md), which the UI turns into a login prompt.
 */
export async function fetchStatus(): Promise<StatusResponse> {
  const res = await fetch('/status', {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /status failed: ${res.status}`, res.status);
  }
  return (await res.json()) as StatusResponse;
}

/** Lists monitored sessions across the fleet (`GET /sessions`). */
export async function fetchSessions(): Promise<SessionSummary[]> {
  const res = await fetch('/sessions', {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /sessions failed: ${res.status}`, res.status);
  }
  const body = (await res.json()) as SessionsResponse;
  return body.sessions ?? [];
}

/** Fetches one session with its recent event history (`GET /sessions/{id}`). */
export async function fetchSessionDetail(id: string): Promise<SessionDetail> {
  const res = await fetch(`/sessions/${encodeURIComponent(id)}`, {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /sessions/${id} failed: ${res.status}`, res.status);
  }
  return (await res.json()) as SessionDetail;
}

/** Lists active + recent alerts for the inbox (`GET /alerts`). */
export async function fetchAlerts(): Promise<AlertSummary[]> {
  const res = await fetch('/alerts', {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /alerts failed: ${res.status}`, res.status);
  }
  const body = (await res.json()) as AlertsResponse;
  return body.alerts ?? [];
}

/**
 * Acknowledges an active alert (`POST /alerts/{id}/ack`). Resolves on `204`.
 * Acking a non-active alert yields `409`, surfaced as an {@link ApiError}.
 */
export async function ackAlert(id: string): Promise<void> {
  const res = await fetch(`/alerts/${encodeURIComponent(id)}/ack`, {
    method: 'POST',
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`POST /alerts/${id}/ack failed: ${res.status}`, res.status);
  }
}

/** Lists pending + recent approval requests for the inbox (`GET /approvals`). */
export async function fetchApprovals(): Promise<ApprovalSummary[]> {
  const res = await fetch('/approvals', {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /approvals failed: ${res.status}`, res.status);
  }
  const body = (await res.json()) as ApprovalsResponse;
  return body.approvals ?? [];
}

/** Outcome of a decide call: either it applied, or it was already resolved. */
export type DecideResult =
  | { outcome: 'applied'; status: Extract<ApprovalStatus, 'approved' | 'denied'> }
  | { outcome: 'already_resolved' };

/**
 * Decides a pending approval (`POST /approvals/{id}/decide`).
 * - `200` → `{ outcome: 'applied', status }`.
 * - `409 already_resolved` → `{ outcome: 'already_resolved' }` (handled
 *   gracefully; the first decision on another surface already won).
 * Any other non-OK status is surfaced as an {@link ApiError}.
 */
export async function decideApproval(
  id: string,
  decision: 'approve' | 'deny',
): Promise<DecideResult> {
  const res = await fetch(`/approvals/${encodeURIComponent(id)}/decide`, {
    method: 'POST',
    credentials: 'include',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify({ decision }),
  });
  if (res.status === 409) {
    return { outcome: 'already_resolved' };
  }
  if (!res.ok) {
    throw new ApiError(
      `POST /approvals/${id}/decide failed: ${res.status}`,
      res.status,
    );
  }
  const body = (await res.json()) as { status: 'approved' | 'denied' };
  return { outcome: 'applied', status: body.status };
}

// ---------------------------------------------------------------------------
// Phase 3 — Remote control & task dispatch
// ---------------------------------------------------------------------------

/**
 * Outcome of issuing a control command. On `202` the command is accepted and
 * its `commandId` is tracked for live `control_event` updates. Expected refusals
 * (`404` no such session, `409` machine offline) are surfaced as `rejected`
 * rather than thrown, so the UI can show a reason without crashing; any other
 * non-2xx status is thrown as an {@link ApiError}.
 */
export type ControlResult =
  | { outcome: 'accepted'; commandId: string; status: ControlStatus }
  | { outcome: 'rejected'; httpStatus: number; reason: string };

const REJECTION_REASON: Record<number, string> = {
  404: 'No such session.',
  409: 'The machine is offline.',
};

/** Shared handling for the `202 { commandId, status }` control endpoints. */
async function issueControl(
  url: string,
  body: unknown,
  label: string,
): Promise<ControlResult> {
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (res.status === 404 || res.status === 409) {
    return {
      outcome: 'rejected',
      httpStatus: res.status,
      reason: REJECTION_REASON[res.status] ?? `Refused (${res.status}).`,
    };
  }
  if (!res.ok) {
    throw new ApiError(`${label} failed: ${res.status}`, res.status);
  }
  const parsed = (await res.json()) as { commandId: string; status: ControlStatus };
  return { outcome: 'accepted', commandId: parsed.commandId, status: parsed.status };
}

/**
 * Sends an instruction to a monitored session (`POST /sessions/{id}/dispatch`).
 * → `202 { commandId, status: 'pending' }`; `404` if gone, `409` if offline.
 */
export async function dispatchTask(
  sessionId: string,
  instruction: string,
): Promise<ControlResult> {
  return issueControl(
    `/sessions/${encodeURIComponent(sessionId)}/dispatch`,
    { instruction },
    `POST /sessions/${sessionId}/dispatch`,
  );
}

/**
 * Starts a new persistent Claude Code run (`POST /machines/{id}/start-run`).
 * → `202 { commandId, status: 'pending' }`; `409` if the machine is offline.
 */
export async function startRun(
  machineId: string,
  projectPath: string,
  instruction: string,
): Promise<ControlResult> {
  return issueControl(
    `/machines/${encodeURIComponent(machineId)}/start-run`,
    { projectPath, instruction },
    `POST /machines/${machineId}/start-run`,
  );
}

/**
 * Stops a running session (`POST /sessions/{id}/stop`, graceful → force).
 * → `202 { commandId, status: 'pending' }`; `404` if no such session.
 */
export async function stopSession(sessionId: string): Promise<ControlResult> {
  return issueControl(
    `/sessions/${encodeURIComponent(sessionId)}/stop`,
    {},
    `POST /sessions/${sessionId}/stop`,
  );
}

/** Lists recent control commands + their status for the strip (`GET /commands`). */
export async function fetchCommands(): Promise<ControlCommandSummary[]> {
  const res = await fetch('/commands', {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /commands failed: ${res.status}`, res.status);
  }
  const body = (await res.json()) as CommandsResponse;
  return body.commands ?? [];
}

/**
 * Starts a managed (Agent-SDK) run (`POST /machines/{id}/start-managed`).
 * → `202 { commandId, status: 'pending' }`; `409` if the machine is offline.
 * The session appears in monitoring marked managed.
 */
export async function startManaged(
  machineId: string,
  projectPath: string,
  instruction: string,
): Promise<ControlResult> {
  return issueControl(
    `/machines/${encodeURIComponent(machineId)}/start-managed`,
    { projectPath, instruction },
    `POST /machines/${machineId}/start-managed`,
  );
}

// ---------------------------------------------------------------------------
// Phase 4 — Full interactive control (questions, transcript, search)
// ---------------------------------------------------------------------------

/** Lists pending + recent free-form questions for the inbox (`GET /questions`). */
export async function fetchQuestions(): Promise<QuestionSummary[]> {
  const res = await fetch('/questions', {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /questions failed: ${res.status}`, res.status);
  }
  const body = (await res.json()) as QuestionsResponse;
  return body.questions ?? [];
}

/** Outcome of answering a question: it applied, or it was already resolved. */
export type AnswerResult =
  | { outcome: 'applied'; status: 'answered' | 'cancelled' }
  | { outcome: 'already_resolved' };

/** The two ways to resolve a pending question: supply an answer, or cancel it. */
export type AnswerInput = { answer: string } | { cancel: true };

/**
 * Answers or cancels a pending question (`POST /questions/{id}/answer`).
 * - `200` → `{ outcome: 'applied', status }` (`answered` or `cancelled`).
 * - `409 already_resolved` → `{ outcome: 'already_resolved' }` (handled
 *   gracefully; the question was already resolved — e.g. by a crash/cancel).
 * The answer is applied at most once; ClaudeDriver NEVER fabricates one.
 */
export async function answerQuestion(
  id: string,
  input: AnswerInput,
): Promise<AnswerResult> {
  const res = await fetch(`/questions/${encodeURIComponent(id)}/answer`, {
    method: 'POST',
    credentials: 'include',
    headers: { accept: 'application/json', 'content-type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (res.status === 409) {
    return { outcome: 'already_resolved' };
  }
  if (!res.ok) {
    throw new ApiError(
      `POST /questions/${id}/answer failed: ${res.status}`,
      res.status,
    );
  }
  const body = (await res.json()) as { status: 'answered' | 'cancelled' };
  return { outcome: 'applied', status: body.status };
}

/** Fetches the full ordered transcript of a session (`GET /sessions/{id}/transcript`). */
export async function fetchTranscript(
  sessionId: string,
): Promise<TranscriptMessage[]> {
  const res = await fetch(
    `/sessions/${encodeURIComponent(sessionId)}/transcript`,
    { credentials: 'include', headers: { accept: 'application/json' } },
  );
  if (!res.ok) {
    throw new ApiError(
      `GET /sessions/${sessionId}/transcript failed: ${res.status}`,
      res.status,
    );
  }
  const body = (await res.json()) as TranscriptResponse;
  return body.messages ?? [];
}

/** Cross-session transcript search (`GET /search?q=`). Bounded; never blocks live control. */
export async function search(q: string): Promise<SearchResult[]> {
  const res = await fetch(`/search?q=${encodeURIComponent(q)}`, {
    credentials: 'include',
    headers: { accept: 'application/json' },
  });
  if (!res.ok) {
    throw new ApiError(`GET /search failed: ${res.status}`, res.status);
  }
  const body = (await res.json()) as SearchResponse;
  return body.results ?? [];
}
