# Feature Specification: Phase 2 — Remote Approvals & Mobile

**Feature Branch**: `003-remote-approvals`

**Created**: 2026-08-16

**Status**: Draft

**Input**: User description: "Phase 2 Remote Approvals and Mobile: blocking PreToolUse approval flow with fail-safe deny, mobile app (Compose Multiplatform) with push notifications, and mobile monitoring parity"

## Overview

Phase 2 turns ClaudeDriver from *see-and-be-alerted* into *act from anywhere*. Two capabilities:
(1) the operator can **approve or deny a Claude Code tool-permission prompt remotely** — Claude Code
pauses on the prompt and waits for the decision, which the operator gives from the web or phone, and
if no decision arrives in time the system **denies** (fail-safe, never auto-approve); and (2) a
**mobile app** (with **push notifications**) so the operator is reachable and can act while away from
the machine and the network.

Building on Phases 0–1 (enrollment, the authenticated outbound agent channel, the shared contract,
the session/alert model, the web dashboard). Still **not** in scope: answering arbitrary free-form
questions Claude Code asks (that needs a different mechanism — a later phase) and dispatching new
tasks (Phase 3).

## Clarifications

### Session 2026-08-16

_None yet. Two assumptions worth confirming in `/speckit.clarify` before planning: the approval
**timeout** (defaulted to deny after a bounded wait) and **which tool prompts** require approval._

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Approve or deny a permission prompt remotely (Priority: P1)

As the operator, when a monitored Claude Code instance asks permission to run a tool, it pauses and
waits for me; I see the request (machine, project, exactly what it wants to do) and choose Approve or
Deny; my decision reaches the instance and it proceeds or is blocked accordingly.

**Why this priority**: This is the headline capability of the whole product — taking real control of
a waiting agent from afar. Everything else in Phase 2 supports it.

**Independent Test**: Cause a monitored instance to request a tool permission; from the dashboard tap
Approve and confirm the instance proceeds; repeat and tap Deny and confirm the instance is blocked
and does not run the tool.

**Acceptance Scenarios**:

1. **Given** a monitored instance requesting a tool permission, **When** the request arrives, **Then**
   an approval item appears showing the machine, project, and the specific action requested, and the
   instance is shown as waiting on that decision.
2. **Given** a pending approval, **When** the operator approves, **Then** the instance proceeds with
   the tool and the item resolves as approved.
3. **Given** a pending approval, **When** the operator denies, **Then** the instance does not run the
   tool and the item resolves as denied.
4. **Given** a pending approval, **When** the operator has not decided within the allowed time,
   **Then** the system **denies** by default and the item resolves as timed-out-denied (fail-safe).
5. **Given** the decision path is unreachable at decision time, **When** the wait elapses, **Then**
   the outcome is deny — never an accidental approve.

---

### User Story 2 - Be reached by push and act from your phone (Priority: P1)

As the operator, when I am away from my machine (and off its network) I receive a push notification on
my phone the moment an instance needs attention or is waiting on an approval, and I can open the app
and approve/deny or view it there.

**Why this priority**: Remote approval is only useful if the operator actually learns about the wait
while away. Push is the reach mechanism that makes the product work in its intended "away" scenario.

**Independent Test**: With the phone backgrounded and off the local network, cause an instance to
request a permission; confirm a push notification arrives within seconds identifying the request;
tapping it opens the app to that approval, where Approve/Deny works end-to-end.

**Acceptance Scenarios**:

1. **Given** the mobile app is installed and signed in, **When** an instance needs attention or a
   permission approval is raised, **Then** a push notification is delivered to the phone identifying
   the machine, project, and request — even when the app is backgrounded and off the LAN.
2. **Given** a push notification, **When** the operator taps it, **Then** the app opens to the related
   approval/alert.
3. **Given** the operator is signed in on the app, **When** they approve or deny from the phone,
   **Then** the decision takes effect on the instance exactly as it would from the web.
4. **Given** the operator signs out or removes the app, **When** that happens, **Then** that device
   stops receiving pushes.

---

### User Story 3 - Full monitoring on mobile (Priority: P2)

As the operator, the mobile app shows the same live picture as the web dashboard — machines, their
sessions and states, and the alert inbox — so I can keep an eye on the fleet from my phone.

**Why this priority**: Parity makes the phone a first-class surface, not just a push target. It
depends on US2's app + connectivity existing.

