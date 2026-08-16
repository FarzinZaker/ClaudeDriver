import type { ControlCommandSummary, ControlCommandType, ControlStatus } from '../types';

interface Props {
  commands: ControlCommandSummary[];
}

/** Human labels for the three control actions. */
export const CONTROL_TYPE_LABEL: Record<ControlCommandType, string> = {
  start_run: 'Start run',
  dispatch_task: 'Send task',
  stop_session: 'Stop session',
};

/** Human labels for a command's lifecycle status. */
export const CONTROL_STATUS_LABEL: Record<ControlStatus, string> = {
  pending: 'Pending',
  started: 'Started',
  delivered: 'Delivered',
  done: 'Done',
  stopped: 'Stopped',
  undeliverable: 'Undeliverable',
  error: 'Error',
};

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** A single control-status pill, reused in the strip and the session surface. */
export function ControlStatusBadge({ status }: { status: ControlStatus }) {
  return (
    <span className={`badge badge--control-${status}`} data-testid="control-status">
      {CONTROL_STATUS_LABEL[status]}
    </span>
  );
}

/**
 * A compact activity strip of recent control commands with their live status.
 * Seeded from `GET /commands` and patched by `control_event` in `App`.
 */
export function CommandsStrip({ commands }: Props) {
  const recent = [...commands]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, 10);

  return (
    <section aria-labelledby="commands-heading" className="commands">
      <h2 id="commands-heading">Control activity ({commands.length})</h2>
      {recent.length === 0 ? (
        <p className="empty">No control commands issued yet.</p>
      ) : (
        <ul className="command-list" data-testid="commands-strip">
          {recent.map((c) => (
            <li
              key={c.id}
              className="command-item"
              data-testid="command-item"
              data-command-id={c.id}
              data-status={c.status}
            >
              <ControlStatusBadge status={c.status} />
              <span className="command-item__type">{CONTROL_TYPE_LABEL[c.type]}</span>
              <span className="command-item__machine">{c.machineName}</span>
              <span className="command-item__detail" title={c.instruction ?? c.message ?? ''}>
                {c.instruction ?? c.message ?? '—'}
              </span>
              <time className="command-item__at" dateTime={c.createdAt}>
                {formatTime(c.createdAt)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
