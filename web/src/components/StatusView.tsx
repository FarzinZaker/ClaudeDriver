import type { AnswerInput } from '../api';
import type {
  AlertSummary,
  ApprovalSummary,
  ControlCommandSummary,
  Machine,
  QuestionSummary,
  SampleEventRecord,
  SearchResult,
  ServerInfo,
  SessionState,
  SessionSummary,
} from '../types';
import type { WsStatus } from '../ws/client';
import { AlertInbox } from './AlertInbox';
import { ApprovalsPanel } from './ApprovalsPanel';
import { CommandsStrip } from './CommandsStrip';
import { ConnectionHealth } from './ConnectionHealth';
import { QuestionsInbox } from './QuestionsInbox';
import { SearchBox } from './SearchBox';
import { SESSION_STATE_LABEL } from './SessionDetail';
import { StartRunPanel } from './StartRunPanel';
import { EnrollMachinePanel } from './EnrollMachinePanel';

interface Props {
  server: ServerInfo;
  machines: Machine[];
  sessions: SessionSummary[];
  alerts: AlertSummary[];
  approvals: ApprovalSummary[];
  commands: ControlCommandSummary[];
  recentSampleEvents: SampleEventRecord[];
  wsStatus: WsStatus;
  onLogout?: () => void;
  onAckAlert: (id: string) => void;
  ackPendingId?: string | null;
  onDecideApproval: (id: string, decision: 'approve' | 'deny') => void;
  decidePendingId?: string | null;
  approvalNotes?: Record<string, string>;
  onOpenSession: (sessionId: string) => void;
  onStartRun: (machineId: string, projectPath: string, instruction: string) => void;
  startRunPending?: boolean;
  onStartManaged: (machineId: string, projectPath: string, instruction: string) => void;
  startManagedPending?: boolean;
  /** Refresh the machine list after a successful enrollment. */
  onEnrolled: () => void;
  onRevokeMachine: (id: string, name: string) => void;
  revokePendingId?: string | null;
  // Phase 4 — questions inbox
  questions: QuestionSummary[];
  onAnswerQuestion: (id: string, input: AnswerInput) => void;
  answerPendingId?: string | null;
  questionNotes?: Record<string, string>;
  // Phase 4 — cross-session search
  onSearch: (term: string) => void;
  searchResults?: SearchResult[];
  searchTerm?: string;
  searchPending?: boolean;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Short relative-ish label for a session's last activity. */
function projectLabel(path: string | null): string {
  if (!path) return 'unknown project';
  const parts = path.split('/').filter(Boolean);
  return parts.length ? parts[parts.length - 1] : path;
}

function SessionRow({
  session,
  onOpen,
}: {
  session: SessionSummary;
  onOpen: (id: string) => void;
}) {
  const state: SessionState = session.state;
  return (
    <li className="session-row" data-testid="session-row" data-state={state}>
      <button
        type="button"
        className="session-row__btn"
        onClick={() => onOpen(session.id)}
        title="Open session detail"
      >
        <span className="session-row__main">
          <span className={`badge badge--state-${state}`} data-testid="session-badge">
            {SESSION_STATE_LABEL[state]}
          </span>
          <span className="session-row__project mono" title={session.projectPath ?? ''}>
            {projectLabel(session.projectPath)}
          </span>
        </span>
        <span className="session-row__meta">
          {!session.processPresent && (
            <span className="session-row__flag" title="No live process backing this session">
              no process
            </span>
          )}
          <time className="session-row__at" dateTime={session.lastActivityAt}>
            {formatTime(session.lastActivityAt)}
          </time>
        </span>
      </button>
    </li>
  );
}

function MachineCard({
  machine,
  sessions,
  onOpenSession,
  onRevoke,
  revokePending,
}: {
  machine: Machine;
  sessions: SessionSummary[];
  onOpenSession: (id: string) => void;
  onRevoke: (id: string, name: string) => void;
  revokePending: boolean;
}) {
  const { connection } = machine;
  const offline = connection.state !== 'connected';
  const revoked = machine.status === 'revoked';
  return (
    <li className="card machine-card" data-testid="machine-card">
      <div className="machine-card__head">
        <span className="machine-card__name">{machine.name}</span>
        <span className={`badge badge--${machine.status}`}>{machine.status}</span>
      </div>
      <div className="machine-card__sub">
        <span className={`conn conn--${connection.state}`} data-testid="machine-conn">
          {offline ? 'offline' : 'online'}
        </span>
        <span className="machine-card__os">{machine.os}</span>
        <span className="machine-card__since">
          {connection.since ? formatTime(connection.since) : 'never seen'}
        </span>
        {connection.protocolVersion && (
          <span className="machine-card__proto">v{connection.protocolVersion}</span>
        )}
      </div>

      <div className="machine-card__sessions">
        {sessions.length === 0 ? (
          <p className="empty empty--sm" data-testid="no-sessions">
            No Claude Code sessions.
          </p>
        ) : (
          <ul className="session-list">
            {sessions.map((s) => (
              <SessionRow key={s.id} session={s} onOpen={onOpenSession} />
            ))}
          </ul>
        )}
        {offline && sessions.length > 0 && (
          <p className="machine-card__stale" data-testid="machine-stale">
            Machine offline — sessions show last-known state.
          </p>
        )}
      </div>

      {!revoked && (
        <div className="machine-card__actions">
          <button
            type="button"
            className="btn btn--sm btn--danger"
            disabled={revokePending}
            onClick={() => {
              if (
                window.confirm(
                  `De-register "${machine.name}"? Its agent certificate is revoked and it can no longer connect.`,
                )
              ) {
                onRevoke(machine.id, machine.name);
              }
            }}
          >
            {revokePending ? 'Revoking…' : 'De-register'}
          </button>
        </div>
      )}
    </li>
  );
}

/**
 * The operator dashboard: alert inbox, the machine fleet with live sessions,
 * and the Phase 0 sample-event inbox. Data is seeded from REST and patched live
 * from the operator WS by `App`.
 */
export function StatusView({
  server,
  machines,
  sessions,
  alerts,
  approvals,
  commands,
  recentSampleEvents,
  wsStatus,
  onLogout,
  onAckAlert,
  ackPendingId,
  onDecideApproval,
  decidePendingId,
  approvalNotes,
  onOpenSession,
  onStartRun,
  startRunPending,
  onStartManaged,
  startManagedPending,
  onEnrolled,
  onRevokeMachine,
  revokePendingId,
  questions,
  onAnswerQuestion,
  answerPendingId,
  questionNotes,
  onSearch,
  searchResults,
  searchTerm,
  searchPending,
}: Props) {
  const sessionsByMachine = new Map<string, SessionSummary[]>();
  for (const s of sessions) {
    const list = sessionsByMachine.get(s.machineId) ?? [];
    list.push(s);
    sessionsByMachine.set(s.machineId, list);
  }

  return (
    <div className="status-view">
      <header className="app-header">
        <div>
          <h1>ClaudeDriver — Operator Dashboard</h1>
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

      <SearchBox
        onSearch={onSearch}
        results={searchResults}
        activeTerm={searchTerm}
        isSearching={searchPending}
        onOpenSession={onOpenSession}
      />

      <StartRunPanel
        machines={machines}
        onStartRun={onStartRun}
        onStartManaged={onStartManaged}
        startRunPending={startRunPending}
        startManagedPending={startManagedPending}
      />

      <QuestionsInbox
        questions={questions}
        onAnswer={onAnswerQuestion}
        answerPendingId={answerPendingId}
        notes={questionNotes}
        onOpenSession={onOpenSession}
      />

      <ApprovalsPanel
        approvals={approvals}
        onDecide={onDecideApproval}
        decidePendingId={decidePendingId}
        notes={approvalNotes}
      />

      <AlertInbox
        alerts={alerts}
        onAck={onAckAlert}
        onOpenSession={onOpenSession}
        ackPendingId={ackPendingId}
      />

      <EnrollMachinePanel onEnrolled={onEnrolled} />

      <section aria-labelledby="machines-heading">
        <h2 id="machines-heading">
          Machines ({machines.length}) · Sessions ({sessions.length})
        </h2>
        {machines.length === 0 ? (
          <p className="empty">No machines enrolled yet.</p>
        ) : (
          <ul className="machine-grid">
            {machines.map((m) => (
              <MachineCard
                key={m.id}
                machine={m}
                sessions={sessionsByMachine.get(m.id) ?? []}
                onOpenSession={onOpenSession}
                onRevoke={onRevokeMachine}
                revokePending={revokePendingId === m.id}
              />
            ))}
          </ul>
        )}
      </section>

      <CommandsStrip commands={commands} />

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
