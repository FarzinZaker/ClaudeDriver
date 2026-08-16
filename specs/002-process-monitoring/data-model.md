# Phase 1 Data Model (delta on Phase 0)

New tables via Flyway `V2__monitoring.sql`. Phase 0 tables (`machine`, `device_certificate`,
`agent_connection`, `audit_event`, …) are reused. Machine online/last-seen is **derived** from
`agent_connection` (no new column needed).

## Session

One monitored Claude Code instance.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | internal id |
| `machine_id` | UUID (FK → machine) | owning machine |
| `claude_session_id` | text | Claude Code's session id (from hook events); unique per machine |
| `project_path` | text, null | working directory / project |
| `state` | enum: `running`, `waiting_for_operator`, `finished`, `stopped`, `unknown_stale` | |
| `last_activity_at` | timestamptz | drives staleness |
| `process_present` | boolean | whether a matching OS process is currently detected |
| `created_at` | timestamptz | |

**Uniqueness**: (`machine_id`, `claude_session_id`).
**Transitions**: see research D5. `unknown_stale` when machine offline or `now - last_activity_at`
exceeds the threshold.

## ActivityEvent

A reported occurrence for a session (bounded recent history).

| Field | Type | Notes |
|---|---|---|
| `id` | bigserial (PK) | |
| `session_id` | UUID (FK → session) | |
| `kind` | text | e.g. `notification`, `stop`, `session_start`, `session_end`, `tool` |
| `attention` | enum: `needs_attention`, `informational`, `completion` | classifier output |
| `summary` | text | human-readable (e.g. "permission: Bash `git push`") |
| `detail` | text | structured payload (JSON as text) |
| `at` | timestamptz | |

**Retention**: keep the most recent N per session (bounded history, Principle VII); older pruned.

## Alert

An operator-facing needs-attention item.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `session_id` | UUID (FK → session) | |
| `machine_id` | UUID (FK → machine) | denormalized for inbox display |
| `status` | enum: `active`, `acknowledged`, `resolved` | |
| `urgency` | enum: `high`, `normal`, `low` | permission/question = high, idle = normal, completion = low |
| `summary` | text | what is being asked |
| `raised_at` | timestamptz | |
| `acknowledged_at` | timestamptz, null | |
| `resolved_at` | timestamptz, null | |
| `resolved_reason` | text, null | `answered`, `session_stopped`, `process_gone` |

**Dedupe**: at most one non-resolved alert per `session_id`.
**Audit**: raised/acknowledged/resolved appended to `audit_event` (Phase 0).

## Relationships

```text
Machine 1───N Session 1───N ActivityEvent
Session 1───0..1 Alert (active)   (historical alerts N)
Machine online/last-seen ← derived from agent_connection (Phase 0)
```
