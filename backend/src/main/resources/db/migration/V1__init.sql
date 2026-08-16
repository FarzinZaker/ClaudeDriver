-- ClaudeDriver Phase 0 schema (data-model.md). Owned by Flyway; Exposed maps to it.

CREATE TABLE operator (
    id          UUID PRIMARY KEY,
    handle      TEXT NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    status      TEXT NOT NULL DEFAULT 'active'
);

CREATE TABLE webauthn_credential (
    id            UUID PRIMARY KEY,
    operator_id   UUID NOT NULL REFERENCES operator(id),
    credential_id BYTEA NOT NULL UNIQUE,
    public_key    BYTEA NOT NULL,
    sign_count    BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE machine (
    id           UUID PRIMARY KEY,
    name         TEXT NOT NULL,
    os           TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'pending',
    enrolled_at  TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

CREATE TABLE enrollment_request (
    id          UUID PRIMARY KEY,
    machine_id  UUID NOT NULL REFERENCES machine(id),
    code_hash   TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'awaiting_approval',
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE device_certificate (
    id           UUID PRIMARY KEY,
    machine_id   UUID NOT NULL REFERENCES machine(id),
    serial       TEXT NOT NULL UNIQUE,
    fingerprint  TEXT NOT NULL UNIQUE,
    not_before   TIMESTAMPTZ NOT NULL,
    not_after    TIMESTAMPTZ NOT NULL,
    status       TEXT NOT NULL DEFAULT 'active'
);

CREATE TABLE agent_connection (
    id                     UUID PRIMARY KEY,
    machine_id             UUID NOT NULL REFERENCES machine(id),
    device_certificate_id  UUID NOT NULL REFERENCES device_certificate(id),
    protocol_version       TEXT NOT NULL,
    connected_at           TIMESTAMPTZ NOT NULL,
    disconnected_at        TIMESTAMPTZ,
    last_seq               BIGINT NOT NULL DEFAULT 0,
    state                  TEXT NOT NULL DEFAULT 'connected'
);

-- Append-only, hash-chained audit trail (Principle VI). Enforce insert-only via trigger.
CREATE TABLE audit_event (
    id         BIGSERIAL PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL,
    actor      TEXT NOT NULL,
    action     TEXT NOT NULL,
    subject    TEXT NOT NULL,
    detail     TEXT NOT NULL DEFAULT '{}',
    prev_hash  TEXT NOT NULL,
    hash       TEXT NOT NULL
);

CREATE OR REPLACE FUNCTION audit_event_no_mutate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_no_mutate();

CREATE INDEX idx_machine_status ON machine(status);
CREATE INDEX idx_device_cert_fingerprint ON device_certificate(fingerprint);
CREATE INDEX idx_agent_connection_machine ON agent_connection(machine_id);
