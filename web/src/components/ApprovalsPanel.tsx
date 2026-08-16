import type { ApprovalStatus, ApprovalSummary } from '../types';

interface Props {
  approvals: ApprovalSummary[];
  /** Decide a pending approval (POST /approvals/{id}/decide). */
  onDecide: (id: string, decision: 'approve' | 'deny') => void;
  /** Id currently mid-decision, to disable its buttons. */
  decidePendingId?: string | null;
  /** Per-approval transient notes (e.g. "already resolved" after a 409). */
  notes?: Record<string, string>;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Short project/session label from a Claude session id. */
function sessionLabel(id: string): string {
  return id.length > 12 ? `${id.slice(0, 12)}…` : id;
}

const RESOLVED_LABEL: Record<Exclude<ApprovalStatus, 'pending'>, string> = {
  approved: 'approved',
  denied: 'denied',
  moot: 'no longer applicable',
};

/**
 * The remote-approvals panel. Pending requests block a real waiting Claude Code
 * instance, so they are rendered prominently and first; recently resolved
 * requests are shown dimmed below for context.
 */
export function ApprovalsPanel({
  approvals,
  onDecide,
  decidePendingId,
  notes,
}: Props) {
  const pending = approvals
    .filter((a) => a.status === 'pending')
    // Oldest first — the longest-waiting instance is the most urgent.
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const resolved = approvals
    .filter((a) => a.status !== 'pending')
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, 5);

  return (
    <section aria-labelledby="approvals-heading" className="approvals">
      <h2 id="approvals-heading">Approvals ({pending.length})</h2>
      {pending.length === 0 ? (
        <p className="empty">No pending approvals. No instance is waiting on you.</p>
      ) : (
        <ul className="approval-list" data-testid="approval-list">
          {pending.map((a) => {
            const busy = decidePendingId === a.id;
            const note = notes?.[a.id];
            return (
              <li
                key={a.id}
                className="card approval-card approval-card--pending"
                data-testid="approval-item"
                data-approval-id={a.id}
                data-status={a.status}
              >
                <div className="approval-card__body">
                  <span className="approval-card__head">
                    <span className="badge badge--approval-pending">pending</span>
                    <span className="approval-card__machine">{a.machineName}</span>
                    <span className="approval-card__tool mono">{a.tool}</span>
                    <span
                      className="approval-card__session mono"
                      title={a.claudeSessionId}
                    >
                      {sessionLabel(a.claudeSessionId)}
                    </span>
                  </span>
                  <span className="approval-card__summary">{a.summary}</span>
                  <time className="approval-card__at" dateTime={a.createdAt}>
                    {formatTime(a.createdAt)}
                  </time>
                  {note && (
                    <span className="approval-card__note" data-testid="approval-note">
                      {note}
                    </span>
                  )}
                </div>
                <div className="approval-card__actions">
                  <button
                    type="button"
                    className="btn btn--sm btn--approve"
                    data-testid="approval-approve"
                    disabled={busy}
                    onClick={() => onDecide(a.id, 'approve')}
                  >
                    {busy ? 'Deciding…' : 'Approve'}
                  </button>
                  <button
                    type="button"
                    className="btn btn--sm btn--deny"
                    data-testid="approval-deny"
                    disabled={busy}
                    onClick={() => onDecide(a.id, 'deny')}
                  >
                    Deny
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {resolved.length > 0 && (
        <ul className="approval-list approval-list--resolved" data-testid="approval-resolved">
          {resolved.map((a) => (
            <li
              key={a.id}
              className="approval-card approval-card--resolved"
              data-testid="approval-resolved-item"
              data-approval-id={a.id}
              data-status={a.status}
            >
              <span className={`badge badge--approval-${a.status}`}>
                {RESOLVED_LABEL[a.status as Exclude<ApprovalStatus, 'pending'>]}
              </span>
              <span className="approval-card__machine">{a.machineName}</span>
              <span className="approval-card__summary">{a.summary}</span>
              <time className="approval-card__at" dateTime={a.createdAt}>
                {formatTime(a.createdAt)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
