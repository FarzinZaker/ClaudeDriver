# Feature Specification: Phase 0 — Foundations & Contracts

**Feature Branch**: `001-phase-0-foundations`

**Created**: 2026-08-16

**Status**: Draft

**Input**: User description: "Phase 0 Foundations and Contracts: monorepo, shared protocol, AWS infra baseline, security/enrollment architecture spike, and CI for ClaudeDriver" (hosting retargeted to AWS during clarification)

## Overview

Phase 0 delivers the **walking skeleton** every later phase plugs into: a deployable, hardened
backend an authorized operator can reach; a per-machine agent that establishes a mutually-trusted,
outbound-only connection to it; a single shared contract that all components speak; and an automated
quality gate that keeps the system honest. It intentionally delivers **no product features yet**
(no process monitoring, no alerts, no approvals) — its value is a secure, deployable spine that
proves the riskiest foundations (trust, deployment, contract, secrets) end-to-end before feature
work begins.

## Clarifications

### Session 2026-08-16

- Q: How should the operator sign in? → A: Self-hosted passkeys / WebAuthn, with no external identity
  provider. (Related decision: hosting is **AWS**, and all AWS resources must be grouped/tagged so
  project billing is clear.)
- Q: How complete should machine enrollment be in Phase 0? → A: Working minimal enrollment — a real
  walking skeleton that genuinely rejects unenrolled/forged agents (not a design-only spike).
- Q: What fleet size should the foundations be designed and cost-sized for? → A: Small — up to 10
  machines and up to 25 concurrent sessions.
- Q: What acts as the operator client in Phase 0 to prove the end-to-end message? → A: A minimal web
  status page that renders live status and the sample message.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reach a secured, deployed backend (Priority: P1)

As the operator, I can deploy the ClaudeDriver backend to the hosting environment and reach an
authenticated status surface from anywhere, while anyone unauthenticated is refused — and no secret
value ever lives in the source repository.

**Why this priority**: Nothing else can be built, demonstrated, or trusted until the system deploys
and enforces "authenticated operators only." This is the security and delivery bedrock.

**Independent Test**: Deploy from a clean checkout; confirm an authenticated operator sees live
status while an unauthenticated request is rejected; grep the repository history and confirm no
secret values are present.

**Acceptance Scenarios**:

1. **Given** a clean checkout and configured secrets, **When** the operator runs the documented
   deploy, **Then** the backend is reachable at its hosted address and reports healthy status.
2. **Given** the deployed backend, **When** an authenticated operator requests status, **Then** the
   current system state is returned.
3. **Given** the deployed backend, **When** an unauthenticated or improperly-authenticated request
   arrives, **Then** it is refused and the attempt is recorded.
4. **Given** the repository at any commit, **When** its contents and history are scanned, **Then**
   no live secret (token, key, connection string, certificate key) is present.

---

### User Story 2 - Enroll a machine and establish trusted identity (Priority: P1)

As the operator, I can enroll a new machine so its agent obtains a unique device identity, after
which the agent dials out to the backend and is recognized as that machine — while any agent that
was never enrolled, or presents a forged/expired identity, is rejected.

**Why this priority**: This is the core trust primitive of an RCE control plane (Constitution
Principles I & IV). Getting outbound-only, per-device, operator-approved identity right is the
single most important foundation; every later capability rides on it.

**Independent Test**: Run an unenrolled agent and confirm the backend rejects it; complete an
operator-approved enrollment, restart the agent, and confirm it connects and appears as a known
machine; tamper with or expire the identity and confirm rejection.

**Acceptance Scenarios**:

1. **Given** a fresh machine whose agent has no identity, **When** the agent attempts to connect,
   **Then** the backend refuses the connection and no machine is registered.
2. **Given** an operator-approved enrollment, **When** the agent completes enrollment, **Then** it
   receives a unique device identity bound to that machine.
3. **Given** an enrolled agent, **When** it starts, **Then** it establishes an outbound,
   mutually-authenticated connection and appears in the system as that specific machine.
4. **Given** an agent presenting a forged, revoked, or expired identity, **When** it attempts to
   connect, **Then** the connection is refused and the attempt is recorded.
5. **Given** developer machines behind normal firewalls, **When** an agent connects, **Then** it
   requires no inbound port or firewall change on the machine.

---

### User Story 3 - Exchange a versioned message over the shared contract (Priority: P2)

As a developer, I can send a message defined once in the shared contract from the agent, through the
backend, to an operator client, and have every component interpret it identically — with a version
mismatch detected and reported at connection time rather than corrupting data.

**Why this priority**: Proves the single-source-of-truth protocol (Principle III) and version
negotiation before any real message types exist, preventing a whole class of drift bugs. It depends
on US1/US2 being in place to have endpoints to exchange between.

**Independent Test**: Emit a sample contract message from the agent; observe it arrive unchanged and
correctly typed at an operator client; then connect a component advertising an incompatible contract
version and confirm the mismatch is detected and refused with a clear reason.

