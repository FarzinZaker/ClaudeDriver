# Feature Specification: Phase 3 — Remote Control & Task Dispatch

**Feature Branch**: `004-task-dispatch`

**Created**: 2026-08-16

**Status**: Draft

**Input**: User description: "Phase 3 Remote Control and Task Dispatch: send a new task or message to an idle session, start a new Claude Code run on a chosen machine and project, stop a running session, with a session control surface; authenticated, idempotent, audited"

## Overview

Phase 3 lets the operator *direct* the fleet, not just watch and approve it. From the web or phone
the operator can **send a new task or message to a session**, **start a fresh Claude Code run** on a
chosen machine and project, and **stop** a running session — each action delivered over the existing
authenticated agent channel, applied at most once, and recorded. Building on Phases 0–2 (enrollment,
the outbound agent channel, the shared contract, sessions/alerts, and approvals). Still **not** in
scope: answering an arbitrary free-form question Claude Code asks mid-turn (that needs a different
mechanism — Phase 4).

## Clarifications

### Session 2026-08-16

- Q: Dispatch to a session that is not idle? → A: **Queue until the session is ready, then deliver**;
  report undeliverable if it never becomes ready. Never lost, never forced into a blocked prompt.
- Q: Is "start a new run" in Phase 3? → A: **Yes** — built in Phase 3.
- Q: What kind of session does "start a run" create? → A: A **persistent** Claude Code session that
  stays alive so the operator can keep directing it (send follow-ups, approve, stop) — not a one-shot.
- Q: Stop semantics? → A: **Graceful first** (ask the run to end cleanly), escalating to
  force-terminate if it does not end within a short window.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Send a new task or message to a session (Priority: P1)

As the operator, I can type a new instruction and send it to a monitored session; when the session is
ready for input it receives the instruction and acts on it, and I can see that it was delivered.

**Why this priority**: Dispatching work is the core "take action" capability — it turns ClaudeDriver
from oversight into remote operation.

**Independent Test**: With an idle monitored session, send a short instruction from the dashboard;
confirm the session picks it up and begins working on it, and the dispatch shows as delivered.

**Acceptance Scenarios**:

1. **Given** a monitored session that is idle/ready for input, **When** the operator sends an
   instruction, **Then** the instruction is delivered to that session and it begins acting on it, and
   the dispatch is shown as delivered.
2. **Given** a session that is currently busy or waiting on a blocking prompt, **When** the operator
   sends an instruction, **Then** the system queues it and delivers it when the session is next
   ready, or clearly reports it cannot be delivered now — it is never lost silently or half-applied.
3. **Given** a dispatch is retried (reconnect/double-tap), **When** it is delivered, **Then** it takes
   effect **at most once**.

---

### User Story 2 - Start a new Claude Code run on a machine/project (Priority: P1)

As the operator, I can start a new Claude Code session on a chosen enrolled machine, in a chosen
project directory, with an initial instruction; the new session starts and appears in monitoring.

**Why this priority**: Starting work remotely (not only continuing existing sessions) is what makes
the operator able to kick off jobs from afar.

**Independent Test**: Pick an enrolled machine and a project path, provide an initial instruction, and
start; confirm a new session appears under that machine working in that project.

**Acceptance Scenarios**:

1. **Given** an enrolled, connected machine, **When** the operator starts a new run with a project and
   an initial instruction, **Then** a new **persistent** Claude Code session starts on that machine in
   that project, appears in monitoring, and remains available for further instructions.
2. **Given** an invalid project path or a machine that is offline, **When** the operator tries to
   start a run, **Then** the attempt is refused with a clear reason and nothing is started.

---

### User Story 3 - Stop a running session (Priority: P2)

As the operator, I can stop a monitored session; the corresponding Claude Code run is ended on its
machine and the session is shown as stopped.

**Why this priority**: The ability to halt a run remotely is essential control, especially if
something is going wrong. It depends on monitoring (US of Phase 1) to target the right session.

