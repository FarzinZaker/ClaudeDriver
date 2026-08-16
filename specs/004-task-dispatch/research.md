# Phase 3 Research & Decisions (delta on Phases 0–2)

Format: **Decision → Rationale → Alternatives**.

## D1 — Control commands over the Phase 2 backend→agent channel

**Decision**: Reuse the Phase 2 `AgentHub` outbound path. The operator issues a control command; the
backend persists it, routes a `control_command` to the owning agent over its WSS, and the agent
replies with a `control_result` (correlated by `commandId`). At-most-once by `commandId`; audited.
**Rationale**: One authenticated, outbound-only channel already exists for approval decisions; control
is the same shape (Principle IV/I). Idempotency by id gives Principle V.
**Alternatives**: a new inbound control endpoint on the machine (breaks outbound-only). Rejected.

## D2 — Agent-managed sessions + a pluggable launcher

**Decision**: The agent runs a `SessionController` that owns the Claude Code runs it starts. A
`Launcher` interface starts a process for a project+instruction; production launches `claude`, tests
use a fake stdin-reading process. Control (dispatch/start/stop) targets **agent-managed** sessions
(keyed by a `claudeSessionId` the agent assigns and reports). Externally-started sessions stay
*observable* (Phase 1) but are **not controllable** — a documented boundary.
**Rationale**: A "persistent, controllable" session (the clarified requirement) must be one the agent
started and holds; driving an arbitrary external terminal is not reliably supported. The launcher
interface makes the whole control plane testable without real Claude Code.
**Alternatives**: try to inject into any monitored terminal (fragile, unsupported — rejected); require
the Agent SDK for all sessions (that's Phase 4's fuller managed mode — deferred).

## D3 — Dispatch: queue-until-ready, else undeliverable

**Decision**: A dispatched instruction is delivered to its managed session when the session is ready
for input; if the session is busy it is queued and delivered next-ready; if it never becomes ready
within a bounded window (or the session is gone), the dispatch is reported **undeliverable**. Never
lost, never forced into a blocked prompt.
**Rationale**: Implements the clarified behavior and FR-002/SC-006.
**Alternatives**: reject-when-busy (rejected by clarification); interrupt current turn (rejected —
aggressive/unsupported).

## D4 — Stop: graceful then force; moot approvals

**Decision**: Stop asks the managed run to end (destroy / close stdin), waits a short window, then
force-terminates (destroyForcibly) if still alive; emits a session-end so monitoring reflects stopped;
and moots the session's pending approvals (reuse Phase 2 `mootForClaudeSession`).
**Rationale**: Clarified graceful→force; consistent with Phase 2's moot-on-stop.
**Alternatives**: force-only (rejected by clarification).

## D5 — Delivery mechanism & surfacing a started run

**Decision**: The managed process receives an instruction via its input (stdin line in the skeleton;
Claude Code's supported input/resume in production). On start, the agent assigns a `claudeSessionId`,
launches the process, and emits an `activity_event(session_start)` so the run appears in monitoring
(Phase 1) alongside the `control_result(started)`.
**Rationale**: Reuses the Phase 1 monitoring surface for the new session; keeps one source of session
truth. The exact production input path to `claude` is the piece that needs real Claude Code to fully
validate; the control plane around it is proven with the fake launcher.
**Alternatives**: a separate "managed session" table divorced from monitoring (duplication —
rejected).

## Deferred (later)

- Answering arbitrary mid-turn questions via SDK-managed sessions (Phase 4).
- Per-operator machine/project authorization scopes (multi-user).
