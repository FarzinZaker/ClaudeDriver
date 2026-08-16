# Phase 2 Data Model (delta on Phases 0–1)

New tables via Flyway `V3__approvals.sql`. Reuses `machine`, `session`, `operator`, `audit_event`.

## ApprovalRequest

A pending tool-permission decision for a waiting session.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | internal id (also the `requestId` on the wire) |
| `machine_id` | UUID (FK → machine) | |
| `session_id` | UUID (FK → session), null | correlated Claude Code session if known |
| `claude_session_id` | text | Claude Code session id from the hook |
| `tool` | text | tool name (e.g. `Bash`, `Write`) |
| `summary` | text | human-readable action (e.g. "Bash: `git push origin main`") |
| `detail` | text | structured hook payload (JSON as text) |
| `status` | enum: `pending`, `approved`, `denied`, `moot` | lifecycle |
| `created_at` | timestamptz | |
| `decided_at` | timestamptz, null | |
| `decided_by` | text, null | operator handle |
| `surface` | text, null | `web` or `mobile` |
| `decision_reason` | text, null | `operator`, `session_stopped`, `path_failed`, `platform_limit` |

**At-most-once**: a decision applies only while `status = pending`.
**Moot**: session stop/disappearance → `moot` + a deny decision sent to the agent.

## PushDevice

An operator device registered for push notifications.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | |
| `operator_id` | UUID (FK → operator) | |
| `token` | text, unique | platform push token (FCM/APNs) |
| `platform` | enum: `ios`, `android` | |
| `created_at` | timestamptz | |
| `last_seen_at` | timestamptz | updated on re-register; used to prune stale tokens |

**Prune**: tokens rejected by the push provider are deleted; re-register refreshes `last_seen_at`.

## Relationships

```text
Machine 1───N ApprovalRequest ───0..1 Session
Operator 1───N PushDevice
(ApprovalRequest raise + decision → audit_event, Phase 0)
```

## Reused

- **Alert / Session** (Phase 1): an approval request also raises/uses the attention surface; a
  session in `waiting_for_operator` may now be waiting specifically on an approval.
- **Audit** (Phase 0): approval raised, decided (approve/deny + who + surface + reason), and moot.
