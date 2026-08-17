/**
 * Single source of the wire types the operator web client consumes.
 *
 * These MIRROR the shared contract (the canonical definition lives in
 * `shared/commonMain`, kotlinx.serialization). See:
 *   - specs/001-phase-0-foundations/contracts/protocol.md  (WebSocket envelope + messages)
 *   - specs/001-phase-0-foundations/contracts/rest-api.md   (GET /status shape)
 *
 * Phase 0 only. Later phases add message types under the same envelope/version rules.
 */

/** Semver of the shared protocol contract this client speaks. */
export const PROTOCOL_VERSION = '0.5.0';

// ---------------------------------------------------------------------------
// REST — GET /status
// ---------------------------------------------------------------------------

export type MachineOs = 'windows' | 'macos';
export type MachineStatus = 'pending' | 'enrolled' | 'revoked';
export type ConnectionState = 'connected' | 'disconnected';

export interface MachineConnection {
  state: ConnectionState;
  /** RFC3339 timestamp, or null when never connected. */
  since: string | null;
  /** Negotiated contract version, or null when not connected. */
  protocolVersion: string | null;
}

export interface Machine {
  id: string;
  name: string;
  os: MachineOs;
  status: MachineStatus;
  connection: MachineConnection;
}

export interface ServerInfo {
  version: string;
  /** RFC3339 timestamp. */
  time: string;
}

/** A relayed `sample_event` payload as surfaced on the status page inbox. */
export interface SampleEventRecord {
  machineId: string;
  message: string;
  /** RFC3339 timestamp. */
  at: string;
}

/** Response body of `GET /status`. This is what the status page seeds from. */
export interface StatusResponse {
  server: ServerInfo;
  machines: Machine[];
  recentSampleEvents: SampleEventRecord[];
}

// ---------------------------------------------------------------------------
// WebSocket — shared envelope (protocol.md)
// ---------------------------------------------------------------------------

/**
 * Every WS frame is an envelope. `payload` is type-specific; discriminate on `type`.
 */
export interface Envelope<TPayload = unknown> {
  /** Semver of THIS contract. */
  protocolVersion: string;
  /** Discriminator — see message types. */
  type: string;
  /** Per-connection monotonic sequence (sender-assigned). */
  seq: number;
  /** Present on commands; enables idempotent dedupe. */
  commandId?: string | null;
  payload: TPayload;
}

// Phase 0 payloads (only those the operator client cares about are typed strongly).

export interface HelloAckPayload {
  machineId: string;
  serverTime: string;
  heartbeatSeconds: number;
}

export interface VersionMismatchPayload {
  serverVersion: string;
  reason: string;
}

export interface PingPongPayload {
  /** RFC3339 timestamp. */
  t: string;
}

/**
 * `sample_event` — the Phase 0 end-to-end demonstration message.
 * agent → backend → operator web, interpreted identically everywhere (FR-016).
 */
export interface SampleEventPayload {
  machineId: string;
  message: string;
  /** RFC3339 timestamp. */
  at: string;
}

export type SampleEventEnvelope = Envelope<SampleEventPayload> & { type: 'sample_event' };

/** Narrowing guard for an incoming `sample_event` envelope. */
export function isSampleEventEnvelope(
  env: Envelope<unknown>,
): env is SampleEventEnvelope {
  if (env.type !== 'sample_event') return false;
  const p = env.payload as Partial<SampleEventPayload> | null;
  return (
    !!p &&
    typeof p.machineId === 'string' &&
    typeof p.message === 'string' &&
    typeof p.at === 'string'
  );
}

// ---------------------------------------------------------------------------
// Phase 1 — Process monitoring (specs/002-process-monitoring)
//   REST additions:  contracts/rest-api-additions.md
//   Protocol adds:   contracts/protocol-additions.md
// ---------------------------------------------------------------------------

/** Lifecycle of a monitored Claude Code session (FR-008/FR-009). */
export type SessionState =
  | 'running'
  | 'waiting_for_operator'
  | 'finished'
  | 'stopped'
  | 'unknown_stale';

/** A monitored session as returned by `GET /sessions` (and inside a detail). */
export interface SessionSummary {
  id: string;
  machineId: string;
  machineName: string;
  /** Project / working directory, or null when not yet known. */
  projectPath: string | null;
  state: SessionState;
  /** RFC3339 timestamp of the last observed activity. */
  lastActivityAt: string;
  /** Whether a detected OS process currently backs this session. */
  processPresent: boolean;
}

/** Whether an activity event needs the operator or is routine (FR-006). */
export type ActivityAttention = 'needs_attention' | 'informational';

