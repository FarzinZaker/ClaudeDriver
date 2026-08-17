import type { Machine } from '../types';
import type { WsStatus } from '../ws/client';

interface Props {
  wsStatus: WsStatus;
  machines: Machine[];
}

const WS_LABEL: Record<WsStatus, string> = {
  connecting: 'connecting…',
  open: 'connected',
  closed: 'disconnected',
};

/** Live health bar: WS link state + how many machines are currently online. */
export function ConnectionHealth({ wsStatus, machines }: Props) {
  const online = machines.filter((m) => m.connection?.state === 'connected').length;

  return (
    <div className="health" role="status" aria-label="connection health">
      <span className={`health-dot health-dot--${wsStatus}`} aria-hidden="true" />
      <span className="health-item">
        Stream: <strong data-testid="ws-status">{WS_LABEL[wsStatus]}</strong>
      </span>
      <span className="health-item">
        Machines online:{' '}
        <strong data-testid="machines-online">
          {online}/{machines.length}
        </strong>
      </span>
    </div>
  );
}
