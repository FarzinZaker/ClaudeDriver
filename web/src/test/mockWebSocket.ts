/**
 * Minimal controllable WebSocket stand-in for tests. Registers the most recent
 * instance so a test can drive incoming frames via `emitMessage`.
 */
import { vi } from 'vitest';

type Listener = (ev: unknown) => void;

export class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static get last(): MockWebSocket | undefined {
    return MockWebSocket.instances[MockWebSocket.instances.length - 1];
  }
  static reset() {
    MockWebSocket.instances = [];
  }

  readonly url: string;
  readyState = 0; // CONNECTING
  private listeners: Record<string, Listener[]> = {};
  close = vi.fn(() => {
    this.readyState = 3;
    this.dispatch('close', {});
  });
  send = vi.fn();

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  addEventListener(type: string, cb: Listener) {
    (this.listeners[type] ??= []).push(cb);
  }
  removeEventListener(type: string, cb: Listener) {
    this.listeners[type] = (this.listeners[type] ?? []).filter((l) => l !== cb);
  }

  private dispatch(type: string, ev: unknown) {
    for (const l of this.listeners[type] ?? []) l(ev);
  }

  /** Simulate the socket opening. */
  emitOpen() {
    this.readyState = 1;
    this.dispatch('open', {});
  }

  /** Simulate an incoming frame (object is JSON-stringified as on the wire). */
  emitMessage(data: unknown) {
    this.dispatch('message', {
      data: typeof data === 'string' ? data : JSON.stringify(data),
    });
  }
}
