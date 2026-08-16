-- ClaudeDriver Phase 1 monitoring schema (data-model.md). Reuses Phase 0 tables.

CREATE TABLE session (
    id                 UUID PRIMARY KEY,
    machine_id         UUID NOT NULL REFERENCES machine(id),
    claude_session_id  TEXT NOT NULL,
    project_path       TEXT,
    state              TEXT NOT NULL DEFAULT 'running',
    last_activity_at   TIMESTAMPTZ NOT NULL,
    process_present    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL,
    UNIQUE (machine_id, claude_session_id)
);

CREATE TABLE activity_event (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES session(id),
    kind        TEXT NOT NULL,
    attention   TEXT NOT NULL,
    summary     TEXT NOT NULL DEFAULT '',
    detail      TEXT NOT NULL DEFAULT '{}',
    at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE alert (
    id                UUID PRIMARY KEY,
    session_id        UUID NOT NULL REFERENCES session(id),
    machine_id        UUID NOT NULL REFERENCES machine(id),
    status            TEXT NOT NULL DEFAULT 'active',
    urgency           TEXT NOT NULL DEFAULT 'normal',
    summary           TEXT NOT NULL DEFAULT '',
    raised_at         TIMESTAMPTZ NOT NULL,
    acknowledged_at   TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    resolved_reason   TEXT
);

CREATE INDEX idx_session_machine ON session(machine_id);
CREATE INDEX idx_activity_session ON activity_event(session_id);
CREATE INDEX idx_alert_session ON alert(session_id);
CREATE INDEX idx_alert_status ON alert(status);
