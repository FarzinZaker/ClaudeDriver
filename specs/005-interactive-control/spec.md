# Feature Specification: Phase 4 — Full Interactive Control & Hardening

**Feature Branch**: `005-interactive-control`

**Created**: 2026-08-16

**Status**: Draft

**Input**: User description: "Phase 4 Full Interactive Control and Hardening: run sessions under the Claude Agent SDK so the operator can answer arbitrary mid-turn questions, view full transcripts with history and search, and a security/cost hardening pass"

## Overview

Phase 4 completes the interactive story and hardens the system. The gap left by Phases 1–3 is that
the operator can be *alerted* to a question and can *approve tool use* or *dispatch new work*, but
cannot **answer an arbitrary free-form question** a session poses mid-turn. Phase 4 closes that by
running sessions in a **managed mode** (under the Claude Agent SDK) where the operator can answer
anything the session asks, see the **full transcript**, search **history**, and where a **hardening
pass** tightens security and cost. It builds on Phases 0–3 (enrollment, the authenticated agent
channel, monitoring, alerts, approvals, remote control).

> **Feasibility note (committed full build).** The Claude Agent SDK is Python/TypeScript; the agent is
> Kotlin/JVM. Managed mode therefore drives an SDK **companion runtime** on the machine, bridged to the
> agent over a defined protocol. Phase 4 commits to building this integration in full. It remains an
> **additional** mode — hook-based monitoring and blocking approvals (Phases 1–2) stay for sessions not
> run in managed mode. Note: validating the *real* SDK end-to-end needs a Claude Code + SDK runtime and
> API access not present in the build environment, so that final validation is a deploy/CI step; the
> bridge protocol is otherwise exercised with a fake companion.

## Clarifications

### Session 2026-08-17

- Q: How far to take SDK-managed mode? → A: **Full build now** — commit to the complete SDK-managed
  integration this phase (the Kotlin agent driving an Agent-SDK companion runtime over a defined
  bridge). **Honest caveat**: validating the *real* SDK end-to-end needs a Claude Code + SDK runtime
  and API access not present in the build environment, so that final validation is a deploy/CI-with-key
  step; the bridge protocol is exercised with a fake companion in the meantime.
- Q: History & search scope? → A: **Full** — managed-session transcript + browse past sessions +
  search across them (US3 complete).
- Q: Hardening scope? → A: **Full pass** — credential rotation/revocation flows + threat-model
  checklist + cost review (FR-010/011/012).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Answer an arbitrary question a session asks (Priority: P1)

As the operator, when a managed session asks me a free-form question (not just a yes/no permission),
I see the question and type an answer; my answer is delivered to the session and it continues with it.

**Why this priority**: This is the one interaction the product could not do before — the capstone of
"full remote control." Everything else in Phase 4 supports or hardens it.

**Independent Test**: In a managed session, cause it to ask a free-form question; confirm the question
appears for the operator, type an answer, and confirm the session receives it and proceeds using it.

**Acceptance Scenarios**:

1. **Given** a managed session that poses a free-form question, **When** the question is raised,
   **Then** the operator sees it (machine, project, the question text) and the session is shown as
   waiting on the answer.
2. **Given** a pending question, **When** the operator submits an answer, **Then** the answer is
   delivered to the session and it continues; the item resolves as answered.
3. **Given** a pending question, **When** the operator declines/cancels instead, **Then** the session
   is told no answer was given and handles it (e.g. stops or takes a safe default) — never a silent
   hang and never a fabricated answer.

---

### User Story 2 - Run a session in managed mode (Priority: P1)

As the operator, I can run a session in **managed mode** so it is fully interactive-controllable —
I can answer any question, approve tool use, and see the full conversation — distinct from the
lighter hook-based monitoring used for other sessions.

**Why this priority**: Managed mode is the capability that enables US1; it must exist and be clearly
distinguished from monitored-only sessions.

**Independent Test**: Start a session in managed mode; confirm it is marked managed, its full
conversation is visible, and both permission prompts and free-form questions are answerable remotely.

**Acceptance Scenarios**:

1. **Given** an enrolled, connected machine, **When** the operator starts a managed session, **Then**
   it runs under management and is marked as managed in monitoring.
