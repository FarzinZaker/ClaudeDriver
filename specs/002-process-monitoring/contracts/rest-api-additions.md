# Contract: REST additions (Phase 1)

New operator-authenticated endpoints (session-cookie auth from Phase 0). All JSON; unauthenticated
access → 401 + audit (Phase 0 behavior).

### `GET /sessions`
List monitored sessions across machines. → `200`:
```jsonc
{ "sessions": [
  { "id": "uuid", "machineId": "uuid", "machineName": "dev-1", "projectPath": "/path|null",
    "state": "running|waiting_for_operator|finished|stopped|unknown_stale",
    "lastActivityAt": "RFC3339", "processPresent": true }
] }
```

### `GET /sessions/{id}`
Session detail + recent event history. → `200`:
```jsonc
{ "session": { /* as above */ },
  "recentEvents": [
    { "kind": "notification", "attention": "needs_attention", "summary": "…", "at": "RFC3339" }
  ] }
```

### `GET /alerts`
Active + recent alerts for the inbox. → `200`:
```jsonc
{ "alerts": [
  { "id": "uuid", "sessionId": "uuid", "machineId": "uuid", "machineName": "dev-1",
    "status": "active|acknowledged|resolved", "urgency": "high|normal|low",
    "summary": "permission: Bash `git push`", "raisedAt": "RFC3339",
    "resolvedReason": "answered|session_stopped|process_gone|null" }
] }
```

### `POST /alerts/{id}/ack`
Acknowledge an active alert. → `204`; emits an `alert_event` (status `acknowledged`) and an audit
entry. Acking a non-active alert → `409`.

## Extended: `GET /status`
The Phase 0 status response gains a per-machine `online`/`lastSeen` (derived from the agent
connection) and a `sessionCount`; existing fields unchanged (additive).

## Live push
The operator WebSocket (`/ws/operator`, Phase 0) now also emits `session_update` and `alert_event`
frames (protocol-additions.md). Seeded by `GET /sessions` + `GET /alerts` on load.

## Test coverage (maps to spec)
- `GET /sessions` reflects a started/stopped process within the latency budget (US1 / SC-001).
- A needs-attention `activity_event` raises exactly one `alert_event` + `GET /alerts` shows it
  (US2 / SC-002/003); a resolving event auto-resolves it.
- Informational events raise no alert (SC-004).
- Offline machine → sessions `unknown_stale` (US1 #4 / SC-005).
- `POST /alerts/{id}/ack` transitions status + audits (US2 / FR-013).
