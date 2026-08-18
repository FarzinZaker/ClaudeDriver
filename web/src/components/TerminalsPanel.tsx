import type { TerminalSummary } from '../types';
import { TerminalView } from './TerminalView';

interface TerminalsPanelProps {
  terminals: TerminalSummary[];
  selectedId: string | null;
  onSelect: (terminalId: string | null) => void;
  onInput: (terminalId: string, dataB64: string) => void;
}

/**
 * The live-terminals surface: a list of terminals mirrored from `claude` shims
 * across the fleet, and the attached xterm view for the selected one. Operators
 * watch output live and, for an open terminal, type straight into the session —
 * the same box whether they are at their desk or away.
 */
export function TerminalsPanel({ terminals, selectedId, onSelect, onInput }: TerminalsPanelProps) {
  const selected = terminals.find((t) => t.terminalId === selectedId) ?? null;
  const open = terminals.filter((t) => t.status === 'open');
  const closed = terminals.filter((t) => t.status !== 'open');

  return (
    <section className="terminals-panel" aria-label="Live terminals">
      <div className="terminals-panel__list">
        <h3 className="terminals-panel__heading">
          Live terminals {open.length > 0 && <span className="terminals-panel__count">{open.length}</span>}
        </h3>
        {terminals.length === 0 && (
          <p className="terminals-panel__empty">
            No live terminals. Start <code>claude</code> on an enrolled machine and it appears here.
          </p>
        )}
        <ul className="terminals-panel__items">
          {[...open, ...closed].map((t) => (
            <li key={t.terminalId}>
              <button
                type="button"
                className={
                  'terminals-panel__item' +
                  (t.terminalId === selectedId ? ' terminals-panel__item--active' : '') +
                  (t.status !== 'open' ? ' terminals-panel__item--closed' : '')
                }
                onClick={() => onSelect(t.terminalId)}
              >
                <span className="terminals-panel__item-dir">
                  {shortCwd(t.cwd)}
                </span>
                <span className="terminals-panel__item-machine">{t.machineName}</span>
                <span className={'terminals-panel__dot terminals-panel__dot--' + t.status} />
              </button>
            </li>
          ))}
        </ul>
      </div>
      <div className="terminals-panel__screen">
        {selected ? (
          <TerminalView key={selected.terminalId} terminal={selected} onInput={onInput} />
        ) : (
          <div className="terminals-panel__placeholder">
            Select a terminal to attach.
          </div>
        )}
      </div>
    </section>
  );
}

function shortCwd(cwd: string): string {
  if (!cwd) return '(no directory)';
  const parts = cwd.split('/').filter(Boolean);
  return parts.length <= 2 ? cwd : '…/' + parts.slice(-2).join('/');
}
