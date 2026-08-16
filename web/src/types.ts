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
export const PROTOCOL_VERSION = '0.1.0';

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
