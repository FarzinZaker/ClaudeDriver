# Feature Specification: Phase 1 — Monitoring MVP

**Feature Branch**: `002-process-monitoring`

**Created**: 2026-08-16

**Status**: Draft

**Input**: User description: "Phase 1 Monitoring MVP: per-machine agent detects Claude Code processes via OSHI, installs Claude Code HTTP hooks, backend session registry classifies needs-attention, dashboard shows live machines/sessions and an alert inbox"

## Overview

Phase 1 turns the Phase 0 walking skeleton into a working product: **see every Claude Code instance
across your enrolled machines, and get alerted the moment one needs your attention.** Building on the
Phase 0 spine (enrolled machines, authenticated outbound agent connection, operator sign-in, the
shared contract, and the audit log), the agent now **detects Claude Code processes** on its machine
and **installs Claude Code hooks** that report activity; the backend maintains a **session registry**
and distinguishes "needs attention" from routine events; and the operator dashboard shows **live
machine → session state and an alert inbox**.

Phase 1 is **observe-and-alert only**. Answering prompts, approving tool use, dispatching tasks, and
mobile push notifications are explicitly later phases — Phase 1 makes the fleet *visible* and makes
attention-needed states *impossible to miss* while at the dashboard.

## Clarifications

### Session 2026-08-16

_None yet. One assumption (which events count as "needs attention") is flagged in Assumptions and is
a good candidate for `/speckit.clarify` before planning._

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See live Claude Code instances per machine (Priority: P1)

As the operator, I can open the dashboard and see, for every enrolled machine, the Claude Code
instances currently running on it — which project each is working in — updating live as instances
start and stop.

**Why this priority**: Visibility is the foundational product value ("see everything"). Nothing else
in Phase 1 is meaningful until running instances are reliably detected and shown.

**Independent Test**: On an enrolled machine, start a Claude Code instance in a project directory and
confirm it appears in the dashboard within seconds with the correct project; stop it and confirm it
disappears (or is marked ended).

**Acceptance Scenarios**:

1. **Given** an enrolled, connected machine, **When** a Claude Code instance starts in a project,
   **Then** it appears under that machine in the dashboard with its project/working directory.
2. **Given** a shown running instance, **When** it exits, **Then** the dashboard reflects that it is
   no longer running.
3. **Given** several Claude Code instances on one machine, **When** the operator views that machine,
   **Then** all of them are listed distinctly.
4. **Given** a machine goes offline (agent disconnects), **When** the operator views it, **Then** its
   instances are shown as stale/last-known rather than silently vanishing or appearing live.

---

### User Story 2 - Be alerted when an instance needs attention (Priority: P1)

As the operator, I am alerted in an inbox when any Claude Code instance is waiting on me — a
permission prompt, a question, or an idle wait — with enough context (machine, project, and what is
being asked) to know it needs action, and the alert clears when the wait ends.

**Why this priority**: This is the headline capability — surfacing prompts and questions so the
operator does not have to babysit terminals. It is the reason the product exists.

**Independent Test**: In a monitored Claude Code instance, trigger a permission prompt (or a
question); confirm an alert appears in the inbox within seconds identifying the machine, project, and
the request; answer it at the terminal and confirm the alert auto-resolves.

**Acceptance Scenarios**:

1. **Given** a monitored instance, **When** it starts waiting for the operator (permission prompt /
   question / idle prompt), **Then** an "attention needed" alert appears in the inbox with machine,
   project, and a summary of what is being asked.
2. **Given** an attention alert, **When** the underlying wait is resolved (answered at the terminal or
   the instance stops), **Then** the alert is marked resolved and leaves the active inbox.
3. **Given** routine activity (a tool ran, a turn finished), **When** those events arrive, **Then**
   they update session state but do NOT raise an attention alert (no noise).
4. **Given** an instance finishes its work and stops, **When** that completion is reported, **Then**
   the operator sees the instance as finished (a distinct, low-urgency signal).

---

### User Story 3 - Inspect a session's state and recent activity (Priority: P2)

As the operator, I can open any monitored session and see its current state (running, waiting for me,
finished, or stopped), the project it is in, when it was last active, and a short history of its
recent events — so I understand the situation before acting (in a later phase).

**Why this priority**: Context turns an alert into an informed decision. It depends on US1/US2 event
flow existing, and it prepares the ground for the control capabilities of later phases.

**Independent Test**: Open a session that is waiting, observe its state and the recent event that
caused the wait; resolve it at the terminal and watch the state transition to running/finished with
the history updated.