**Independent Test**: With sessions active, open the app and confirm machines and their sessions/state
appear and update live, and the alert inbox matches the web.

**Acceptance Scenarios**:

1. **Given** active sessions, **When** the operator opens the app, **Then** machines and their live
   session states are shown and update in real time.
2. **Given** active alerts, **When** the operator opens the inbox, **Then** it matches what the web
   shows, and acknowledging on one surface reflects on the other.

---

### User Story 4 - Approvals are safe, scoped, and accountable (Priority: P3)

As the operator, every approval decision is authenticated, attributable to me, recorded, and
idempotent, and the system always errs toward denial when anything is uncertain.

**Why this priority**: This capability grants an agent permission to run code on a real machine; the
safety and audit properties are what make it trustworthy, even though they are invisible when correct.

**Independent Test**: Approve/deny several requests and confirm each decision is recorded with who,
what, which machine, and the outcome; retry a decision and confirm it does not double-apply; force a
timeout/disconnect and confirm the outcome is deny.

**Acceptance Scenarios**:

1. **Given** any approval decision, **When** it is made, **Then** it is recorded to the audit trail
   with the operator, machine, session, requested action, decision, and timestamp.
2. **Given** a decision is delivered more than once (retry/reconnect), **When** it is applied, **Then**
   it takes effect at most once (idempotent) and does not contradict an already-final outcome.
3. **Given** an approval request whose instance disappears before a decision, **When** that happens,
   **Then** the item resolves as no-longer-applicable and no stale decision can later run the tool.
4. **Given** a decision, **When** it is authorized, **Then** it is allowed only within the operator's
   permitted machines/projects (scope), and unauthorized attempts are refused and recorded.

---

### Edge Cases

- **Decision arrives after timeout-deny** → ignored; the final (denied) outcome stands, and the
  operator is told it already timed out.
- **Instance crashes/stops while waiting** → the approval resolves as moot; a late approve cannot
  run anything.
- **Duplicate approval requests** for the same waiting prompt → coalesced into one pending item.
- **Push token invalid/expired** → dropped and the device re-registers on next sign-in; delivery
  failure never blocks the approval (the web path remains).
- **Operator decides on two surfaces at once** (web + phone) → the first decision wins; the second is
  a no-op with a clear "already decided" indication.
- **Backend↔agent link drops mid-wait** → on the machine the prompt still ultimately resolves to deny
  by timeout (the instance is never left blocked forever, and never auto-approved).
- **Clock differences** → timeout is enforced conservatively so a decision is never accepted after the
  deny deadline.

## Requirements *(mandatory)*

### Functional Requirements

**Remote approval flow**
- **FR-001**: When a monitored Claude Code instance requests permission to use a tool, the system
  MUST surface a pending **approval request** with the machine, project, session, and a clear
  description of the specific action requested, and mark the session as waiting on it.
- **FR-002**: The instance MUST pause on the request until a decision is delivered or the timeout
  elapses; the operator's **approve** MUST let it proceed and **deny** MUST prevent the tool from
  running.
- **FR-003**: If no decision is made within a bounded, configurable time, the system MUST resolve the
  request as **denied** (fail-safe). The system MUST NEVER auto-approve by timeout, error, or
  component failure.
- **FR-004**: A decision MUST take effect on the correct waiting instance and MUST be applied **at
  most once**; late or duplicate decisions MUST NOT change an already-final outcome.
- **FR-005**: If the waiting instance disappears before a decision, the request MUST resolve as
  no-longer-applicable, and no later decision may cause the tool to run.

**Mobile app & push**
- **FR-006**: The system MUST provide a mobile app for iOS and Android through which the operator
  signs in, views alerts/approvals, and approves/denies.
- **FR-007**: The system MUST deliver a **push notification** to the operator's registered devices
  when an attention alert or approval request is raised, reaching the phone even when the app is
  backgrounded and off the machine's local network.
- **FR-008**: A push notification MUST identify the machine, project, and request, and tapping it MUST
  open the app to the corresponding item.
- **FR-009**: The app MUST register/unregister the device for push on sign-in/sign-out, and invalid
  device tokens MUST be pruned; push-delivery failure MUST NOT block the approval path.
- **FR-010**: The mobile app MUST reach the backend from anywhere (not only the local network), and a
  decision made on mobile MUST take effect identically to one made on the web.

