/**
 * A minimal pub/sub for live terminal output. The operator WS client is wired
 * once in `App`; each mounted `TerminalView` subscribes here by terminalId so
 * incoming `terminal_data` frames reach the right xterm instance without
 * threading callbacks through the component tree.
 */
type DataListener = (dataB64: string) => void;

const listeners = new Map<string, Set<DataListener>>();

export function subscribeTerminalData(terminalId: string, cb: DataListener): () => void {
  let set = listeners.get(terminalId);
  if (!set) {
    set = new Set();
    listeners.set(terminalId, set);
  }
  set.add(cb);
  return () => {
    const s = listeners.get(terminalId);
    if (!s) return;
    s.delete(cb);
    if (s.size === 0) listeners.delete(terminalId);
  };
}

export function emitTerminalData(terminalId: string, dataB64: string): void {
  listeners.get(terminalId)?.forEach((cb) => {
    try {
      cb(dataB64);
    } catch {
      /* a bad listener must not break the stream */
    }
  });
}

/** base64 → bytes (for writing PTY output into xterm, which UTF-8-decodes itself). */
export function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
  return out;
}

/** string → base64 of its UTF-8 bytes (for sending keystrokes). */
export function strToB64(s: string): string {
  const bytes = new TextEncoder().encode(s);
  let bin = '';
  for (let i = 0; i < bytes.length; i += 1) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}
