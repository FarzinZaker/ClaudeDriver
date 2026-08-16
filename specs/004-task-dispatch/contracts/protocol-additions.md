# Contract: Protocol additions (Phase 3)

Additive to `shared`. **`PROTOCOL_VERSION` → 0.4.0** (same-MAJOR compatible; unknown types ignored).

## Backend → agent

### `control_command`
A control action routed to the owning agent.
```jsonc
{ "type": "control_command", "seq": N, "payload": {
    "commandId": "uuid",
    "type": "start_run|dispatch_task|stop_session",
    "claudeSessionId": "abc-123|null",   // target session (dispatch/stop)
    "projectPath": "/path|null",         // start-run
    "instruction": "text|null",          // start-run / dispatch
    "at": "RFC3339"
}}
```

## Agent → backend

### `control_result`
The outcome, correlated by `commandId`.
```jsonc
{ "type": "control_result", "seq": N, "payload": {
    "commandId": "uuid",
    "status": "started|delivered|done|stopped|undeliverable|error",
    "claudeSessionId": "abc-123|null",   // for start-run, the new session id
    "message": "…|null"
}}
```
Applied to the command idempotently (only if not already terminal).

## Backend → operator

### `control_event`
Command status for live UI.
```jsonc
{ "type": "control_event", "seq": N, "payload": {
    "commandId": "uuid", "machineId": "uuid", "machineName": "dev-1",
    "commandType": "start_run|dispatch_task|stop_session",
    "status": "pending|started|delivered|done|stopped|undeliverable|error",
    "claudeSessionId": "abc-123|null", "at": "RFC3339", "message": "…|null"
}}
```

## Compatibility

Additive; ship the `shared` module + version bump together (Principle III).
