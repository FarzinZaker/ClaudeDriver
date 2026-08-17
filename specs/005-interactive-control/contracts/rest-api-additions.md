# Contract: REST additions (Phase 4)

Operator-authenticated (Phase 0 passkey session). JSON. Unauthenticated → 401 + audit.

### `POST /machines/{id}/start-managed`
Start a managed (Agent-SDK) run. Body: `{ "projectPath": "…", "instruction": "…" }`.
→ `202 { "commandId": "uuid", "status": "pending" }`. `409` if the machine is offline. (Issues a
`start_managed` control command; the session appears in monitoring marked managed.)

### `GET /questions`
Pending + recent free-form questions. → `200`:
```jsonc
{ "questions": [
  { "id": "uuid", "machineId": "uuid", "machineName": "dev-1", "claudeSessionId": "abc-123",
    "text": "…", "status": "pending|answered|cancelled|unanswered", "createdAt": "RFC3339",
    "answer": "…|null", "resolvedBy": "…|null" }
] }
```

### `POST /questions/{id}/answer`
Body: `{ "answer": "text" }` **or** `{ "cancel": true }`. Applies at most once.
- `200 { "status": "answered|cancelled" }`.
- `409 { "error": "already_resolved" }` if not pending.
Routes a `question_answer` to the agent, emits a `question_event`, and audits.

### `GET /sessions/{id}/transcript`
The full ordered transcript of a session. → `200 { "messages": [ { "role": "…", "text": "…", "at": "RFC3339" } ] }`.

### `GET /search?q=<term>&limit=<n>`
Cross-session search over transcript text. → `200`:
```jsonc
{ "results": [
  { "sessionId": "uuid", "machineName": "dev-1", "claudeSessionId": "abc-123",
    "role": "assistant", "snippet": "…term…", "at": "RFC3339" }
] }
```
Bounded/paged; MUST NOT block live control.

### `POST /machines/{id}/rotate-cert`
Hardening: revoke the machine's active device certs and issue a fresh enrollment.
→ `201 { "enrollmentCode": "…", "expiresAt": "RFC3339" }`. Old certs stop working; the agent
re-enrolls with the new code. Audited.

## Live push
The operator WebSocket also emits `question_event` and `transcript_event`. `GET /questions` +
`GET /sessions/{id}/transcript` seed initial state.

## Test coverage (maps to spec)
- Managed `question_raised` → `GET /questions` shows it pending (US1 / SC-001); `POST
  /questions/{id}/answer` routes the answer → companion continues (US1 / SC-002); at-most-once (SC-003).
- Cancel → session told no answer; never fabricated (FR-003 / SC-003).
- `transcript_message`s stored → `GET /sessions/{id}/transcript` returns them; `GET /search` finds a
  term (US3 / SC-004).
- `POST /machines/{id}/rotate-cert` revokes old + issues new; old identity refused (US4 / SC-005).
