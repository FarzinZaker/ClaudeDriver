import { useState } from 'react';
import type { AnswerInput } from '../api';
import type {
  ControlCommandSummary,
  QuestionSummary,
  SessionDetail,
  SessionState,
  TranscriptMessage,
} from '../types';
import { CONTROL_TYPE_LABEL, ControlStatusBadge } from './CommandsStrip';

interface Props {
  detail?: SessionDetail;
  isLoading: boolean;
  isError: boolean;
  onClose: () => void;
  /** Dispatch an instruction to this session (`POST /sessions/{id}/dispatch`). */
  onDispatch: (sessionId: string, instruction: string) => void;
  /** Stop this session (`POST /sessions/{id}/stop`). */
  onStop: (sessionId: string) => void;
  dispatchPending?: boolean;
  stopPending?: boolean;
  /** Latest control command targeting this session, for live status. */
  latestCommand?: ControlCommandSummary;
  /** Whether this session runs under management (Agent SDK). */
  managed?: boolean;
  /** The ordered managed-session transcript (Phase 4), or undefined for monitored-only. */
  transcript?: TranscriptMessage[];
  transcriptLoading?: boolean;
  /** Pending free-form questions on this session (managed mode). */
  pendingQuestions?: QuestionSummary[];
  /** Answer / cancel a pending question (`POST /questions/{id}/answer`). */
  onAnswerQuestion?: (id: string, input: AnswerInput) => void;
  answerPendingId?: string | null;
  /** Per-question transient notes (e.g. "already resolved" after a 409). */
  questionNotes?: Record<string, string>;
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

/** The send-task / stop control surface for a session (FR-009). */
function ControlSurface({
  session,
  onDispatch,
  onStop,
  dispatchPending,
  stopPending,
  latestCommand,
}: {
  session: SessionDetail['session'];
  onDispatch: (sessionId: string, instruction: string) => void;
  onStop: (sessionId: string) => void;
  dispatchPending?: boolean;
  stopPending?: boolean;
  latestCommand?: ControlCommandSummary;
}) {
  const [instruction, setInstruction] = useState('');
  const alreadyStopped = session.state === 'stopped' || session.state === 'finished';
  const canSend = !dispatchPending && instruction.trim() !== '';

  const send = () => {
    if (!canSend) return;
    onDispatch(session.id, instruction.trim());
    setInstruction('');
  };

  const stop = () => {
    if (stopPending) return;
    // Errs toward doing nothing when uncertain — confirm before halting a run.
    if (!window.confirm('Stop this session? Its Claude Code run will be ended.')) return;
    onStop(session.id);
  };

  return (
    <section className="control-surface" data-testid="control-surface" aria-label="Session controls">
      <h3 className="session-detail__subhead">Control</h3>
      <label className="field">
        <span>Send a task or message</span>
        <textarea
          data-testid="dispatch-input"
          value={instruction}
          rows={2}
          placeholder="Type an instruction for this session…"
          onChange={(e) => setInstruction(e.target.value)}
        />
      </label>
      <div className="control-surface__actions">
        <button
          type="button"
          className="btn btn--sm btn--primary control-surface__send"
          data-testid="dispatch-send"
          disabled={!canSend}
          onClick={send}
        >
          {dispatchPending ? 'Sending…' : 'Send task'}
        </button>
        <button
          type="button"
          className="btn btn--sm btn--deny"
          data-testid="stop-session"
          disabled={stopPending || alreadyStopped}
          onClick={stop}
        >
          {stopPending ? 'Stopping…' : 'Stop session'}
        </button>
      </div>
      {latestCommand && (
        <p className="control-surface__latest" data-testid="control-latest">
          <span className="control-surface__latest-type">
            {CONTROL_TYPE_LABEL[latestCommand.type]}
          </span>
          <ControlStatusBadge status={latestCommand.status} />
          {latestCommand.message && (
            <span className="control-surface__latest-msg">{latestCommand.message}</span>
          )}
        </p>
      )}
    </section>
  );
}

/** Compact inline answer affordance for a pending question on a managed session. */
function InlineQuestion({
  question,
  onAnswer,
  busy,
  note,
}: {
  question: QuestionSummary;
  onAnswer: (id: string, input: AnswerInput) => void;
  busy: boolean;
  note?: string;
}) {
  const [answer, setAnswer] = useState('');
  const canSend = !busy && answer.trim() !== '';
  return (
    <div
      className="inline-question"
      data-testid="inline-question"
      data-question-id={question.id}
    >
      <span className="badge badge--question-pending">waiting on you</span>
      <p className="inline-question__text">{question.text}</p>
      <textarea
        data-testid="inline-question-input"
        className="inline-question__input"
        value={answer}
        rows={2}
        placeholder="Type an answer for this session…"
        onChange={(e) => setAnswer(e.target.value)}
      />
      {note && (
        <span className="question-card__note" data-testid="inline-question-note">
          {note}
        </span>
      )}
      <div className="inline-question__actions">
        <button
          type="button"
          className="btn btn--sm btn--primary"
          data-testid="inline-question-send"
          disabled={!canSend}
          onClick={() => canSend && onAnswer(question.id, { answer: answer.trim() })}
        >
          {busy ? 'Sending…' : 'Send answer'}
        </button>
        <button
          type="button"
          className="btn btn--sm btn--deny"
          data-testid="inline-question-cancel"
          disabled={busy}
          onClick={() => onAnswer(question.id, { cancel: true })}
        >
          Cancel
        </button>
      </div>
    </div>
  );
}

/** The ordered transcript of a managed session (FR-008). */
function TranscriptView({
  transcript,
  isLoading,
}: {
  transcript?: TranscriptMessage[];
  isLoading?: boolean;
}) {
  return (
    <section className="transcript" data-testid="transcript" aria-label="Transcript">
      <h3 className="session-detail__subhead">
        Transcript ({transcript?.length ?? 0})
      </h3>
      {isLoading && !transcript ? (
        <p className="empty">Loading transcript…</p>
      ) : !transcript || transcript.length === 0 ? (
        <p className="empty">No transcript messages yet.</p>
      ) : (
        <ol className="transcript-list" data-testid="transcript-list">
          {transcript.map((m, i) => (
            <li
              className={`transcript-msg transcript-msg--${m.role}`}
              data-testid="transcript-msg"
              data-role={m.role}
              key={`${m.at}-${i}`}
            >
              <span className="transcript-msg__role">{m.role}</span>
              <span className="transcript-msg__text">{m.text}</span>
              <time className="transcript-msg__at" dateTime={m.at}>
                {formatTime(m.at)}
              </time>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** Modal panel: a session's state, project, last-active, and recent history (FR-016). */
export function SessionDetailModal({
  detail,
  isLoading,
  isError,
  onClose,
  onDispatch,
  onStop,
  dispatchPending,
  stopPending,
  latestCommand,
  managed,
  transcript,
  transcriptLoading,
  pendingQuestions,
  onAnswerQuestion,
  answerPendingId,
  questionNotes,
}: Props) {
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
          <h2>
            Session detail
            {managed && (
              <span className="badge badge--managed" data-testid="managed-badge">
                managed
              </span>
            )}
          </h2>
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

            {managed &&
              onAnswerQuestion &&
              pendingQuestions &&
              pendingQuestions.length > 0 && (
                <section
                  className="session-detail__questions"
                  data-testid="session-questions"
                  aria-label="Pending questions"
                >
                  <h3 className="session-detail__subhead">
                    Questions waiting on you ({pendingQuestions.length})
                  </h3>
                  {pendingQuestions.map((q) => (
                    <InlineQuestion
                      key={q.id}
                      question={q}
                      onAnswer={onAnswerQuestion}
                      busy={answerPendingId === q.id}
                      note={questionNotes?.[q.id]}
                    />
                  ))}
                </section>
              )}

            <ControlSurface
              session={detail.session}
              onDispatch={onDispatch}
              onStop={onStop}
              dispatchPending={dispatchPending}
              stopPending={stopPending}
              latestCommand={latestCommand}
            />

            {managed && (
              <TranscriptView
                transcript={transcript}
                isLoading={transcriptLoading}
              />
            )}

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
