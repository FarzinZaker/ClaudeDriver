# Phase 4 Data Model (delta on Phases 0–3)

New tables via Flyway `V5__managed.sql`. Reuses `machine`, `session`, `audit_event`, and the Phase 0
credential tables (for rotation/revocation).

## Question

A free-form question a managed session posed.

| Field | Type | Notes |
|---|---|---|
| `id` | UUID (PK) | also the questionId on the wire |
| `machine_id` | UUID (FK → machine) | |
| `session_id` | UUID (FK → session), null | |
| `claude_session_id` | text | the managed session id |
| `text` | text | the question |
| `status` | enum: `pending`, `answered`, `cancelled`, `unanswered` | lifecycle |
| `answer` | text, null | the operator's answer (when answered) |
| `created_at` | timestamptz | |
| `resolved_at` | timestamptz, null | |
| `resolved_by` | text, null | operator handle |

**At-most-once**: answer/cancel applies only while `pending`. `unanswered` = companion ended/crashed
before an answer. **Never** a fabricated answer.

## TranscriptMessage

An ordered message in a managed session's conversation (for viewing + search).

| Field | Type | Notes |
|---|---|---|
| `id` | bigserial (PK) | monotonic order |
| `session_id` | UUID (FK → session), null | |
| `claude_session_id` | text | |
| `machine_id` | UUID (FK → machine) | |
| `role` | text | `user` / `assistant` / `tool` / `system` |
| `text` | text | message content |
| `at` | timestamptz | |

**Search**: case-insensitive text match over `text`, bounded/paged, grouped by session.

## Reused

- **Session / Machine**: a managed session is a Session marked managed (via `session_start`).
- **Device certificate / Operator credential** (Phase 0): rotation revokes active certs + issues a
  fresh enrollment; revocation reuses `machine_revoked`.
- **Audit**: question raised/answered/cancelled; cert rotate/revoke.

## Relationships

```text
Machine 1───N Question ───0..1 Session
Machine 1───N TranscriptMessage ───0..1 Session
(Question + rotation/revocation → audit_event)
```
