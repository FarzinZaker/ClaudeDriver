import type { SessionDetail, SessionState } from '../types';

interface Props {
  detail?: SessionDetail;
  isLoading: boolean;
  isError: boolean;
  onClose: () => void;
}

export const SESSION_STATE_LABEL: Record<SessionState, string> = {
  running: 'Running',
  waiting_for_operator: 'Waiting for you',
  finished: 'Finished',
  stopped: 'Stopped',
  unknown_stale: 'Unknown / stale',
};

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Modal panel: a session's state, project, last-active, and recent history (FR-016). */
export function SessionDetailModal({ detail, isLoading, isError, onClose }: Props) {
  return (
    <div
      className="modal-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Session detail"
      onClick={onClose}
    >
      <div
        className="card modal"
        data-testid="session-detail"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="modal__head">
          <h2>Session detail</h2>
          <button
            type="button"
            className="btn btn--ghost btn--sm"
            onClick={onClose}
            aria-label="Close session detail"
          >
            Close
          </button>
        </header>

        {isLoading && <p className="empty">Loading session…</p>}
        {isError && !isLoading && (
          <p className="error">Could not load this session.</p>
        )}

        {detail && (
          <>
            <dl className="session-detail__meta">
              <div>
                <dt>Machine</dt>
                <dd>{detail.session.machineName}</dd>
              </div>
              <div>
                <dt>State</dt>
                <dd>
                  <span className={`badge badge--state-${detail.session.state}`}>
                    {SESSION_STATE_LABEL[detail.session.state]}
                  </span>
                </dd>
              </div>
              <div>
                <dt>Project</dt>
                <dd className="mono">{detail.session.projectPath ?? '—'}</dd>
              </div>
              <div>
                <dt>Last active</dt>
                <dd>{formatTime(detail.session.lastActivityAt)}</dd>
              </div>
            </dl>

            <h3 className="session-detail__subhead">
              Recent activity ({detail.recentEvents.length})
            </h3>
            {detail.recentEvents.length === 0 ? (
              <p className="empty">No recent events for this session.</p>
            ) : (
              <ul className="event-list" data-testid="event-list">
                {detail.recentEvents.map((ev, i) => (
                  <li
                    className={`event-item event-item--${ev.attention}`}
                    key={`${ev.at}-${i}`}
                  >
                    <span className="event-item__kind">{ev.kind}</span>
                    <span className="event-item__summary">{ev.summary}</span>
                    <time className="event-item__at" dateTime={ev.at}>
                      {formatTime(ev.at)}
                    </time>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </div>
  );
}
