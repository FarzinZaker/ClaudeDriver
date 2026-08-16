# Contract: Protocol additions (Phase 1)

Additive changes to `shared` (contracts/protocol.md from Phase 0). New message types under the same
`Envelope`; **`PROTOCOL_VERSION` → 0.2.0** (MINOR: new types, backward-compatible framing). Same
version negotiation applies.

## Agent → backend

### `process_snapshot`
The agent's current view of Claude Code processes on its machine (sent on change / heartbeat).
```jsonc
{ "type": "process_snapshot", "seq": N, "payload": {
    "processes": [
      { "pid": 12345, "claudeSessionId": "abc-123|null", "projectPath": "/path|null", "startedAt": "RFC3339" }
    ]
}}
```

### `activity_event`
A Claude Code hook event, forwarded from the agent's localhost receiver.
```jsonc
{ "type": "activity_event", "seq": N, "payload": {
    "claudeSessionId": "abc-123",
    "kind": "notification|stop|session_start|session_end|tool",
    "notificationType": "permission_prompt|idle_prompt|agent_needs_input|null",
    "projectPath": "/path|null",
    "summary": "permission: Bash `git push`",
    "detail": "{...}",
    "at": "RFC3339"
}}
```

## Backend → operator

### `session_update`
Live session state for the dashboard.
```jsonc
{ "type": "session_update", "seq": N, "payload": {
    "sessionId": "uuid", "machineId": "uuid", "projectPath": "/path|null",
    "state": "running|waiting_for_operator|finished|stopped|unknown_stale",
    "lastActivityAt": "RFC3339", "processPresent": true
}}
```

### `alert_event`
Alert raised / changed / resolved.
```jsonc
{ "type": "alert_event", "seq": N, "payload": {
    "alertId": "uuid", "sessionId": "uuid", "machineId": "uuid",
    "status": "active|acknowledged|resolved", "urgency": "high|normal|low",
    "summary": "permission: Bash `git push`", "raisedAt": "RFC3339",
    "resolvedReason": "answered|session_stopped|process_gone|null"
}}
```

## Compatibility

Additive only. Unknown types remain ignored by peers on a compatible MAJOR.MINOR (forward-compat).
The `shared` module and `PROTOCOL_VERSION` bump ship in the same change set (Principle III).