**Acceptance Scenarios**:

1. **Given** the shared contract, **When** the agent sends a sample message, **Then** the backend
   and the operator client interpret its fields identically without any component-local redefinition.
2. **Given** two components on the same contract version, **When** they connect, **Then** the
   connection is accepted.
3. **Given** two components on incompatible contract versions, **When** they connect, **Then** the
   mismatch is detected at connection time and the connection is refused with a clear reason.

---

### User Story 4 - Automated quality gate blocks unsafe changes (Priority: P3)

As a developer, every change is automatically built, tested, secret-scanned, and checked against the
constitution before it can merge, so an unsafe change (a leaked secret, a broken contract, a failing
test) is rejected without manual vigilance.

**Why this priority**: Locks in Principle II and the secret-hygiene rule so the foundations don't
erode. It is P3 because the earlier stories deliver demonstrable value first, but it must land within
Phase 0.

**Independent Test**: Open a change that introduces a plaintext secret and confirm the gate fails it;
open a change that breaks a contract or a test and confirm the gate fails it; open a clean change and
confirm it passes.

**Acceptance Scenarios**:

1. **Given** the quality gate, **When** a change introduces a plaintext secret, **Then** the gate
   fails and blocks the merge.
2. **Given** the quality gate, **When** a change breaks the build or a test, **Then** the gate fails.
3. **Given** the quality gate, **When** a compliant change is submitted, **Then** the gate passes.

---

### Edge Cases

- **Agent connects before enrollment** → refused; no partial/implicit registration is created.
- **Backend unreachable when the agent starts or mid-session** → the agent retries with backoff and
  does not busy-loop, spam, or crash; it reconnects automatically when the backend returns.
- **Required secret missing/blank at deploy** → deployment fails fast with a clear message rather
  than starting in an insecure or half-configured state.
- **Contract version mismatch** → refused at connection with an actionable reason, never silently
  coerced.
- **Duplicate or repeated enrollment of the same machine** → resolves to a single machine identity
  without creating duplicate registrations.
- **Revoked machine or operator** → subsequent connections/requests are refused promptly.
- **Clock skew / expired identity** → treated as untrusted (refused), consistent with fail-safe.

## Requirements *(mandatory)*

### Functional Requirements

**Deployment & configuration**
- **FR-001**: The system MUST be deployable to the hosting environment from a clean checkout using
  documented steps.
- **FR-002**: The system MUST source all secrets and environment-specific values from an external
  secret/configuration source at deploy/run time, never from committed files.
- **FR-003**: The system MUST fail deployment/startup with a clear error when a required secret or
  configuration value is missing or empty.
- **FR-004**: The repository (working tree and history) MUST contain no live secret values; a
  template of required configuration keys MUST be provided instead.

**Operator access**
- **FR-005**: The system MUST expose an operator-facing status surface reporting current system and
  connection state.
- **FR-006**: The system MUST require operator authentication for every non-public surface and MUST
  refuse unauthenticated or improperly-authenticated access. Operator authentication MUST be
  self-hosted, phishing-resistant (passkeys / WebAuthn), and MUST NOT depend on an external identity
  provider.
- **FR-007**: The system MUST record authentication failures and refused access attempts.

**Machine enrollment & identity**
- **FR-008**: The system MUST provide an operator-approved enrollment flow that admits a new machine;
  no machine may join without explicit operator approval.
- **FR-009**: Each enrolled machine MUST receive a unique cryptographic device identity bound to that
  machine.
- **FR-010**: The agent MUST establish connectivity by initiating an OUTBOUND connection to the
  backend and MUST NOT require any inbound port or firewall change on the machine.
- **FR-011**: Agent↔backend connections MUST be mutually authenticated; the backend MUST reject any
  agent lacking a valid, current device identity (unenrolled, forged, expired, or revoked).
- **FR-012**: The system MUST allow the operator to revoke a machine's identity, after which that
  machine's subsequent connections are refused.
- **FR-013**: Network-level discovery, if present at all, MUST NOT by itself grant membership or
  trust.

**Shared contract**
- **FR-014**: All inter-component messages MUST be defined in a single shared contract consumed by
  the backend, the agent, and operator clients; no component may maintain a divergent copy of a
  shared type.
- **FR-015**: The contract MUST carry an explicit version; components MUST negotiate compatibility at
  connection time and refuse incompatible peers with a clear reason.
- **FR-016**: The system MUST demonstrate a sample message traveling agent → backend → a minimal web
  status page (the Phase 0 operator client) and being interpreted identically by each.

**Resilience & auditability (foundational)**
- **FR-017**: The agent MUST reconnect automatically using backoff after a lost or refused-then-fixed
  connection, without busy-looping.
- **FR-018**: The system MUST record consequential foundational events (enrollment, revocation,
  connection accepted/refused, auth failure) to an append-only audit record.