/** One entry of a session's bounded recent-event history (`GET /sessions/{id}`). */
export interface ActivityEventSummary {
  kind: string;
  attention: ActivityAttention;
  summary: string;
  /** RFC3339 timestamp. */
  at: string;
}

/** Response body of `GET /sessions/{id}`. */
export interface SessionDetail {
  session: SessionSummary;
  recentEvents: ActivityEventSummary[];
}

export type AlertStatus = 'active' | 'acknowledged' | 'resolved';
export type AlertUrgency = 'high' | 'normal' | 'low';
export type AlertResolvedReason =
  | 'answered'
  | 'session_stopped'
  | 'process_gone'
  | null;

/** An operator-facing alert as returned by `GET /alerts`. */
export interface AlertSummary {
  id: string;
  sessionId: string;
  machineId: string;
  machineName: string;
  status: AlertStatus;
  urgency: AlertUrgency;
  summary: string;
  /** RFC3339 timestamp of when the alert was raised. */
  raisedAt: string;
  resolvedReason: AlertResolvedReason;
}

/** Response body of `GET /sessions`. */
export interface SessionsResponse {
  sessions: SessionSummary[];
}

/** Response body of `GET /alerts`. */
export interface AlertsResponse {
  alerts: AlertSummary[];
}

// --- WebSocket: session_update -------------------------------------------

/**
 * `session_update` payload. Note the wire uses `sessionId` (not `id`) and does
 * NOT carry `machineName` — the operator client resolves the display name from
 * the machine fleet / existing cache when patching.
 */
export interface SessionUpdatePayload {
  sessionId: string;
  machineId: string;
  projectPath: string | null;
  state: SessionState;
  lastActivityAt: string;
  processPresent: boolean;
}

export type SessionUpdateEnvelope = Envelope<SessionUpdatePayload> & {
  type: 'session_update';
};

/** Narrowing guard for an incoming `session_update` envelope. */
export function isSessionUpdateEnvelope(
  env: Envelope<unknown>,
): env is SessionUpdateEnvelope {
  if (env.type !== 'session_update') return false;
  const p = env.payload as Partial<SessionUpdatePayload> | null;
  return (
    !!p &&
    typeof p.sessionId === 'string' &&
    typeof p.machineId === 'string' &&
    typeof p.state === 'string' &&
    typeof p.lastActivityAt === 'string' &&
    typeof p.processPresent === 'boolean'
  );
}

// --- WebSocket: alert_event ----------------------------------------------

/**
 * `alert_event` payload. The wire uses `alertId` (not `id`) and omits
 * `machineName`; the client resolves the name when patching the inbox cache.
 */
export interface AlertEventPayload {
  alertId: string;
  sessionId: string;
  machineId: string;
  status: AlertStatus;
  urgency: AlertUrgency;
  summary: string;
  raisedAt: string;
  resolvedReason: AlertResolvedReason;
}

export type AlertEventEnvelope = Envelope<AlertEventPayload> & {
  type: 'alert_event';
};

/** Narrowing guard for an incoming `alert_event` envelope. */
export function isAlertEventEnvelope(
  env: Envelope<unknown>,
): env is AlertEventEnvelope {
  if (env.type !== 'alert_event') return false;
  const p = env.payload as Partial<AlertEventPayload> | null;
  return (
    !!p &&
    typeof p.alertId === 'string' &&
    typeof p.sessionId === 'string' &&
    typeof p.machineId === 'string' &&
    typeof p.status === 'string' &&
    typeof p.summary === 'string'
  );
}

// ---------------------------------------------------------------------------
// Phase 2 — Remote approvals (specs/003-remote-approvals)
//   REST additions:  contracts/rest-api-additions.md
//   Protocol adds:   contracts/protocol-additions.md
// ---------------------------------------------------------------------------

/**
 * Lifecycle of a remote tool-permission approval request. `pending` blocks a
 * real waiting Claude Code instance; the terminal states are `approved`,
 * `denied`, and `moot` (the instance disappeared before a decision).
 */
export type ApprovalStatus = 'pending' | 'approved' | 'denied' | 'moot';

/**
 * A remote approval request as returned by `GET /approvals` (`decidedBy` and
 * `reason` are populated once a decision or resolution has occurred).
 */
export interface ApprovalSummary {
  id: string;
  machineId: string;
  machineName: string;
  /** The Claude Code session that raised the permission prompt. */
  claudeSessionId: string;
  /** The tool Claude Code asked to use (e.g. `Bash`). */
  tool: string;
  /** Human summary of the exact action requested (e.g. ``Bash: `git push` ``). */
  summary: string;
  status: ApprovalStatus;
  /** RFC3339 timestamp of when the request was raised. */
  createdAt: string;
  /** Who decided it (`operator`), or null while pending. */
  decidedBy?: string | null;
  /** Decision/resolution reason, or null while pending. */
  reason?: string | null;
}

