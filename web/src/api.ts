/** Operator REST calls the status page depends on. */

import type {
  AlertSummary,
  AlertsResponse,
  ApprovalStatus,
  ApprovalSummary,
  ApprovalsResponse,
  SessionDetail,
  SessionSummary,
  SessionsResponse,
  StatusResponse,
} from './types';

/** TanStack Query key for the seeded `/status` snapshot. */
export const STATUS_QUERY_KEY = ['status'] as const;
/** TanStack Query key for the seeded `/sessions` list. */
export const SESSIONS_QUERY_KEY = ['sessions'] as const;
/** TanStack Query key for the seeded `/alerts` inbox. */
export const ALERTS_QUERY_KEY = ['alerts'] as const;
/** TanStack Query key for the seeded `/approvals` inbox. */
export const APPROVALS_QUERY_KEY = ['approvals'] as const;
/** TanStack Query key factory for a single session's detail. */
export const sessionDetailQueryKey = (id: string) =>
  ['session', id] as const;

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