**Automated quality gate**
- **FR-019**: Every proposed change MUST automatically run build, tests, and a secret scan before it
  can merge, and MUST be blocked on any failure.
- **FR-020**: The quality gate MUST fail a change that introduces a plaintext secret or breaks the
  shared contract or a test.

**Hosting & cost attribution**
- **FR-021**: Every hosted resource provisioned for the system MUST be grouped and tagged under a
  single project identifier so that total project cost is clearly and separately attributable, with a
  budget/cost alert configured.
- **FR-022**: Provisioning MUST be reproducible (infrastructure-as-code) so the grouping/tagging is
  enforced automatically rather than applied by hand.

### Key Entities

- **Operator**: the authorized human user; authenticates to access surfaces and approve enrollments.
  (Single operator in this phase, treated as high-privilege.)
- **Machine**: an enrolled developer computer (Windows or macOS) identified by a unique device
  identity; has a lifecycle (pending → enrolled → revoked).
- **Agent**: the per-machine participant that holds the outbound connection and represents its
  machine to the backend.
- **Device Identity / Enrollment**: the cryptographic credential and its issuance record binding an
  agent to a machine, including validity and revocation state.
- **Connection/Session**: a live, authenticated link between a participant and the backend, with
  status the operator can observe.
- **Contract Version**: the negotiated version of the shared message contract for a connection.
- **Audit Event**: an append-only record of a consequential action (who/what/when/result).
- **Configuration/Secret**: an externally-sourced value required to run; never committed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A person following the documented steps can deploy the system from a clean checkout and
  reach a healthy authenticated status surface in under 30 minutes, with zero secrets taken from the
  repository.
- **SC-002**: 100% of unauthenticated or improperly-authenticated access attempts are refused, and
  each is recorded.
- **SC-003**: A machine goes from "not enrolled" to "connected and recognized" through the
  operator-approved flow in under 10 minutes, requiring no inbound firewall change on the machine.
- **SC-004**: 100% of connection attempts from unenrolled, forged, expired, or revoked identities are
  refused.
- **SC-005**: Every shared message type has exactly one definition consumed by all components (zero
  duplicated/divergent definitions), and a contract-version mismatch is refused 100% of the time
  rather than silently accepted.
- **SC-006**: A sample message is delivered agent → backend → operator client and interpreted
  identically by each component.
- **SC-007**: The automated quality gate blocks 100% of changes that introduce a plaintext secret,
  break the contract, or break a test, and passes compliant changes.
- **SC-008**: After an induced backend outage, a running agent reconnects automatically within a
  bounded time once the backend returns, without manual intervention.
- **SC-009**: The foundations sustain the target small fleet (up to 10 machines / 25 concurrent
  sessions) within the stated minimal cost envelope, and the project's total hosting cost is
  reportable as a single, separately-attributable figure.

## Assumptions

- **Single operator, high-risk posture**: exactly one authorized operator in this phase, treated as
  a fleet administrator; multi-user roles are deferred to a later phase.
- **Hosting**: the backend and all services are hosted on **AWS** and are internet-reachable; the
  system is therefore hardened to stand safe on the public internet (strong operator auth +
  mutually-authenticated agents), and no separate VPN/tunnel is assumed for reachability.
- **Operator authentication method** (resolved): self-hosted passkeys / WebAuthn, with no external
  identity provider.
- **Fleet scale**: the foundations are designed and cost-sized for a small fleet — up to 10 machines
  and up to 25 concurrent sessions — and may grow later.
- **Operator client for Phase 0**: a minimal web status page serves as the operator client that
  renders live status and the demonstrated sample message; the full web dashboard is a later phase.
- **Cost-consciousness & attribution**: infrastructure uses minimal, scale-to-idle resources; any
  always-on cost is justified in the plan; and all resources are grouped/tagged under one project
  identifier with a budget so project billing is clear (Constitution Principle VII).
- **Machines have outbound internet access**; no inbound reachability to developer machines is assumed
  or required.
- **Enrollment scope for Phase 0**: a minimal but *working* operator-approved enrollment and
  mutually-authenticated connection (the walking skeleton). Rich enrollment UX, bulk operations, and
  policy management are deferred to later phases.
- **Explicitly out of scope for Phase 0** (delivered in later phases): detecting/monitoring Claude
  Code processes, installing Claude Code hooks, alerts and notifications, remote approvals, task
  dispatch, the full web dashboard, and the mobile app + push. Phase 0 provides only the spine these
  attach to.
- **Contract transport**: a single persistent, bidirectional channel per participant is assumed as
  the basis for later real-time delivery (details chosen in the plan).

## Dependencies

- Availability of the operator's AWS account and an AWS-managed secret store (Secrets Manager / SSM).
- Ability to run a small long-lived background process on each developer machine (installed later;
  Phase 0 only requires it can connect outbound).
- The ratified project constitution (`.specify/memory/constitution.md`), whose Principles I–VII this
  spec must satisfy.