/** Response body of `GET /approvals`. */
export interface ApprovalsResponse {
  approvals: ApprovalSummary[];
}

// --- WebSocket: approval_event -------------------------------------------

/**
 * `approval_event` payload. Unlike `alert_event`, the wire carries
 * `machineName` directly, and uses `approvalId` (not `id`) with `at` (not
 * `createdAt`).
 */
export interface ApprovalEventPayload {
  approvalId: string;
  machineId: string;
  machineName: string;
  claudeSessionId: string;
  tool: string;
  summary: string;
  status: ApprovalStatus;
  /** RFC3339 timestamp of the raise/decision/resolution. */
  at: string;
  decidedBy?: string | null;
  reason?: string | null;
}

export type ApprovalEventEnvelope = Envelope<ApprovalEventPayload> & {
  type: 'approval_event';
};

/** Narrowing guard for an incoming `approval_event` envelope. */
export function isApprovalEventEnvelope(
  env: Envelope<unknown>,
): env is ApprovalEventEnvelope {
  if (env.type !== 'approval_event') return false;
  const p = env.payload as Partial<ApprovalEventPayload> | null;
  return (
    !!p &&
    typeof p.approvalId === 'string' &&
    typeof p.machineId === 'string' &&
    typeof p.tool === 'string' &&
    typeof p.summary === 'string' &&
    typeof p.status === 'string'
  );
}

// ---------------------------------------------------------------------------
// Phase 3 — Remote control & task dispatch (specs/004-task-dispatch)
//   REST additions:  contracts/rest-api-additions.md
//   Protocol adds:   contracts/protocol-additions.md
// ---------------------------------------------------------------------------

/**
 * The three operator-issued control actions. `dispatch_task` sends an
 * instruction to an existing monitored session, `start_run` launches a new
 * persistent Claude Code run on a machine/project, and `stop_session` ends a
 * running session.
 */
export type ControlCommandType =
  | 'start_run'
  | 'dispatch_task'
  | 'stop_session'
  | 'start_managed';

/**
 * Lifecycle of a control command. `pending` is the initial accepted state; the
 * agent then reports one of the terminal outcomes (`started` for a new run,
 * `delivered` for a dispatched task, `done`, `stopped`, `undeliverable`, or
 * `error`). Applied to the command idempotently — only if not already terminal.
 */
export type ControlStatus =
  | 'pending'
  | 'started'
  | 'delivered'
  | 'done'
  | 'stopped'
  | 'undeliverable'
  | 'error';

/**
 * A control command + its status as returned by `GET /commands` (which seeds
 * the activity strip; live updates arrive as `control_event`).
 */
export interface ControlCommandSummary {
  id: string;
  machineId: string;
  machineName: string;
  type: ControlCommandType;
  /** Target Claude session for dispatch/stop, or null (e.g. start_run). */
  claudeSessionId?: string | null;
  /** The instruction text for dispatch/start_run, or null (e.g. stop). */
  instruction?: string | null;
  status: ControlStatus;
  /** RFC3339 timestamp of when the command was created. */
  createdAt: string;
  /** Latest result/diagnostic message, or null. */
  message?: string | null;
}

/** Response body of `GET /commands`. */
export interface CommandsResponse {
  commands: ControlCommandSummary[];
}

// --- WebSocket: control_event --------------------------------------------

/**
 * `control_event` payload — a command's status for the live UI. Note the wire
 * uses `commandId` (not `id`), `commandType` (not `type`), and `at` (not
 * `createdAt`); it carries `machineName` directly (like `approval_event`).
 */
export interface ControlEventPayload {
  commandId: string;
  machineId: string;
  machineName: string;
  commandType: ControlCommandType;
  status: ControlStatus;
  claudeSessionId?: string | null;
  /** RFC3339 timestamp of the status change. */
  at: string;
  message?: string | null;
}

export type ControlEventEnvelope = Envelope<ControlEventPayload> & {
  type: 'control_event';
};

/** Narrowing guard for an incoming `control_event` envelope. */
export function isControlEventEnvelope(
  env: Envelope<unknown>,
): env is ControlEventEnvelope {
  if (env.type !== 'control_event') return false;
  const p = env.payload as Partial<ControlEventPayload> | null;
  return (
    !!p &&
    typeof p.commandId === 'string' &&
    typeof p.machineId === 'string' &&
    typeof p.commandType === 'string' &&
    typeof p.status === 'string'
  );
}

