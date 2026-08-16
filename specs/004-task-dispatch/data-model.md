# Phase 3 Data Model (delta on Phases 0–2)

New table via Flyway `V4__control.sql`. Reuses `machine`, `session`, `approval_request`,
`audit_event`.

## ControlCommand

An operator-issued control action and its outcome.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | also the `commandId` on the wire |
| `machine_id` | UUID (FK → machine) | owning/target machine |
| `session_id` | UUID (FK → session), null | target session (dispatch/stop); null for start-run |
| `claude_session_id` | text, null | target session id on the wire |
| `type` | enum: `start_run`, `dispatch_task`, `stop_session` | |
| `project_path` | text, null | for start-run |
| `instruction` | text, null | for start-run / dispatch |
| `status` | enum: `pending`, `delivered`, `done`, `started`, `stopped`, `undeliverable`, `error` | lifecycle |
| `result_message` | text, null | agent-reported detail |
| `issued_by` | text | operator handle |
| `created_at` | timestamptz | |
| `updated_at` | timestamptz | on result |

**At-most-once**: a `control_result` updates a command only while it is not yet terminal.
**Statuses**: start_run → `started` | `error`; dispatch_task → `delivered`/`done` | `undeliverable`;
stop_session → `stopped` | `error`.

## Relationships

```text
Machine 1───N ControlCommand ───0..1 Session
(ControlCommand issue + result → audit_event, Phase 0)
Stop → moots pending ApprovalRequest for the session (Phase 2)
```

## Reused

- **Session / Machine** (Phases 0–1): targets; a started run appears as a Session via an emitted
  `session_start` activity event.
- **ApprovalRequest** (Phase 2): mooted when a session is stopped.
- **Audit** (Phase 0): command issued and each result.