**Acceptance Scenarios**:

1. **Given** a monitored session, **When** the operator opens it, **Then** its current state, project,
   and last-active time are shown.
2. **Given** a session that changes state, **When** new events arrive, **Then** the state and a recent
   event history update live.
3. **Given** a session with no recent events, **When** viewed, **Then** it is shown with a clear
   unknown/stale indication rather than a misleading "active" state.

---

### User Story 4 - Monitoring is installed and removed safely (Priority: P3)

As the operator, when a machine is enrolled its agent sets up Claude Code monitoring automatically
and safely — without leaking secrets, without clobbering my own Claude Code configuration, and
reversibly — and when a machine is disabled the monitoring is cleanly removed.

**Why this priority**: The system modifies configuration on developer machines; doing so safely,
idempotently, and reversibly is required for trust, even though it is invisible when it works.

**Independent Test**: Enroll a machine and confirm monitoring is active and its reporting is
authenticated and scoped; re-run setup and confirm no duplicate/broken configuration; disable the
machine and confirm monitoring configuration is removed and pre-existing user configuration is
preserved.

**Acceptance Scenarios**:

1. **Given** a newly enrolled machine, **When** the agent sets up monitoring, **Then** Claude Code
   reports activity to the system over an authenticated, scoped channel, and no secret is written in
   plaintext to a shared/committed location.
2. **Given** monitoring already set up, **When** setup runs again, **Then** it is idempotent (no
   duplicated or conflicting configuration) and preserves the user's unrelated configuration.
3. **Given** a machine is disabled or its agent removed, **When** teardown runs, **Then** the
   monitoring configuration this system added is removed.

---

### Edge Cases

- **Claude Code not installed / never run** on a machine → the machine shows as monitored with zero
  instances, not an error.
- **Hook/event delivery path temporarily unreachable** → events are buffered and delivered on
  recovery; a gap is reflected as stale state, never as false "healthy." Monitoring MUST NOT block or
  slow the Claude Code instance itself.
- **A process is detected but no session events have arrived yet** (or vice-versa) → reconciled into a
  single session view with an explicit "starting/unknown" state rather than duplicates.
- **Rapid or duplicate events** → coalesced so the inbox and state do not flicker or flood.
- **An attention alert whose instance disappears** (crash/kill) before resolution → auto-resolved with
  the reason noted.
- **Multiple instances in the same project on one machine** → tracked as distinct sessions.
- **Machine offline** → its sessions marked stale with a last-seen time; alerts already raised remain
  visible with a staleness note.

## Requirements *(mandatory)*

### Functional Requirements

**Process detection (agent)**
- **FR-001**: The agent MUST detect Claude Code processes on its machine, identifying each by process
  identity and the project/working directory it is running in.
- **FR-002**: The agent MUST track process lifecycle (started, still running, exited) and report
  changes to the backend over its existing authenticated outbound connection.
- **FR-003**: Process reporting MUST NOT require any inbound connection to the machine and MUST NOT
  materially affect the monitored Claude Code process's performance.

**Activity events & needs-attention (agent + backend)**
- **FR-004**: The agent MUST set up Claude Code to report activity events (at minimum: an instance
  waiting for the operator, and an instance finishing) to the system.
- **FR-005**: Activity-event delivery MUST be authenticated and MUST NOT block or delay the Claude
  Code instance; if the system is unreachable the instance MUST continue unaffected.
- **FR-006**: The backend MUST classify each incoming event as **needs-attention** (a permission
  prompt, a question, or an idle wait for the operator) or **informational** (routine progress), per
  a configurable mapping.
- **FR-007**: The system MUST correlate process detection and activity events for the same instance
  into a single session record.

**Session registry & state (backend)**
- **FR-008**: The backend MUST maintain a session registry with, per session: owning machine,
  project, current state (running / waiting-for-operator / finished / stopped / unknown-stale), last
  activity time, and a bounded recent-event history.
- **FR-009**: Session state MUST transition from reported events and MUST become **stale/unknown**
  when its machine is offline or events stop arriving beyond a threshold — never a misleading
  "active."

**Alerts (backend)**
- **FR-010**: When a session enters a needs-attention state, the system MUST raise an alert carrying
  the machine, project, session, and a human-readable summary of what is being asked.
- **FR-011**: An alert MUST auto-resolve when its underlying wait ends (resolved at the terminal or
  the session stops) and MUST support the operator marking it read/acknowledged.