**Independent Test**: Stop a running monitored session; confirm the Claude Code process ends on the
machine and the session shows stopped, with any pending approvals for it resolved as moot.

**Acceptance Scenarios**:

1. **Given** a running monitored session, **When** the operator stops it, **Then** its Claude Code run
   ends on the machine and the session is shown as stopped.
2. **Given** a session with a pending approval, **When** it is stopped, **Then** the pending approval
   is resolved as moot (nothing runs) — consistent with Phase 2.
3. **Given** a session that already ended, **When** the operator stops it, **Then** the action is a
   no-op reported as already stopped.

---

### User Story 4 - Session control surface, safe and accountable (Priority: P3)

As the operator, each session has a control surface showing its recent activity and the send / start
/ stop controls; every control action is authenticated, recorded, idempotent, and errs toward doing
nothing when uncertain.

**Why this priority**: These actions run or halt code on real machines; being able to see context and
having every action authenticated and audited is what makes remote control trustworthy.

**Independent Test**: From a session's control surface, perform send/stop and confirm each is recorded
with who/what/which machine/when; retry an action and confirm it does not double-apply; disconnect
mid-action and confirm nothing partial or unsafe results.

**Acceptance Scenarios**:

1. **Given** any control action (send task, start run, stop), **When** it is performed, **Then** it is
   recorded to the audit trail with operator, machine, session (or target), action, and timestamp.
2. **Given** a control action delivered more than once, **When** it is applied, **Then** it takes
   effect at most once.
3. **Given** the agent link drops during a control action, **When** that happens, **Then** the action
   either completes or is cleanly reported as not delivered — never a partial or duplicated effect.
4. **Given** a control action, **When** it is authorized, **Then** it is carried over the
   authenticated device channel (Phase 0 identity); an unauthenticated path cannot control a session.

---

### Edge Cases

- **Send to a session that never becomes ready** (crashes/stops while queued) → the dispatch is
  reported undelivered; it does not silently vanish and is not delivered to a different session.
- **Start a run where Claude Code is not installed / the path is missing** → refused with a clear
  reason; nothing started.
- **Stop a session whose process already exited** → reported already stopped; no error state.
- **Two operators (or web + phone) act on the same session at once** → actions are serialized; the
  first wins where they conflict; the outcome is unambiguous.
- **Agent offline when a control action is issued** → the action is refused/queued explicitly (per
  clarified dispatch behavior), never applied blindly on reconnect in a way that surprises the
  operator.
- **Very long or malformed instruction text** → bounded/validated; rejected with a reason rather than
  mis-delivered.

## Requirements *(mandatory)*

### Functional Requirements

**Task/message dispatch**
- **FR-001**: The operator MUST be able to send a text instruction to a specific monitored session;
  when the session is ready for input the system MUST deliver it and the session MUST act on it.
- **FR-002**: If the target session is not ready (busy or blocked), the system MUST either queue the
  instruction for delivery when ready, or explicitly report it cannot be delivered now — never lose it
  silently or deliver it partially.
- **FR-003**: Dispatch MUST be applied **at most once** per instruction, even under retry/reconnect.
- **FR-004**: A dispatch MUST be routed only to its intended session on its intended machine; it MUST
  NOT be delivered to a different session.

**Start a new run**
- **FR-005**: The operator MUST be able to start a new Claude Code run on a chosen enrolled, connected
  machine, in a specified project path, with an initial instruction; the new session MUST appear in
  monitoring. The new session MUST be **persistent and controllable** — able to receive further
  dispatch, approvals, and stop — not a one-shot that exits after the first instruction.
- **FR-006**: Starting a run MUST be refused with a clear reason when the machine is offline, the
  project path is invalid, or Claude Code is unavailable on that machine — and nothing MUST start.

**Stop a session**
- **FR-007**: The operator MUST be able to stop a running monitored session; the system MUST end the
  corresponding Claude Code run on its machine and reflect it as stopped.
