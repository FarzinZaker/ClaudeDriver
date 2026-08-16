# Contract: Shared WebSocket Protocol (Phase 0)

Defined once in `shared/commonMain` (`kotlinx.serialization`) and consumed by backend, agent, and
web. JSON over WSS. This is the **single source of truth** for the wire format (Principle III). Only
Phase 0 message types are listed; later phases add types under the same envelope + version rules.

## Envelope

Every frame is an envelope:

```jsonc
{
  "protocolVersion": "0.1.0",   // semver of THIS contract
  "type": "string",             // discriminator (see message types)
  "seq": 0,                     // per-connection monotonic sequence (sender-assigned)
  "commandId": "uuid|null",     // present on commands; enables idempotent dedupe
  "payload": { }                // type-specific object
}
```

Rules:
- `seq` strictly increases per connection per direction; receiver tracks last-seen for resume.
- A frame with a `commandId` already processed on this connection MUST be acknowledged but not
  re-executed (idempotency, Principle V).
- Unknown `type` on a compatible version MUST be ignored (forward-compat), not fatal.

## Version negotiation (connect handshake)

1. Immediately after the WSS upgrade, the agent sends `hello`; the backend replies `hello_ack`.
2. Compatibility rule (Phase 0): **same MAJOR.MINOR** required; PATCH differences allowed.
3. On mismatch the backend sends `version_mismatch` with the reason and closes the socket. No other
   messages are processed. (Refused, never silently coerced.)

## Message types (Phase 0)

### `hello` (agent → backend)
```jsonc
{ "protocolVersion": "0.1.0", "type": "hello", "seq": 1, "payload": {
    "machineName": "string",         // informational; identity comes from the mTLS cert
    "agentVersion": "string",
    "resumeFromSeq": 0                // last event seq the agent already received (0 = fresh)
}}
```

### `hello_ack` (backend → agent)
```jsonc
{ "protocolVersion": "0.1.0", "type": "hello_ack", "seq": 1, "payload": {
    "machineId": "uuid",             // resolved from the verified device certificate
    "serverTime": "RFC3339",
    "heartbeatSeconds": 30
}}
```

### `version_mismatch` (backend → agent, terminal)
```jsonc
{ "protocolVersion": "0.1.0", "type": "version_mismatch", "seq": 1, "payload": {
    "serverVersion": "0.1.0",
    "reason": "string"               // e.g. "requires MAJOR.MINOR 0.1"
}}
```

### `ping` / `pong` (both directions)
```jsonc
{ "protocolVersion": "0.1.0", "type": "ping", "seq": 42, "payload": { "t": "RFC3339" } }
{ "protocolVersion": "0.1.0", "type": "pong", "seq": 42, "payload": { "t": "RFC3339" } }
```
Missing pong within 2× `heartbeatSeconds` ⇒ the peer treats the socket as dead and reconnects.

### `sample_event` (agent → backend → web) — the Phase 0 end-to-end demonstration
Proves one message defined once is interpreted identically by agent, backend, and web (FR-016).
```jsonc
{ "protocolVersion": "0.1.0", "type": "sample_event", "seq": 7, "payload": {
    "machineId": "uuid",
    "message": "string",
    "at": "RFC3339"
}}
```
Backend persists nothing beyond audit needs; it relays the event to connected operator clients over
their own WSS, unchanged in meaning.

## Compatibility / evolution

- Additive fields on existing types = PATCH. New message types = MINOR. Removing/renaming a field or
  changing its meaning = MAJOR (and a negotiated break). Bumping the contract version and the
  `shared` module happen in the same change set (Principle III / workflow rule).