2. **Given** a managed session, **When** it needs any operator input (permission or question), **Then**
   that input is answerable remotely and the session waits for it (consistent fail-safe: no input is
   never treated as approval).
3. **Given** a machine or environment where managed mode is unavailable, **When** the operator tries
   to start a managed session, **Then** it is refused with a clear reason and falls back to
   monitored-only (Phases 1–3) rather than failing opaquely.

---

### User Story 3 - Full transcript, history & search (Priority: P2)

As the operator, I can read the full conversation of a managed session, browse the history of past
sessions, and search across them, so I can review and find what happened.

**Why this priority**: Context and recall make the control usable at scale; it depends on managed
mode producing a full transcript.

**Independent Test**: Open a managed session and read its full transcript live; then browse a past
session and search for a term and find it.

**Acceptance Scenarios**:

1. **Given** a managed session, **When** the operator opens it, **Then** the full conversation
   (messages, tool activity, questions/answers) is shown and updates live.
2. **Given** past sessions, **When** the operator searches for a term, **Then** matching sessions and
   locations are returned.
3. **Given** the transcript, **When** it is displayed, **Then** it is reconstructed from a stable,
   supported source — not by parsing an unsupported internal file format.

---

### User Story 4 - Security & cost hardening pass (Priority: P3)

As the operator, the system undergoes a hardening pass so credentials rotate and revoke cleanly, the
hosted footprint is reviewed for cost, and the remote-code-execution surface is reviewed against the
threat model — so the whole system is safe and economical to run.

**Why this priority**: The system grants RCE over a fleet; a deliberate hardening pass is essential
before relying on it, even though it is invisible when done right.

**Independent Test**: Rotate and revoke a credential and confirm old ones stop working; review the
cost report against the target; run through the threat-model checklist and confirm each control is
present.

**Acceptance Scenarios**:

1. **Given** a device or operator credential, **When** it is rotated or revoked, **Then** the old
   credential stops working promptly and the new one works.
2. **Given** the hosted deployment, **When** its cost is reviewed, **Then** it is within the stated
   small-fleet envelope, with any always-on cost justified.
3. **Given** the threat model, **When** the hardening checklist is reviewed, **Then** each required
   control (mTLS, fail-safe, least privilege, audit, no-internet-exposure-without-hardening) is
   confirmed present, and gaps are logged.

---

### Edge Cases

- **Managed SDK runtime crashes mid-session** → the session is reported ended/errored; any pending
  question resolves as unanswered (never a fabricated answer); the operator is told.
- **Operator never answers a question** → the session waits (no ClaudeDriver timeout, consistent with
  the Phase 2 approval decision); if the runtime's own limit forces resolution, the session is told no
  answer was given (safe default), never a fabricated one.
- **A very long transcript** → paginated/streamed, never dropped or truncated silently.
- **Search over many sessions** → bounded/paged; a slow search never blocks live control.
- **Managed and monitored modes on the same machine** → both coexist; a session is one or the other,
  clearly labelled.
- **Answer submitted after the question already resolved** (crash/cancel) → ignored; the final state
  stands and the operator is told it was already resolved.

## Requirements *(mandatory)*

### Functional Requirements

**Answer arbitrary questions (managed mode)**
- **FR-001**: When a managed session poses a free-form question, the system MUST surface it to the
  operator (machine, project, question text) and mark the session as waiting on the answer.
- **FR-002**: The operator MUST be able to submit a free-form answer that is delivered to the session,
  which then continues; the system MUST NEVER fabricate or auto-generate an answer.
- **FR-003**: The operator MUST be able to decline/cancel a question; the session MUST be told no
  answer was given and handle it safely (stop or safe default), never hang silently.
- **FR-004**: A question and its answer MUST correlate to exactly the intended session, and an answer
  MUST be applied at most once.

**Managed mode**
- **FR-005**: The operator MUST be able to start a session in **managed mode**; such a session MUST be
  marked managed and MUST support remote answering of both permission prompts and free-form questions.