**Monitoring parity (mobile)**
- **FR-011**: The mobile app MUST show machines, their live sessions and states, and the alert inbox,
  updating in real time, consistent with the web dashboard.
- **FR-012**: Acknowledging an alert or deciding an approval on one surface MUST be reflected on the
  other.

**Safety, scope & audit**
- **FR-013**: Every approval decision MUST be recorded to the append-only audit trail with operator,
  machine, session, requested action, decision, and timestamp.
- **FR-014**: Decisions MUST be authorized against the operator's permitted machines/projects
  (scope); unauthorized decisions MUST be refused and recorded.
- **FR-015**: The identity/channel carrying a decision to the instance MUST be authenticated
  (reusing the Phase 0 device-identity + authenticated agent channel); an unauthenticated or
  unenrolled path MUST NOT be able to answer a prompt.

### Key Entities

- **Approval Request**: a pending tool-permission decision for a waiting session — machine, project,
  session, requested action (tool + specifics), created time, deadline, and status (pending /
  approved / denied / timed-out-denied / moot).
- **Approval Decision**: the operator's resolution of a request — decision (approve/deny), deciding
  operator, surface (web/mobile), timestamp; idempotent by request.
- **Device (push)**: a registered operator device with a push token and platform, used to deliver
  notifications; tied to the operator and prunable.
- **Alert / Session / Machine**: from Phase 1, now also sources of push and the anchor for approvals.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A tool-permission request from a monitored instance appears as a pending approval
  (web and mobile) within 5 seconds, with the specific action shown.
- **SC-002**: An approve/deny decision reaches and takes effect on the waiting instance within 5
  seconds of the operator's action.
- **SC-003**: 100% of undecided requests resolve as **denied** at the timeout — 0% ever auto-approve
  by timeout, error, disconnect, or crash.
- **SC-004**: A push notification for a new attention/approval reaches a backgrounded, off-network
  phone within 10 seconds, and tapping it opens the correct item.
- **SC-005**: A decision is applied at most once — duplicate/late deliveries cause 0 double-applies
  and never override a final outcome.
- **SC-006**: 100% of decisions are audited (operator, machine, session, action, decision, time) and
  refused when outside the operator's scope.
- **SC-007**: Mobile and web show the same machines/sessions/alerts, and an acknowledgement/decision
  on one appears on the other within 5 seconds.

## Assumptions

- **Builds on Phases 0–1**: enrollment/device identity, the authenticated outbound agent channel, the
  shared contract, the session/alert model, and the web dashboard are reused.
- **Approval transport preserves outbound-only** (Constitution Principle IV): Claude Code's blocking
  permission hook posts to the on-machine agent, which forwards the request over its existing
  authenticated outbound channel and returns the operator's decision back to the waiting hook. The
  backend is never posted to directly, and nothing on the machine accepts external inbound.
- **Approval timeout** defaults to a bounded wait (e.g. a few minutes) after which the outcome is
  **deny**; the exact value is configurable and worth confirming in `/speckit.clarify`.
- **Scope of prompts requiring approval**: tool-permission prompts Claude Code raises; the exact set
  (all tools vs a configurable subset) is a default to confirm in `/speckit.clarify`.
- **Push may use a cloud delivery service** (the mobile push networks and a dispatcher); this is the
  one place a third-party path is expected, consistent with the earlier hosting decision.
- **Away-from-network reach**: the mobile app talks to the internet-reachable (AWS-hosted) backend
  directly; no LAN dependency (matches the Phase 0 remote-by-default decision).
- **Out of scope**: answering arbitrary free-form questions Claude Code asks (needs a different,
  later mechanism), and dispatching new tasks/messages to sessions (Phase 3).

## Dependencies

- Phases 0–1 delivered (and, for a true test of the away path + mTLS, deployed).
- Claude Code's blocking permission-prompt hook mechanism available to the on-machine agent.
- Mobile push delivery (platform push networks + a backend dispatcher) and app-store/dev signing for
  the mobile app.
- The ratified constitution (`.specify/memory/constitution.md`), Principles I–VII (especially I:
  fail-safe, least privilege, audit).

## Out of Scope (later phases)

- Remotely **answering arbitrary questions** Claude Code asks (Phase 4 spike — needs SDK-managed
  sessions).
- **Task dispatch** / sending new work or messages to sessions (Phase 3).
