# Contract: REST additions (Phase 3)

Operator-authenticated (Phase 0 passkey session). JSON. Unauthenticated → 401 + audit.

### `POST /sessions/{id}/dispatch`
Send an instruction to a monitored (agent-managed) session. Body: `{ "instruction": "…" }`.
→ `202 { "commandId": "uuid", "status": "pending" }` (delivery is asynchronous; watch `control_event`).
`404` if no such session; `409` if the machine is offline.

### `POST /machines/{id}/start-run`
Start a new persistent Claude Code run. Body: `{ "projectPath": "…", "instruction": "…" }`.
→ `202 { "commandId": "uuid", "status": "pending" }`. `409` if the machine is offline.

### `POST /sessions/{id}/stop`
Stop a running session (graceful → force). → `202 { "commandId": "uuid", "status": "pending" }`.
`404` if no such session.

### `GET /commands`
Recent control commands + their status. → `200`:
```jsonc
{ "commands": [
  { "id": "uuid", "machineId": "uuid", "machineName": "dev-1", "type": "dispatch_task",
    "claudeSessionId": "abc-123|null", "instruction": "…|null", "status": "delivered",
    "createdAt": "RFC3339", "message": "…|null" }
] }
```

## Live push
The operator WebSocket (`/ws/operator`) additionally emits `control_event` frames; `GET /commands`
seeds the initial list.

## Test coverage (maps to spec)
- `POST /sessions/{id}/dispatch` to an idle managed session → `control_command` routed; agent delivers;
  status `delivered` (US1 / SC-001). Undeliverable if the session is gone (SC-006).
- `POST /machines/{id}/start-run` → agent launches a persistent run; status `started`; the session
  appears in monitoring (US2 / SC-002).
- `POST /sessions/{id}/stop` → agent ends the run; status `stopped`; pending approvals mooted
  (US3 / SC-003).
- Re-issuing a command result twice → applied at most once (US4 / SC-004).
- Every command + result audited (FR-010 / SC-004).
