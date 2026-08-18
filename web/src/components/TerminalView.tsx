import { useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';
import type { TerminalSummary } from '../types';
import { fetchTerminalScrollback } from '../api';
import { b64ToBytes, strToB64, subscribeTerminalData } from '../terminal/terminalBus';

interface TerminalViewProps {
  terminal: TerminalSummary;
  /** Send operator keystrokes (base64 of UTF-8) to this terminal. */
  onInput: (terminalId: string, dataB64: string) => void;
}

/**
 * A live xterm.js view of one mirrored terminal. Renders recent scrollback on
 * attach, streams `terminal_data` as it arrives, and — while the terminal is
 * open — forwards keystrokes back to the machine. A closed terminal is shown
 * read-only (its final output stays visible).
 */
export function TerminalView({ terminal, onInput }: TerminalViewProps) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const termRef = useRef<Terminal | null>(null);
  const [ready, setReady] = useState(false);
  const readOnly = terminal.status !== 'open';

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    const term = new Terminal({
      convertEol: false,
      cursorBlink: !readOnly,
      fontFamily:
        'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace',
      fontSize: 13,
      theme: { background: '#0b0f14', foreground: '#d6deeb' },
      disableStdin: readOnly,
      scrollback: 5000,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(host);
    try {
      fit.fit();
    } catch {
      /* host not yet measured — ignore */
    }
    termRef.current = term;
    setReady(true);

    // Forward keystrokes to the machine (only meaningful while open).
    const dataSub = term.onData((data) => {
      if (!readOnly) onInput(terminal.terminalId, strToB64(data));
    });

    // Live output for this terminal.
    const unsub = subscribeTerminalData(terminal.terminalId, (b64) => {
      term.write(b64ToBytes(b64));
    });

    // Seed with recent scrollback, then the live stream continues from here.
    let cancelled = false;
    void fetchTerminalScrollback(terminal.terminalId)
      .then((b64) => {
        if (!cancelled && b64) term.write(b64ToBytes(b64));
      })
      .catch(() => {
        /* scrollback is best-effort; the live stream still works */
      });

    const onResize = () => {
      try {
        fit.fit();
      } catch {
        /* ignore */
      }
    };
    window.addEventListener('resize', onResize);

    return () => {
      cancelled = true;
      window.removeEventListener('resize', onResize);
      unsub();
      dataSub.dispose();
      term.dispose();
      termRef.current = null;
    };
    // Re-create the terminal when switching to a different session or its open/closed state flips.
  }, [terminal.terminalId, readOnly, onInput]);

  return (
    <div className="terminal-view">
      <div className="terminal-view__meta">
        <span className="terminal-view__cwd" title={terminal.cwd}>
          {terminal.cwd || '(no directory)'}
        </span>
        <span className="terminal-view__machine">{terminal.machineName}</span>
        {readOnly && (
          <span className="terminal-view__closed">
            closed{terminal.exitCode != null ? ` · exit ${terminal.exitCode}` : ''}
          </span>
        )}
      </div>
      <div ref={hostRef} className="terminal-view__screen" />
      {!ready && <div className="terminal-view__loading">Attaching…</div>}
    </div>
  );
}
