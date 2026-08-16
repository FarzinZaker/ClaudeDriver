import type { Machine, SampleEventRecord, ServerInfo } from '../types';
import type { WsStatus } from '../ws/client';
import { ConnectionHealth } from './ConnectionHealth';

interface Props {
  server: ServerInfo;
  machines: Machine[];
  recentSampleEvents: SampleEventRecord[];
  wsStatus: WsStatus;
  onLogout?: () => void;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function MachineCard({ machine }: { machine: Machine }) {
  const { connection } = machine;
  return (
    <li className="card machine-card" data-testid="machine-card">
      <div className="machine-card__head">
        <span className="machine-card__name">{machine.name}</span>
        <span className={`badge badge--${machine.status}`}>{machine.status}</span>
      </div>
      <dl className="machine-card__meta">
        <div>
          <dt>OS</dt>
          <dd>{machine.os}</dd>
        </div>
        <div>
          <dt>Connection</dt>
          <dd>
            <span className={`conn conn--${connection.state}`}>{connection.state}</span>
          </dd>
        </div>
        <div>
          <dt>Since</dt>
          <dd>{connection.since ? formatTime(connection.since) : '—'}</dd>
        </div>
        <div>
          <dt>Protocol</dt>
          <dd>{connection.protocolVersion ?? '—'}</dd>
        </div>
      </dl>
    </li>
  );
}

/**
 * The operator status page: server identity, machine fleet, and the live
 * sample-event inbox. Data is seeded from `GET /status` and patched live from
 * the operator WS by `App`.
 */
export function StatusView({
  server,
  machines,
  recentSampleEvents,
  wsStatus,
  onLogout,
}: Props) {
  return (
    <div className="status-view">
      <header className="app-header">
        <div>
          <h1>ClaudeDriver — Operator Status</h1>
          <p className="subtitle">
            Server <strong>v{server.version}</strong> · {formatTime(server.time)}
          </p>
        </div>
        {onLogout && (
          <button type="button" className="btn btn--ghost" onClick={onLogout}>
            Sign out
          </button>
        )}
      </header>

      <ConnectionHealth wsStatus={wsStatus} machines={machines} />

      <section aria-labelledby="machines-heading">
        <h2 id="machines-heading">Machines ({machines.length})</h2>
        {machines.length === 0 ? (
          <p className="empty">No machines enrolled yet.</p>
        ) : (
          <ul className="machine-grid">
            {machines.map((m) => (
              <MachineCard key={m.id} machine={m} />
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="inbox-heading">
        <h2 id="inbox-heading">Sample event inbox ({recentSampleEvents.length})</h2>
        {recentSampleEvents.length === 0 ? (
          <p className="empty">No sample events received.</p>
        ) : (
          <ul className="inbox" data-testid="inbox">
            {recentSampleEvents.map((ev, i) => (
              <li
                className="inbox__item"
                data-testid="inbox-item"
                key={`${ev.machineId}-${ev.at}-${i}`}
              >
                <time className="inbox__at" dateTime={ev.at}>
                  {formatTime(ev.at)}
                </time>
                <span className="inbox__machine">{ev.machineId}</span>
                <span className="inbox__message">{ev.message}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