// ---------------------------------------------------------------------------
// Phase 4 — Full interactive control (specs/005-interactive-control)
//   REST additions:  contracts/rest-api-additions.md
//   Protocol adds:   contracts/protocol-additions.md
// ---------------------------------------------------------------------------

/**
 * Lifecycle of a free-form question posed by a managed session. `pending` blocks
 * a real session that is waiting on the operator; the terminal states are
 * `answered` (the operator supplied text), `cancelled` (the operator declined —
 * the session is told no answer was given), and `unanswered` (resolved without
 * an answer, e.g. the runtime crashed or forced resolution). Never fabricated.
 */
export type QuestionStatus = 'pending' | 'answered' | 'cancelled' | 'unanswered';

/**
 * A free-form question as returned by `GET /questions`. `answer` and `resolvedBy`
 * populate once the question resolves.
 */
export interface QuestionSummary {
  id: string;
  machineId: string;
  machineName: string;
  /** The managed Claude session that posed the question. */
  claudeSessionId: string;
  /** The free-form question text shown to the operator. */
  text: string;
  status: QuestionStatus;
  /** RFC3339 timestamp of when the question was raised. */
  createdAt: string;
  /** The operator's answer once answered, else null. */
  answer?: string | null;
  /** Who resolved it (`operator`), or null while pending. */
  resolvedBy?: string | null;
}

/** Response body of `GET /questions`. */
export interface QuestionsResponse {
  questions: QuestionSummary[];
}

/** One ordered message in a managed session's reconstructed transcript. */
export interface TranscriptMessage {
  /** Speaker role — `assistant | user | tool | system`. */
  role: string;
  text: string;
  /** RFC3339 timestamp. */
  at: string;
}

/** Response body of `GET /sessions/{id}/transcript`. */
export interface TranscriptResponse {
  messages: TranscriptMessage[];
}

/** One cross-session search hit as returned by `GET /search`. */
export interface SearchResult {
  sessionId: string;
  machineName: string;
  claudeSessionId: string;
  role: string;
  /** A snippet of transcript text around the match. */
  snippet: string;
  /** RFC3339 timestamp. */
  at: string;
}

/** Response body of `GET /search`. */
export interface SearchResponse {
  results: SearchResult[];
}

// --- WebSocket: question_event -------------------------------------------

/**
 * `question_event` payload. Like `approval_event`, the wire carries
 * `machineName` directly and uses `questionId` (not `id`) with `at` (not
 * `createdAt`).
 */
export interface QuestionEventPayload {
  questionId: string;
  machineId: string;
  machineName: string;
  claudeSessionId: string;
  text: string;
  status: QuestionStatus;
  /** RFC3339 timestamp of the raise/resolution. */
  at: string;
  resolvedBy?: string | null;
}

export type QuestionEventEnvelope = Envelope<QuestionEventPayload> & {
  type: 'question_event';
};

/** Narrowing guard for an incoming `question_event` envelope. */
export function isQuestionEventEnvelope(
  env: Envelope<unknown>,
): env is QuestionEventEnvelope {
  if (env.type !== 'question_event') return false;
  const p = env.payload as Partial<QuestionEventPayload> | null;
  return (
    !!p &&
    typeof p.questionId === 'string' &&
    typeof p.machineId === 'string' &&
    typeof p.claudeSessionId === 'string' &&
    typeof p.text === 'string' &&
    typeof p.status === 'string'
  );
}

// --- WebSocket: transcript_event -----------------------------------------

/**
 * `transcript_event` payload — one new ordered message in a managed session's
 * conversation. Keyed by `claudeSessionId`; carries `machineId`.
 */
export interface TranscriptEventPayload {
  claudeSessionId: string;
  machineId: string;
  role: string;
  text: string;
  /** RFC3339 timestamp. */
  at: string;
}

export type TranscriptEventEnvelope = Envelope<TranscriptEventPayload> & {
  type: 'transcript_event';
};

/** Narrowing guard for an incoming `transcript_event` envelope. */
export function isTranscriptEventEnvelope(
  env: Envelope<unknown>,
): env is TranscriptEventEnvelope {
  if (env.type !== 'transcript_event') return false;
  const p = env.payload as Partial<TranscriptEventPayload> | null;
  return (
    !!p &&
    typeof p.claudeSessionId === 'string' &&
    typeof p.role === 'string' &&
    typeof p.text === 'string' &&
    typeof p.at === 'string'
  );
}
