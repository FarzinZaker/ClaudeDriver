import { useState } from 'react';
import type { AnswerInput } from '../api';
import type { QuestionStatus, QuestionSummary } from '../types';

interface Props {
  questions: QuestionSummary[];
  /** Answer or cancel a pending question (POST /questions/{id}/answer). */
  onAnswer: (id: string, input: AnswerInput) => void;
  /** Id currently mid-answer, to disable its controls. */
  answerPendingId?: string | null;
  /** Per-question transient notes (e.g. "already resolved" after a 409). */
  notes?: Record<string, string>;
  /** Open the session a question belongs to. */
  onOpenSession?: (sessionId: string) => void;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/** Short project/session label from a Claude session id. */
function sessionLabel(id: string): string {
  return id.length > 12 ? `${id.slice(0, 12)}…` : id;
}

const RESOLVED_LABEL: Record<Exclude<QuestionStatus, 'pending'>, string> = {
  answered: 'answered',
  cancelled: 'cancelled',
  unanswered: 'unanswered',
};

/** A single pending question with an answer textarea + Send / Cancel. */
function PendingQuestion({
  question,
  onAnswer,
  busy,
  note,
  onOpenSession,
}: {
  question: QuestionSummary;
  onAnswer: (id: string, input: AnswerInput) => void;
  busy: boolean;
  note?: string;
  onOpenSession?: (sessionId: string) => void;
}) {
  const [answer, setAnswer] = useState('');
  const canSend = !busy && answer.trim() !== '';

  const send = () => {
    if (!canSend) return;
    onAnswer(question.id, { answer: answer.trim() });
  };

  return (
    <li
      className="card question-card question-card--pending"
      data-testid="question-item"
      data-question-id={question.id}
      data-status={question.status}
    >
      <div className="question-card__head">
        <span className="badge badge--question-pending">waiting on you</span>
        <span className="question-card__machine">{question.machineName}</span>
        {onOpenSession ? (
          <button
            type="button"
            className="question-card__session mono"
            title={`Open session ${question.claudeSessionId}`}
            onClick={() => onOpenSession(question.claudeSessionId)}
          >
            {sessionLabel(question.claudeSessionId)}
          </button>
        ) : (
          <span className="question-card__session mono" title={question.claudeSessionId}>
            {sessionLabel(question.claudeSessionId)}
          </span>
        )}
        <time className="question-card__at" dateTime={question.createdAt}>
          {formatTime(question.createdAt)}
        </time>
      </div>
      <p className="question-card__text">{question.text}</p>
      <label className="field question-card__field">
        <span>Your answer</span>
        <textarea
          data-testid="question-answer-input"
          value={answer}
          rows={2}
          placeholder="Type an answer for this session…"
          onChange={(e) => setAnswer(e.target.value)}
        />
      </label>
      {note && (
        <span className="question-card__note" data-testid="question-note">
          {note}
        </span>
      )}
      <div className="question-card__actions">
        <button
          type="button"
          className="btn btn--sm btn--primary question-card__send"
          data-testid="question-send"
          disabled={!canSend}
          onClick={send}
        >
          {busy ? 'Sending…' : 'Send answer'}
        </button>
        <button
          type="button"
          className="btn btn--sm btn--deny"
          data-testid="question-cancel"
          disabled={busy}
          onClick={() => onAnswer(question.id, { cancel: true })}
        >
          Cancel
        </button>
      </div>
    </li>
  );
}

/**
 * The free-form questions inbox. A pending question BLOCKS a real managed
 * session that is waiting on the operator, so pending items are rendered
 * prominently and first (oldest → longest-waiting first). Recently resolved
 * questions are shown dimmed below for context.
 */
export function QuestionsInbox({
  questions,
  onAnswer,
  answerPendingId,
  notes,
  onOpenSession,
}: Props) {
  const pending = questions
    .filter((q) => q.status === 'pending')
    // Oldest first — the longest-waiting session is the most urgent.
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const resolved = questions
    .filter((q) => q.status !== 'pending')
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, 5);

  return (
    <section aria-labelledby="questions-heading" className="questions">
      <h2 id="questions-heading">Questions ({pending.length})</h2>
      {pending.length === 0 ? (
        <p className="empty">
          No pending questions. No managed session is waiting on you.
        </p>
      ) : (
        <ul className="question-list" data-testid="question-list">
          {pending.map((q) => (
            <PendingQuestion
              key={q.id}
              question={q}
              onAnswer={onAnswer}
              busy={answerPendingId === q.id}
              note={notes?.[q.id]}
              onOpenSession={onOpenSession}
            />
          ))}
        </ul>
      )}

      {resolved.length > 0 && (
        <ul
          className="question-list question-list--resolved"
          data-testid="question-resolved"
        >
          {resolved.map((q) => (
            <li
              key={q.id}
              className="question-card question-card--resolved"
              data-testid="question-resolved-item"
              data-question-id={q.id}
              data-status={q.status}
            >
              <span className={`badge badge--question-${q.status}`}>
                {RESOLVED_LABEL[q.status as Exclude<QuestionStatus, 'pending'>]}
              </span>
              <span className="question-card__machine">{q.machineName}</span>
              <span className="question-card__text question-card__text--sm">
                {q.answer ? `${q.text} → ${q.answer}` : q.text}
              </span>
              <time className="question-card__at" dateTime={q.createdAt}>
                {formatTime(q.createdAt)}
              </time>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