- **FR-008**: Stopping a session MUST resolve any pending approvals for it as moot (nothing runs),
  consistent with Phase 2, and MUST be a no-op if the session already ended.

**Control surface & safety**
- **FR-009**: Each session MUST have a control surface presenting its recent activity (from monitoring)
  and the send / start / stop controls.
- **FR-010**: Every control action MUST be recorded to the append-only audit trail (operator, machine,
  session/target, action, timestamp, result).
- **FR-011**: Control actions MUST be carried over the authenticated agent device channel (Phase 0
  identity); an unauthenticated or unenrolled path MUST NOT be able to control a session.
- **FR-012**: Concurrent control actions on the same session MUST be serialized to a single
  unambiguous outcome (first-wins on conflict).

### Key Entities

- **Control Command**: an operator-issued action against a target — type (send-task / start-run /
  stop), target (session or machine+project), payload (instruction/path), status (pending / delivered
  / done / undeliverable), created/decided times, issuing operator; idempotent by command id.
- **Session / Machine** (Phases 0–1): the targets of control; a session may be idle, busy, waiting,
  or stopped.
- **Approval** (Phase 2): pending approvals are mooted when their session is stopped.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An instruction sent to an idle monitored session is delivered and the session begins
  acting on it within 5 seconds, shown as delivered.
- **SC-002**: A new run started on a connected machine appears as a live session in monitoring within
  10 seconds; an invalid start is refused within 5 seconds with a reason and starts nothing.
- **SC-003**: Stopping a running session ends its run and reflects stopped within 10 seconds, and
  mooting of its pending approvals is 100%.
- **SC-004**: Every control action is applied **at most once** (0 double-applies under retry) and is
  **100% audited** (operator, machine, target, action, time, result).
- **SC-005**: 100% of control actions travel over the authenticated device channel; an unauthenticated
  attempt to control a session is refused.
- **SC-006**: A dispatch to a session that never becomes ready is reported undelivered (0 silent
  losses, 0 misdeliveries to another session).

## Assumptions

- **Builds on Phases 0–2**: enrollment/device identity, the authenticated outbound agent channel, the
  shared contract, the session/alert model, and approvals are reused. Control commands travel the same
  authenticated channel as approval decisions (backend → owning agent).
- **Dispatch mechanism & readiness** (confirm in `/speckit.clarify`): the on-machine agent delivers an
  instruction to a session when it is idle/ready (e.g. resuming the session with the new prompt, or
  injecting it between turns). Delivery to a session that is mid-turn or blocked on a prompt is queued
  until ready or reported undeliverable — it is not forced into a blocked prompt. Default:
  **queue-until-ready with an undeliverable timeout**.
- **Starting a run** (clarified: in Phase 3): the agent launches Claude Code on the machine in the
  given project with the initial instruction, as a **persistent** session that stays alive for
  further dispatch/approvals/stop (not a one-shot). Requires Claude Code installed and the path to
  exist on that machine.
- **Stop** (confirm in `/speckit.clarify`): default is a **graceful** stop of the session's run,
  escalating to force if needed; the target is identified via monitoring (process + session).
- **Single operator** (from Phase 2): all machines/projects are in scope; per-machine/project scoping
  is deferred to multi-user. Audit + at-most-once + authenticated channel still apply now.
- **Out of scope**: answering arbitrary free-form questions Claude Code asks mid-turn (Phase 4); this
  phase dispatches *new* instructions and controls lifecycle, it does not answer in-flight prompts
  (approvals excepted, from Phase 2).

## Dependencies

- Phases 0–2 delivered (and, for a real test of the away path + mTLS, deployed).
- Claude Code installed on target machines and launchable/resumable by the on-machine agent.
- The ratified constitution (`.specify/memory/constitution.md`), Principles I–VII (fail-safe,
  least privilege, audit).

## Out of Scope (later phases)

- Remotely **answering arbitrary questions** Claude Code asks mid-turn (Phase 4 — SDK-managed
  sessions).
- **Per-operator machine/project authorization scopes** (multi-user; deferred).
