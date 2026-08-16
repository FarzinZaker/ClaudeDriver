# Contract: REST API (Phase 0)

Backend HTTP surface for the walking skeleton. Two audiences on two ALB listeners:

- **Operator/web listener** — server TLS only; auth via WebAuthn passkey session cookie.
- **Agent listener** — ALB mutual-TLS (client cert required) for `/agent/*` and the WSS upgrade.

All request/response bodies are JSON. Error bodies: `{ "error": "code", "message": "human text" }`.
Unauthenticated access to any non-public route ⇒ `401` and an `auth_failure` audit event.

## Public

### `GET /healthz`
Liveness/readiness. `200 {"status":"ok","version":"..."}`. No auth. (Not an operator surface.)

## Operator — WebAuthn passkey authentication (self-hosted)

### `POST /auth/register/options`
Body: `{ "bootstrapCode": "string" }` (first operator only; rejected once an operator exists unless
a valid code is presented). → `200` WebAuthn `PublicKeyCredentialCreationOptions`.

### `POST /auth/register/verify`
Body: WebAuthn attestation response. → `201` (passkey stored) or `400`.

### `POST /auth/login/options`
Body: `{}`. → `200` WebAuthn `PublicKeyCredentialRequestOptions` (assertion challenge).

### `POST /auth/login/verify`
Body: WebAuthn assertion response. → `200` + sets a signed session cookie, or `401` (+
`auth_failure` audit). `sign_count` regression ⇒ `401`.

### `POST /auth/logout`
→ `204`, clears session.

## Operator — machines & enrollment (session-authenticated)

### `GET /status`
→ `200`:
```jsonc
{
  "server": { "version": "0.1.0", "time": "RFC3339" },
  "machines": [
    { "id": "uuid", "name": "string", "os": "windows|macos",
      "status": "pending|enrolled|revoked",
      "connection": { "state": "connected|disconnected", "since": "RFC3339|null",
                      "protocolVersion": "0.1.0|null" } }
  ],
  "recentSampleEvents": [ { "machineId": "uuid", "message": "string", "at": "RFC3339" } ]
}
```
This is what the minimal web status page renders (live via the operator WSS; this REST call seeds
initial load).

### `POST /machines`
Register a machine record to enroll. Body: `{ "name": "string", "os": "windows|macos" }`. →
`201 { "machineId": "uuid" }` with status `pending`.

### `POST /machines/{id}/enrollment`
Operator approves enrollment and mints a one-time code. → `201`:
```jsonc
{ "enrollmentCode": "string", "expiresAt": "RFC3339" }
```
Emits `enrollment_approved` audit. The code is shown once; only its hash is stored.

### `POST /machines/{id}/revoke`
→ `204`; sets machine `revoked`, marks its device certs `revoked`, drops live connections. Emits
`machine_revoked` audit. Subsequent connections from that identity ⇒ refused.

## Agent — enrollment & connection (mutual-TLS listener)

### `POST /agent/enroll`
Called by an un-carded agent over the agent listener using the one-time code (this specific call
may use a bootstrap/enrollment TLS profile; see plan). Body:
```jsonc
{ "machineId": "uuid", "enrollmentCode": "string", "csr": "PEM PKCS#10" }
```
→ `201`:
```jsonc
{ "deviceCertificate": "PEM", "caChain": "PEM", "notAfter": "RFC3339" }
```
Emits `enrollment_consumed` audit and flips the machine to `enrolled`. Invalid/expired/duplicate
code ⇒ `403` (no certificate issued).

### `GET /agent/whoami`
Client-cert-authenticated sanity check. → `200 { "machineId": "uuid", "status": "enrolled" }`;
missing/invalid client cert ⇒ refused at the ALB (never reaches the app) or `401` + audit.

### `GET /agent/connect`  (WebSocket upgrade)
Requires a valid client cert (ALB-verified). Upgrades to the WSS channel defined in
`protocol.md`. On accept → `connection_accepted` audit; on refuse (unenrolled/forged/expired/version
mismatch) → `connection_refused` audit.

## Contract test coverage (maps to spec acceptance scenarios)

- Unauthenticated `/status` ⇒ `401` + audit (US1 #3, FR-006/007).
- Unenrolled/forged client cert on `/agent/connect` ⇒ refused + audit (US2 #1/#4, FR-011).
- Approved enrollment → cert issued → agent connects and appears `enrolled` (US2 #2/#3).
- `sample_event` traverses agent → backend → operator WSS unchanged (US3 #1, FR-016).
- `hello` with incompatible `protocolVersion` ⇒ `version_mismatch` + close (US3 #3, FR-015).
- Revoke → subsequent connect refused (edge case, FR-012).
