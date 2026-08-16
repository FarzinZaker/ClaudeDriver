# Contract: REST additions (Phase 2)

Operator-authenticated (Phase 0 passkey session) unless noted. JSON. Unauthenticated → 401 + audit.

### `GET /approvals`
Pending + recent approval requests for the inbox. → `200`:
```jsonc
{ "approvals": [
  { "id": "uuid", "machineId": "uuid", "machineName": "dev-1", "claudeSessionId": "abc-123",
    "tool": "Bash", "summary": "Bash: `git push`", "status": "pending|approved|denied|moot",
    "createdAt": "RFC3339", "decidedBy": "operator|null", "reason": "…|null" }
] }
```

### `POST /approvals/{id}/decide`
Body: `{ "decision": "approve" | "deny" }`. Applies at most once.
- `200 { "status": "approved|denied" }` on success.
- `409 { "error": "already_resolved" }` if the request is not `pending`.
Emits an `approval_decision` to the agent, an `approval_event` to operators, and an audit entry.

### `POST /devices`
Register this device for push. Body: `{ "token": "…", "platform": "ios|android" }`. → `201`.
Re-registering the same token refreshes it (idempotent).

### `DELETE /devices/{token}`
Unregister (sign-out / app removal). → `204`.

## Live push
The operator WebSocket (`/ws/operator`) additionally emits `approval_event` frames. Seeded by
`GET /approvals` on load.

## Test coverage (maps to spec)
- Blocking `PreToolUse` (matched tool) → `approval_request` held; `GET /approvals` shows it pending
  within the latency budget (US1 / SC-001).
- `POST /approvals/{id}/decide` approve → held hook returns allow; deny → returns deny (US1 / SC-002).
- Deciding twice → second call `409`, no double-apply (US4 / SC-005).
- Session stop while pending → request `moot` + deny sent (US1 #5 / edge).
- WSS drop while held → agent completes deny (fail-safe / SC-003).
- Every decision audited (FR-013 / SC-006).
- `POST /devices` registers; a raise triggers a push send (LoggingPushSender records it) (US2).
