# Contract: Protocol additions (Phase 2)

Additive to `shared`. **`PROTOCOL_VERSION` → 0.3.0** (same-MAJOR compatible; unknown types ignored).

## Agent → backend

### `approval_request`
Sent when a blocking Claude Code permission hook is held on the machine.
```jsonc
{ "type": "approval_request", "seq": N, "payload": {
    "requestId": "uuid",           // agent-generated; correlates the held hook
    "claudeSessionId": "abc-123",
    "tool": "Bash",
    "summary": "Bash: `git push origin main`",
    "detail": "{...}",             // raw hook payload
    "projectPath": "/path|null",
    "at": "RFC3339"
}}
```

## Backend → agent

### `approval_decision`
The operator's decision, routed to the machine holding the request.
```jsonc
{ "type": "approval_decision", "seq": N, "payload": {
    "requestId": "uuid",
    "decision": "approve|deny",
    "reason": "operator|session_stopped|path_failed|platform_limit"
}}
```
The agent applies it to the held hook (allow/deny) idempotently by `requestId`; an unknown or
already-completed `requestId` is a no-op.

## Backend → operator

### `approval_event`
Approval raised / decided / resolved, for live UI.
```jsonc
{ "type": "approval_event", "seq": N, "payload": {
    "approvalId": "uuid", "machineId": "uuid", "machineName": "dev-1",
    "claudeSessionId": "abc-123", "tool": "Bash", "summary": "…",
    "status": "pending|approved|denied|moot", "at": "RFC3339",
    "decidedBy": "operator|null", "reason": "…|null"
}}
```

## Compatibility

Additive only; ship the `shared` module + version bump together (Principle III). The mobile app
reuses these exact types.