- **FR-006**: Managed mode MUST be an **additional** mode; sessions not managed retain the Phase 1–3
  hook-based monitoring/approval behavior. If managed mode is unavailable on a machine, the attempt
  MUST be refused with a clear reason (fall back to monitored-only), not fail opaquely.
- **FR-007**: A managed session that needs input MUST wait for it (no ClaudeDriver timeout); absence
  of input MUST NEVER be treated as an approval or a fabricated answer (fail-safe, Principle I).

**Transcript, history & search**
- **FR-008**: The system MUST present the full conversation of a managed session (messages, tool
  activity, questions/answers), updating live, reconstructed from a stable/supported source (not by
  parsing an unsupported internal file format).
- **FR-009**: The operator MUST be able to browse past sessions and **search** across them; results
  MUST be bounded/paged and MUST NOT block live control.

**Hardening**
- **FR-010**: Device and operator credentials MUST be rotatable and revocable such that old
  credentials stop working promptly and new ones work.
- **FR-011**: The hosted deployment's cost MUST be reviewable against the small-fleet envelope, with
  any always-on cost justified.
- **FR-012**: The system MUST provide a threat-model hardening checklist confirming the required
  controls (mTLS device identity, fail-safe defaults, least privilege, complete audit, no unhardened
  internet exposure), with gaps logged.

### Key Entities

- **Managed Session**: a session run under management (Agent SDK) — fully interactive-controllable
  (answer questions, approve, full transcript); distinct from a monitored-only session.
- **Question**: a free-form prompt a managed session poses — machine, session, question text, status
  (pending / answered / cancelled / unanswered), created/decided times; idempotent by id.
- **Transcript**: the reconstructed conversation of a managed session from a supported source.
- **Credential** (Phases 0/2): device certs and operator passkeys — now with explicit
  rotation/revocation flows exercised.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A free-form question from a managed session appears to the operator within 5 seconds
  with the question text and context.
- **SC-002**: An operator's answer reaches the session and it continues within 5 seconds; the item
  resolves as answered.
- **SC-003**: The system NEVER fabricates an answer or treats no-input as approval — 0 occurrences
  across all questions and prompts.
- **SC-004**: A managed session's full transcript is viewable and updates live; a search over past
  sessions returns matches, paged, without blocking live control.
- **SC-005**: A rotated/revoked credential stops working within a bounded time; the new one works.
- **SC-006**: The hardening checklist shows 100% of required controls present (or gaps explicitly
  logged), and reviewed cost is within the small-fleet envelope.

## Assumptions

- **Builds on Phases 0–3**: enrollment/identity, the authenticated agent channel, monitoring, alerts,
  approvals, and remote control are reused; questions/answers and managed-session control travel the
  same authenticated backend↔agent channel.
- **Managed mode uses the Claude Agent SDK** (Python/TypeScript) driven as a companion runtime by the
  on-machine agent over a defined bridge protocol; Phase 4 commits to building this in full
  (clarified). It is additive (monitored-only sessions keep Phase 1–3 behavior). Validating the real
  SDK end-to-end needs a runtime + API access (a deploy/CI-with-key step); the bridge is exercised
  with a fake companion otherwise.
- **No ClaudeDriver timeout on questions** (consistent with the Phase 2 approvals decision): a managed
  session waits for the operator; if the SDK runtime's own limit forces resolution, the session is
  told no answer was given (safe default), never a fabricated one.
- **Transcript source**: reconstructed from the SDK message stream / a supported export — never by
  parsing an unsupported internal transcript file.
- **Single operator** (from Phase 2): scope enforcement deferred to multi-user; audit + at-most-once +
  authenticated channel apply now.
- **Hardening scope** (clarified: full pass): credential rotation/revocation flows + a threat-model
  checklist + a cost review — not a full re-architecture.

## Dependencies

- Phases 0–3 delivered (and, for a real test, deployed).
- The Claude Agent SDK available on target machines (a Python/Node runtime the agent can drive).
- The ratified constitution (`.specify/memory/constitution.md`), Principles I–VII.

## Out of Scope (this phase / product edges)

- Replacing hook-based monitoring with managed mode for *all* sessions (managed mode is opt-in per
  session).
- Multi-user per-operator authorization scopes (deferred).
