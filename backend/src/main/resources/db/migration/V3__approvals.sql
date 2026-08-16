-- ClaudeDriver Phase 2 approvals + push schema (data-model.md). Reuses Phase 0/1 tables.

CREATE TABLE approval_request (
    id                 UUID PRIMARY KEY,        -- also the requestId on the wire
    machine_id         UUID NOT NULL REFERENCES machine(id),
    session_id         UUID REFERENCES session(id),
    claude_session_id  TEXT NOT NULL,
    tool               TEXT NOT NULL,
    summary            TEXT NOT NULL DEFAULT '',
    detail             TEXT NOT NULL DEFAULT '{}',
    status             TEXT NOT NULL DEFAULT 'pending',
    created_at         TIMESTAMPTZ NOT NULL,
    decided_at         TIMESTAMPTZ,
    decided_by         TEXT,
    surface            TEXT,
    decision_reason    TEXT
);

CREATE TABLE push_device (
    id            UUID PRIMARY KEY,
    operator_id   UUID NOT NULL REFERENCES operator(id),
    token         TEXT NOT NULL UNIQUE,
    platform      TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_approval_machine ON approval_request(machine_id);
CREATE INDEX idx_approval_status ON approval_request(status);
CREATE INDEX idx_push_operator ON push_device(operator_id);
