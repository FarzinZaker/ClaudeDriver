# Phase 1 Research & Decisions (delta on Phase 0)

Only decisions new to monitoring are recorded; Phase 0 stack decisions (Ktor, Postgres, mTLS,
WebAuthn, AWS) carry forward unchanged. Format: **Decision → Rationale → Alternatives**.

## D1 — Process detection: OSHI, poll-and-diff

**Decision**: The agent uses OSHI (`oshi-core`) to enumerate processes on an interval (~2 s), match
Claude Code by executable/command-line, read each match's working directory and lifecycle, and diff
snapshots to emit started/exited transitions.
**Rationale**: One JVM API covers Windows + macOS (Principle III of the ops story); poll-and-diff is
simple and bounded. Matches the Phase 0 constitution/roadmap choice of OSHI.
**Alternatives**: OS-native watchers (per-OS code, more work), transcript-file tailing (unstable
format — rejected in Phase 0 research).
**Caveat**: reading another user's process cwd may need elevated privilege; where unavailable the
project is shown as best-known (spec FR-001 allows this).

## D2 — Activity events arrive via a localhost hook receiver in the agent

**Decision**: The agent embeds a tiny **Ktor CIO server bound to 127.0.0.1** on a fixed local port.
Claude Code's HTTP hooks POST events there (token-protected); the agent forwards them to the backend
over its existing authenticated **outbound** WebSocket.
**Rationale**: Preserves outbound-only (Principle IV) — Claude Code never talks to the backend
directly, and nothing new is exposed off the machine (the receiver is loopback-only). One
authenticated channel to the backend.
**Alternatives**: Claude Code posts directly to the backend public URL (rejected — breaks
outbound-only, needs per-machine backend credentials, widens attack surface); agent tails transcript
JSONL (rejected — unstable/unsupported format).

## D3 — Claude Code hook installation: a managed block in user settings, idempotent + reversible

**Decision**: The agent writes a **managed section** into the user-scope Claude Code
`settings.json`: `http` hooks for `Notification`, `Stop`, `SessionStart`, `SessionEnd` targeting
`http://127.0.0.1:<port>/hook`, with `allowedHttpHookUrls` including loopback and the auth header
value drawn from an env var (listed in `httpHookAllowedEnvVars`). Installation merges (never
overwrites the user's other hooks), is idempotent (keyed by a managed marker), and teardown removes
exactly the managed section.
**Rationale**: User scope covers all the user's projects; `http` hooks + env-var auth match Claude
Code's documented mechanism and keep secrets out of committed/plaintext-shared files (FR-017/018).
**Alternatives**: project-scope hooks (per-project, misses others), `command` hooks shelling `curl`
(works but less clean and portable than the `http` type).

## D4 — Needs-attention classification: a configurable map with safe defaults

**Decision**: A backend classifier maps event kinds → `NEEDS_ATTENTION` | `INFORMATIONAL` |
`COMPLETION`. Defaults: `Notification` of kind permission-prompt / idle-prompt / needs-input →
NEEDS_ATTENTION; `Stop` → COMPLETION (low-urgency); `SessionStart`/`SessionEnd`/tool events →
INFORMATIONAL. The map is configuration, not code.
**Rationale**: Directly implements FR-006 and keeps alert noise tunable (the clarified open
question). Defaults chosen to avoid false alarms (SC-004).
**Alternatives**: hard-coded rules (not tunable — rejected).

## D5 — Session registry & state machine, correlation by session id + cwd

**Decision**: Sessions are keyed by Claude Code's `session_id` (from hook events). Detected processes
are correlated to sessions by working directory + machine. State machine:
`running → waiting_for_operator → running`, `→ finished` (Stop), `→ stopped` (SessionEnd / process
exit), and `→ unknown_stale` when the machine is offline or no events arrive within a threshold.
**Rationale**: `session_id` is the stable identity Claude Code emits; cwd correlates the OS process.
Explicit stale state satisfies FR-009 / SC-005 (never falsely active).
**Alternatives**: key on pid (unstable, reused), on cwd alone (collides for multiple sessions in one
project — rejected).

## D6 — Alerts: raise on entry to waiting, auto-resolve on exit, de-duped, operator-acknowledgeable

**Decision**: Entering `waiting_for_operator` raises exactly one **active** alert per session (dedupe
on `(sessionId, active)`); leaving that state (answered at terminal, session finished/stopped, or
process gone) auto-resolves it; the operator may acknowledge an active alert. Lifecycle
(raised/acknowledged/resolved) is appended to the Phase 0 audit log.
**Rationale**: Implements FR-010–013 and SC-003 (one alert, no floods, auto-resolve).
**Alternatives**: alert per event (floods — rejected), no auto-resolve (stale inbox — rejected).

## Deferred (still later phases)

- Remote approvals of permission prompts (Phase 2) — Phase 1 only *alerts* on them.
- Task dispatch / answering (Phase 3/4). Mobile app + push (Phase 2).
