# Phase 0 Data Model

Persistent entities for the walking skeleton. Owned by Flyway migrations; accessed via Exposed.
Only what Phase 0 needs — monitoring/alert/approval entities arrive in later phases. Types are
conceptual (map to Postgres in migrations).

## Operator

The single authorized human user.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `handle` | text, unique | login handle / display name |
| `created_at` | timestamptz | |
| `status` | enum: `active`, `disabled` | disabled → all sessions refused |

**Relationships**: 1—N `WebAuthnCredential`.
**Validation**: exactly one operator may be bootstrapped via `OPERATOR_BOOTSTRAP_CODE` in Phase 0.

## WebAuthnCredential

A registered passkey for an operator (self-hosted WebAuthn).

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `operator_id` | UUID (FK → Operator) | |
| `credential_id` | bytea, unique | WebAuthn credential id |
| `public_key` | bytea | COSE public key |
| `sign_count` | bigint | replay/cloning detection; must be non-decreasing |
| `created_at` | timestamptz | |

**Validation**: `sign_count` regression ⇒ authentication refused (possible cloned authenticator).

## Machine

An enrolled developer computer.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | stable machine identity |
| `name` | text | operator-assigned label |
| `os` | enum: `windows`, `macos` | |
| `status` | enum: `pending`, `enrolled`, `revoked` | lifecycle |
| `enrolled_at` | timestamptz, null | set when enrolled |
| `revoked_at` | timestamptz, null | set when revoked |

**State transitions**: `pending → enrolled` (operator approves) · `enrolled → revoked` (operator
revokes). No other transitions; a revoked machine cannot return to enrolled (a new enrollment
creates/refreshes identity).
**Relationships**: 1—N `DeviceCertificate` (current + historical), 1—N `AgentConnection`.

## EnrollmentRequest

An operator-approved admission of a machine; single-use.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `machine_id` | UUID (FK → Machine) | the machine being admitted |
| `code_hash` | text | hash of the short-lived enrollment code (never stored plaintext) |
| `status` | enum: `awaiting_approval`, `approved`, `consumed`, `expired` | |
| `expires_at` | timestamptz | short TTL |
| `created_at` | timestamptz | |

**Validation**: a code is usable only while `approved` and before `expires_at`; consuming it issues
exactly one `DeviceCertificate` and flips to `consumed`. Expiry/duplicate use ⇒ refused.

## DeviceCertificate

The per-device mTLS client certificate bound to a machine.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `machine_id` | UUID (FK → Machine) | |
| `serial` | text, unique | certificate serial |
| `fingerprint` | text, unique | SHA-256 of the cert (trust-store lookup key) |
| `not_before` | timestamptz | |
| `not_after` | timestamptz | short-lived; renewed before expiry |
| `status` | enum: `active`, `revoked`, `expired` | |

**Validation**: a connection is accepted only if the presented cert is `active`, unexpired, and its
`machine` is `enrolled`. Revoked/expired/forged ⇒ refused (fail-safe).

## AgentConnection

A live authenticated agent↔backend session (observable status).

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `machine_id` | UUID (FK → Machine) | |
| `device_certificate_id` | UUID (FK → DeviceCertificate) | the identity used |
| `protocol_version` | text | negotiated at connect |
| `connected_at` | timestamptz | |
| `disconnected_at` | timestamptz, null | |
| `last_seq` | bigint | last event sequence acknowledged (resume/replay) |
| `state` | enum: `connected`, `disconnected` | |

**Relationships**: N—1 `Machine`, N—1 `DeviceCertificate`.

## AuditEvent

Append-only, hash-chained record of consequential actions.

| Field | Type | Notes |
|---|---|---|
| `id` | bigserial (PK) | monotonic |
| `at` | timestamptz | |
| `actor` | text | operator handle, machine id, or `system` |
| `action` | enum: `enrollment_approved`, `enrollment_consumed`, `machine_revoked`, `connection_accepted`, `connection_refused`, `auth_success`, `auth_failure` | |
| `subject` | text | machine id / operator id / connection id |
| `detail` | jsonb | structured context (reason, remote addr, etc.) |
| `prev_hash` | text | hash of previous row |
| `hash` | text | SHA-256(`prev_hash` ‖ canonical(row)) — tamper-evidence |

**Validation**: rows are insert-only (no update/delete); `hash` chain must verify on audit read.

## Non-persistent / external

- **Contract Version** — a runtime value in the connect handshake (see `contracts/protocol.md`), not
  a table.
- **Configuration / Secret** — sourced from SSM Parameter Store / `.env`; never persisted in the app
  DB and never committed.

## Entity relationship summary

```text
Operator 1───N WebAuthnCredential
Machine  1───N DeviceCertificate 1───N AgentConnection
Machine  1───N EnrollmentRequest
Machine  1───N AgentConnection
(AuditEvent references subjects by id; append-only, hash-chained)
```
