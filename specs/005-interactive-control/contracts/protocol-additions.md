# Contract: Protocol additions (Phase 4)

Additive to `shared`. **`PROTOCOL_VERSION` → 0.5.0** (same-MAJOR compatible; unknown types ignored).
A new control type `start_managed` reuses the Phase 3 `control_command`.

## Agent → backend

### `question_raised`
A managed session posed a free-form question.
```jsonc
{ "type": "question_raised", "seq": N, "payload": {
    "questionId": "uuid", "claudeSessionId": "abc-123", "text": "Which region?", "at": "RFC3339"
}}
```

### `transcript_message`
An ordered message in a managed session's conversation.
```jsonc
{ "type": "transcript_message", "seq": N, "payload": {
    "claudeSessionId": "abc-123", "role": "assistant|user|tool|system", "text": "…", "at": "RFC3339"
}}
```

## Backend → agent

### `question_answer`
The operator's answer or cancel, routed to the owning agent (→ companion).
```jsonc
{ "type": "question_answer", "seq": N, "payload": {
    "questionId": "uuid", "answer": "text|null", "cancel": false
}}
```
Applied idempotently by `questionId`; unknown/already-resolved is a no-op.

### `control_command` (type `start_managed`)
Starts a managed run (Phase 3 shape, new `type`).
```jsonc
{ "type": "control_command", "seq": N, "payload": {
    "commandId": "uuid", "type": "start_managed", "projectPath": "/path", "instruction": "…", "at": "RFC3339"
}}
```

## Backend → operator

### `question_event`
```jsonc
{ "type": "question_event", "seq": N, "payload": {
    "questionId": "uuid", "machineId": "uuid", "machineName": "dev-1", "claudeSessionId": "abc-123",
    "text": "…", "status": "pending|answered|cancelled|unanswered", "at": "RFC3339", "resolvedBy": "…|null"
}}
```

### `transcript_event`
```jsonc
{ "type": "transcript_event", "seq": N, "payload": {
    "claudeSessionId": "abc-123", "machineId": "uuid", "role": "assistant", "text": "…", "at": "RFC3339"
}}
```

## Compatibility

Additive; ship the `shared` module + version bump together (Principle III).
