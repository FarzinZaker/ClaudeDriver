/**
 * Operator WebSocket client.
 *
 * Connects to the operator WS stream, parses shared-contract envelopes
 * (protocol.md), and surfaces `sample_event` frames to a consumer. It also
 * reports connection state so the UI can show a health indicator, and
 * reconnects with capped backoff after a drop (FR-017 semantics, applied to the
 * operator side).
 *
 * The client does NOT know about TanStack Query; `App` wires the callbacks to
 * patch the query cache. This keeps the transport testable in isolation.
 */

import {
  isAlertEventEnvelope,
  isApprovalEventEnvelope,
  isControlEventEnvelope,
  isSampleEventEnvelope,
  isSessionUpdateEnvelope,
  type AlertEventPayload,
  type ApprovalEventPayload,
  type ControlEventPayload,
  type Envelope,
  type SampleEventPayload,
  type SessionUpdatePayload,
} from '../types';

export type WsStatus = 'connecting' | 'open' | 'closed';

export interface OperatorWsCallbacks {
  onStatusChange?: (status: WsStatus) => void;
  onSampleEvent?: (event: SampleEventPayload) => void;
  /** Phase 1: live session state for the dashboard (`session_update`). */
  onSessionUpdate?: (event: SessionUpdatePayload) => void;
  /** Phase 1: alert raised / changed / resolved (`alert_event`). */
  onAlertEvent?: (event: AlertEventPayload) => void;
  /** Phase 2: approval raised / decided / resolved (`approval_event`). */
  onApprovalEvent?: (event: ApprovalEventPayload) => void;
  /** Phase 3: control command status change (`control_event`). */
  onControlEvent?: (event: ControlEventPayload) => void;
  /** Any well-formed envelope, for callers that want the full stream. */
  onEnvelope?: (envelope: Envelope<unknown>) => void;
}

export interface OperatorWsOptions extends OperatorWsCallbacks {
  /** Injectable for tests; defaults to the global `WebSocket`. */
  socketFactory?: (url: string) => WebSocket;
  /** Injectable for tests; disable reconnection to keep tests deterministic. */
  reconnect?: boolean;
  baseReconnectMs?: number;
  maxReconnectMs?: number;
}

/** Resolves the operator WS URL from the current page origin (proxied at `/ws`). */
export function operatorWsUrl(path = '/ws'): string {
  if (typeof window === 'undefined') return path;
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}${path}`;
}

export class OperatorWsClient {
  private socket: WebSocket | null = null;
  private closedByUser = false;
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly url: string;
  private readonly opts: Required<
    Pick<OperatorWsOptions, 'reconnect' | 'baseReconnectMs' | 'maxReconnectMs'>
  > &
    OperatorWsCallbacks & {
      socketFactory: (url: string) => WebSocket;
    };

  constructor(url: string, options: OperatorWsOptions = {}) {
    this.url = url;
    this.opts = {
      reconnect: options.reconnect ?? true,
      baseReconnectMs: options.baseReconnectMs ?? 1000,
      maxReconnectMs: options.maxReconnectMs ?? 15000,
      socketFactory: options.socketFactory ?? ((u) => new WebSocket(u)),
      onStatusChange: options.onStatusChange,
      onSampleEvent: options.onSampleEvent,
      onSessionUpdate: options.onSessionUpdate,
      onAlertEvent: options.onAlertEvent,
      onApprovalEvent: options.onApprovalEvent,
      onControlEvent: options.onControlEvent,
      onEnvelope: options.onEnvelope,
    };
  }

  connect(): void {
    this.closedByUser = false;
    this.open();
  }

  private open(): void {
    this.opts.onStatusChange?.('connecting');
    const socket = this.opts.socketFactory(this.url);
    this.socket = socket;

    socket.addEventListener('open', () => {
      this.attempt = 0;
      this.opts.onStatusChange?.('open');
    });

    socket.addEventListener('message', (ev: MessageEvent) => {
      this.handleMessage(ev.data);
    });

    socket.addEventListener('close', () => {
      this.opts.onStatusChange?.('closed');
      this.scheduleReconnect();
    });

    socket.addEventListener('error', () => {
      // A dead socket also fires 'close'; reconnection is handled there.
      try {
        socket.close();
      } catch {
        /* ignore */
      }
    });
  }

  private handleMessage(data: unknown): void {
    if (typeof data !== 'string') return;
    let envelope: Envelope<unknown>;
    try {
      envelope = JSON.parse(data) as Envelope<unknown>;
    } catch {
      return; // malformed frame — ignore, never crash the stream
    }
    if (!envelope || typeof envelope.type !== 'string') return;

    this.opts.onEnvelope?.(envelope);

    // Unknown types on a compatible version are ignored (forward-compat, protocol.md).
    if (isSampleEventEnvelope(envelope)) {
      this.opts.onSampleEvent?.(envelope.payload);
    } else if (isSessionUpdateEnvelope(envelope)) {
      this.opts.onSessionUpdate?.(envelope.payload);
    } else if (isAlertEventEnvelope(envelope)) {
      this.opts.onAlertEvent?.(envelope.payload);
    } else if (isApprovalEventEnvelope(envelope)) {
      this.opts.onApprovalEvent?.(envelope.payload);
    } else if (isControlEventEnvelope(envelope)) {
      this.opts.onControlEvent?.(envelope.payload);
    }
  }

  private scheduleReconnect(): void {
    if (this.closedByUser || !this.opts.reconnect) return;
    const delay = Math.min(
      this.opts.baseReconnectMs * 2 ** this.attempt,
      this.opts.maxReconnectMs,
    );
    this.attempt += 1;
    this.reconnectTimer = setTimeout(() => this.open(), delay);
  }

  close(): void {
    this.closedByUser = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.socket) {
      try {
        this.socket.close();
      } catch {
        /* ignore */
      }
      this.socket = null;
    }
  }
}
