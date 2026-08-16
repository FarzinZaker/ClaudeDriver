import type { AlertSummary, AlertUrgency } from '../types';

interface Props {
  alerts: AlertSummary[];
  /** Acknowledge an active alert (POST /alerts/{id}/ack). */
  onAck: (id: string) => void;
  /** Open the session an alert belongs to. */
  onOpenSession: (sessionId: string) => void;
  /** Id currently mid-acknowledge, to disable its button. */
  ackPendingId?: string | null;
}

const URGENCY_RANK: Record<AlertUrgency, number> = { high: 0, normal: 1, low: 2 };

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/**
 * Active alerts, sorted by urgency then recency. Acknowledged alerts remain
 * (dimmed) until resolved; resolved alerts are dropped from the inbox upstream.
 */
function sortAlerts(alerts: AlertSummary[]): AlertSummary[] {
  return [...alerts]
    .filter((a) => a.status !== 'resolved')
    .sort((a, b) => {
      // Active before acknowledged.
      const actA = a.status === 'active' ? 0 : 1;
      const actB = b.status === 'active' ? 0 : 1;
      if (actA !== actB) return actA - actB;
      const urg = URGENCY_RANK[a.urgency] - URGENCY_RANK[b.urgency];
      if (urg !== 0) return urg;
      // Recency: newest first.
      return b.raisedAt.localeCompare(a.raisedAt);
    });
}

/** The operator alert inbox (FR-015). */
export function AlertInbox({ alerts, onAck, onOpenSession, ackPendingId }: Props) {
  const visible = sortAlerts(alerts);

  return (
    <section aria-labelledby="alerts-heading">
      <h2 id="alerts-heading">Alert inbox ({visible.length})</h2>
      {visible.length === 0 ? (
        <p className="empty">No active alerts. Nothing needs your attention.</p>
      ) : (
        <ul className="alert-list" data-testid="alert-list">
          {visible.map((alert) => (
            <li
              key={alert.id}
              className={`card alert-card alert-card--${alert.urgency} alert-card--${alert.status}`}
              data-testid="alert-item"
              data-alert-id={alert.id}
              data-status={alert.status}
            >
              <button
                type="button"
                className="alert-card__open"
                onClick={() => onOpenSession(alert.sessionId)}
                title="Open the related session"
              >
                <span className="alert-card__head">
                  <span className={`badge badge--urgency-${alert.urgency}`}>
                    {alert.urgency}
                  </span>
                  <span className="alert-card__machine">{alert.machineName}</span>
                  {alert.status === 'acknowledged' && (
                    <span className="alert-card__ack-tag">acknowledged</span>
                  )}
                </span>
                <span className="alert-card__summary">{alert.summary}</span>
                <time className="alert-card__at" dateTime={alert.raisedAt}>
                  {formatTime(alert.raisedAt)}
                </time>
              </button>
              {alert.status === 'active' && (
                <button
                  type="button"
                  className="btn btn--sm"
                  data-testid="alert-ack"
                  disabled={ackPendingId === alert.id}
                  onClick={() => onAck(alert.id)}
                >
                  {ackPendingId === alert.id ? 'Acknowledging…' : 'Acknowledge'}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
