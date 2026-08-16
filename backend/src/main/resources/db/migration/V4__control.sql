-- ClaudeDriver Phase 3 remote-control schema (data-model.md). Reuses Phase 0-2 tables.

CREATE TABLE control_command (
    id                 UUID PRIMARY KEY,       -- also the commandId on the wire
    machine_id         UUID NOT NULL REFERENCES machine(id),
    session_id         UUID REFERENCES session(id),
    claude_session_id  TEXT,
    type               TEXT NOT NULL,          -- start_run | dispatch_task | stop_session
    project_path       TEXT,
    instruction        TEXT,
    status             TEXT NOT NULL DEFAULT 'pending',
    result_message     TEXT,
    issued_by          TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_control_machine ON control_command(machine_id);
CREATE INDEX idx_control_status ON control_command(status);
