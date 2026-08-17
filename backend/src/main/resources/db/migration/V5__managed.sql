-- ClaudeDriver Phase 4 managed-session schema (data-model.md). Reuses Phase 0-3 tables.

CREATE TABLE question (
    id                 UUID PRIMARY KEY,       -- also the questionId on the wire
    machine_id         UUID NOT NULL REFERENCES machine(id),
    session_id         UUID REFERENCES session(id),
    claude_session_id  TEXT NOT NULL,
    text               TEXT NOT NULL,
    status             TEXT NOT NULL DEFAULT 'pending',
    answer             TEXT,
    created_at         TIMESTAMPTZ NOT NULL,
    resolved_at        TIMESTAMPTZ,
    resolved_by        TEXT
);

CREATE TABLE transcript_message (
    id                 BIGSERIAL PRIMARY KEY,  -- monotonic order
    machine_id         UUID NOT NULL REFERENCES machine(id),
    session_id         UUID REFERENCES session(id),
    claude_session_id  TEXT NOT NULL,
    role               TEXT NOT NULL,
    text               TEXT NOT NULL,
    at                 TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_question_machine ON question(machine_id);
CREATE INDEX idx_question_status ON question(status);
CREATE INDEX idx_transcript_session ON transcript_message(session_id);
CREATE INDEX idx_transcript_claude ON transcript_message(claude_session_id);