- **FR-012**: Alerts MUST be de-duplicated and coalesced so repeated/rapid events do not flood the
  inbox.
- **FR-013**: The system MUST record alert lifecycle (raised, resolved, acknowledged) to the
  append-only audit trail.

**Operator dashboard**
- **FR-014**: The dashboard MUST show each enrolled machine with its live Claude Code sessions and
  their state, updating in real time, and MUST indicate offline/stale machines.
- **FR-015**: The dashboard MUST present an **alert inbox** of active needs-attention items, sorted by
  urgency/recency, from which the operator can open the related session.
- **FR-016**: The dashboard MUST let the operator open a session to view its state, project,
  last-active time, and recent event history.

**Safe setup/teardown (agent)**
- **FR-017**: Monitoring setup MUST be idempotent and MUST NOT overwrite or remove the user's
  unrelated Claude Code configuration.
- **FR-018**: Any credential used for activity-event reporting MUST NOT be written in plaintext to a
  shared or version-controlled location.
- **FR-019**: Disabling a machine (or removing its agent) MUST remove the monitoring configuration the
  system added.

### Key Entities

- **Machine**: an enrolled computer (from Phase 0); now also has an online/offline + last-seen status
  and a set of monitored sessions.
- **Session**: one Claude Code instance being monitored — machine, project/working directory, state,
  last-active time, recent events. Correlates a detected process with its activity events.
- **Activity Event**: a reported occurrence for a session (waiting-for-operator, finished, routine
  progress) with a type and timestamp; classified needs-attention or informational.
- **Alert**: an operator-facing item raised from a needs-attention session state; has status (active /
  acknowledged / resolved), urgency, context summary, and timestamps.
- **Attention Classification**: the configurable mapping from event kinds to needs-attention vs
  informational.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Claude Code instance starting or stopping on an enrolled, connected machine is
  reflected in the dashboard within 5 seconds.
- **SC-002**: When an instance begins waiting for the operator, an attention alert appears in the
  inbox within 5 seconds, identifying the machine, project, and what is being asked.
- **SC-003**: 100% of needs-attention waits raise exactly one active alert (no misses, no duplicate
  flooding), and every alert auto-resolves within 5 seconds of its wait ending.
- **SC-004**: 0% of routine/informational events raise an attention alert (no false alarms).
- **SC-005**: When a machine goes offline, its sessions are shown as stale within 30 seconds and never
  as falsely active.
- **SC-006**: Setting up monitoring twice on the same machine produces identical configuration (no
  duplicates), and teardown removes 100% of system-added configuration while leaving user
  configuration intact.
- **SC-007**: Monitoring adds no perceptible latency to the monitored Claude Code instance, and an
  unreachable backend never blocks it.
- **SC-008**: The system correctly attributes and displays activity across the target small fleet
  (up to 10 machines / 25 concurrent sessions from Phase 0).

## Assumptions

- **Builds on Phase 0**: enrollment/device identity, the authenticated outbound agent connection, the
  shared contract, operator sign-in, and the audit log already exist and are reused.
- **Activity events flow through the local agent**: Claude Code reports to the on-machine agent, which
  forwards over its existing authenticated outbound channel — preserving outbound-only (Constitution
  Principle IV) rather than opening the backend to direct per-machine posting.
- **Needs-attention default mapping** (revisit in `/speckit.clarify`): a Claude Code instance
  *waiting for the operator* (permission prompt / question / idle prompt) is **needs-attention**;
  session start, tool activity, and turn completion are **informational** (completion shown as a
  distinct low-urgency signal). This mapping is configurable.
- **Observe-and-alert only for Phase 1**: no answering prompts, approving tool use, or dispatching
  tasks (later phases); the dashboard alert inbox is the only surface (no mobile push yet).
- **Detection is best-effort by process + events**: reading another process's working directory may
  require the agent to run with sufficient privilege; where unavailable, project is shown as
  best-known.
- **Fleet scale** remains small (≤10 machines / ≤25 sessions).

## Dependencies

- Phase 0 delivered and deployed (agent connection, enrollment, contract, audit, dashboard shell).
- Claude Code installed on monitored machines and its hook/reporting mechanism available to the agent.
- The ratified constitution (`.specify/memory/constitution.md`), Principles I–VII.

## Out of Scope (later phases)

- Remote **approvals** of tool-use permission prompts (Phase 2).
- **Task dispatch** / sending messages to sessions (Phase 3).
- Remotely **answering arbitrary questions** (Phase 4 spike).
- **Mobile app and push notifications** (Phase 2).
