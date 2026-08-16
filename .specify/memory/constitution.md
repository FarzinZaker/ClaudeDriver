<!--
SYNC IMPACT REPORT
Version change: 1.0.0 → 1.1.0
Rationale: Amendment — set hosting provider to AWS, specify self-hosted
  (passkey/WebAuthn) operator authentication with no external identity
  provider, and require project-level cost attribution (resource
  grouping/tagging). MINOR: materially updated Technology & Architecture
  Constraints and clarified Principle I & VII; no principle removed.
Modified principles:
  - I. Security-First & Fail-Safe — operator AuthN specified as self-hosted
    passkeys/WebAuthn (was "OIDC/SSO or passkey/TOTP")
  - VII. Resource & Cost Discipline — added project cost-attribution requirement
Modified sections:
  - Technology & Architecture Constraints — Azure → AWS across backend,
    persistence, secrets, push, and remote access
Added sections: none
Removed sections: none
Templates reviewed:
  - .specify/templates/plan-template.md ✅ (Constitution Check gate references these principles)
  - .specify/templates/spec-template.md ✅
  - .specify/templates/tasks-template.md ✅
Deferred TODOs: none

Prior versions:
  - 1.0.0 (2026-08-16): Initial ratification (7 principles + constraints + workflow + governance).
-->

# ClaudeDriver Constitution

ClaudeDriver is a self-hostable control plane that monitors and remotely operates multiple
Claude Code instances running across developer machines (Windows and macOS), surfacing prompts,
questions, and status to a web and mobile UI, and letting an authorized operator answer, approve,
and dispatch work while away from those machines. Because the system can answer permission prompts
and dispatch tasks to coding agents, it is a **remote-code-execution control plane over a developer
fleet**. That fact governs every principle below.

## Core Principles

### I. Security-First & Fail-Safe (NON-NEGOTIABLE)

The system grants remote code execution over developer machines; it MUST be designed as a
crown-jewel asset with minimized blast radius.

- Agents MUST authenticate to the backend with per-device identity (mTLS client certificates
  issued at enrollment). Bearer tokens alone MUST NOT be the sole agent credential.
- Operators MUST authenticate with strong, phishing-resistant, self-hosted authentication
  (passkeys / WebAuthn) that does NOT depend on an external identity provider. Answering prompts or
  dispatching tasks is a fleet-admin capability and MUST be gated accordingly.
- Authorization MUST be least-privilege and scoped per operator × machine × project × action.
- **Fail-safe, never fail-open:** when a decision path is unreachable, times out, or is ambiguous,
  the system MUST default to the SAFE outcome (deny / hold). A permission prompt MUST NEVER be
  auto-approved by timeout, absence of response, or component failure.
- Secrets (tokens, certs, connection strings, push keys) MUST live in a secret store or an
  untracked `.env`; they MUST NEVER be committed to the repository.
- Any capability to answer prompts or run tasks MUST be constrained to the intended coding-agent
  interaction surface, not a general "run anything" primitive.

Rationale: a single forged command or backend compromise equals fleet-wide RCE; safety must be the
default state, not the happy path.

### II. Spec-Driven & Test-Backed Development (NON-NEGOTIABLE)

Every feature MUST flow through the Spec Kit lifecycle — `specify` → `plan` → `tasks` →
`implement` — before implementation. No production code is written without an approved spec and
plan that pass the Constitution Check.

- Contract and integration tests MUST exist for every cross-component boundary (agent↔backend,
  backend↔client, hook↔backend) and MUST be written before or alongside the implementation they
  cover, not after.
- Security-relevant behavior (auth, scope enforcement, fail-safe defaults, audit emission) MUST
  have explicit tests; it is not "done" until proven by test.

Rationale: the risk profile forbids ad-hoc changes; specs and tests are the control that keeps a
high-consequence system honest.

### III. Single Shared Contract

The wire protocol and its data types MUST be defined exactly once and reused everywhere.

- Protocol DTOs MUST be authored in a shared Kotlin module (`kotlinx.serialization`, `commonMain`)
  consumed by the backend, the per-machine agent, and the Compose Multiplatform app.
- The protocol MUST be explicitly versioned; backward-incompatible changes MUST bump the protocol
  version and be negotiated at connection time.
- No component may hand-maintain a divergent copy of a shared type.

Rationale: a small team cannot afford N drifting copies of the contract; one source of truth
prevents whole classes of integration bugs.

### IV. Outbound-Only Agents & Explicit Enrollment

Developer machines MUST NOT accept inbound connections for this system.

- The per-machine agent MUST initiate a persistent OUTBOUND connection to the backend. No inbound
  ports, firewall changes, or port-forwarding on developer machines are permitted.
- A machine MUST become known through explicit, operator-approved enrollment that establishes
  device identity. Network discovery (e.g. mDNS) MAY suggest candidates but MUST NEVER, by itself,
  confer trust or membership.

Rationale: outbound-only removes the NAT/firewall attack surface and matches how trustworthy
control planes onboard nodes.

### V. Resilient Real-Time Delivery

Alerts are only useful if they are trustworthy; the transport MUST be built for flaky links.

- Every persistent connection MUST use heartbeat/ping-pong liveness detection and reconnect with
  exponential backoff + jitter.
- Events MUST carry per-source monotonic sequence IDs; clients MUST be able to resume/replay missed
  events after reconnect.
- Commands (approve, deny, send-task) MUST be idempotent and de-duplicated by command ID; delivery
  may occur more than once.
- Connections MUST apply bounded queues and state coalescing (send latest snapshot, not every
  delta) under load.

Rationale: "you'll be alerted when a machine needs you" is a promise that fails silently without
these guarantees.

### VI. Full Auditability & Observability

Every consequential action MUST be attributable and reconstructable after the fact.

- Every command and prompt answer MUST be recorded to an append-only, tamper-evident audit log:
  who, which machine/session/project, what action, the decision, when, and the result.
- Session and machine state changes MUST be observable in real time and retained for history.
- The system MUST NOT rely on parsing Claude Code's internal transcript files as a stable API;
  state MUST be reconstructed from hook events and supported CLI/SDK outputs.

Rationale: an RCE tool without a complete audit trail is unaccountable and undiagnosable.

### VII. Resource & Cost Discipline

The hosted footprint MUST stay small and predictable.

- Components MUST be efficient at rest — many mostly-idle connections MUST NOT translate into
  proportional CPU/memory or cost.
- Cloud resources MUST prefer minimal instance sizes and scale-to-idle where available; any resource
  that incurs ongoing cost MUST be justified in the plan.
- All hosted resources MUST be grouped and tagged under a single project identifier so total project
  cost is clearly attributable (cost-allocation tags + a budget/alert), keeping billing legible.
- Per-connection memory, polling intervals, and event volume MUST be bounded and reviewed.

Rationale: the operator explicitly constrains hosting cost; efficiency is a first-class design
requirement, not an afterthought.

## Technology & Architecture Constraints

- **Backend:** Kotlin on the JVM using Ktor (coroutine-native, WebSocket-first). Persistence in
  PostgreSQL (Amazon RDS / Aurora Serverless) with Flyway migrations. Hosted on AWS with minimal,
  scale-aware compute; secrets in AWS Secrets Manager / SSM Parameter Store.
- **Per-machine agent:** a Kotlin/JVM daemon installed as a Windows Service / macOS launchd
  service, using OSHI for cross-platform process detection (name, args, working directory,
  lifecycle). Ships the same shared protocol module as the backend.
- **Web UI:** React + TypeScript (Vite), TanStack Query as the server-state cache fed by a
  WebSocket layer, minimal local UI state (e.g. Zustand). A live, virtualized machine/session
  board plus an alert inbox.
- **Mobile UI:** Compose Multiplatform (iOS + Android), sharing networking and DTOs with the
  backend. Designed **remote-by-default** — it MUST NOT depend on direct LAN reachability of the
  backend, and MUST NOT rely on the iOS Local Network permission for core function.
- **Claude Code integration:** HTTP hooks are the integration surface — `Notification` and `Stop`
  for detection, a blocking `PreToolUse` hook for remote approval, `SessionStart`/`SessionEnd` for
  registry. Hook target URLs MUST be allowlisted; hook auth headers MUST use env-var interpolation,
  never inline secrets. Answering arbitrary free-form questions remotely is out of scope for the
  hook-based mode and, if ever required, MUST be delivered via an Agent-SDK-managed session mode
  specified separately.
- **Remote access & push:** the operator is typically off-network. Interactive access reaches the
  AWS-hosted backend directly (hardened, never left unauthenticated); background alerts reach the
  operator via APNs/FCM push (dispatched via Amazon SNS / Pinpoint) independent of any live
  connection. These are two deliberate paths.

## Development Workflow & Quality Gates

- Work is organized as a Gradle multi-module project (shared protocol, backend, agent) plus the
  web app and the Compose Multiplatform app. Shared types live in the shared module only.
- Every change set MUST pass, before merge: build, unit + contract/integration tests, and a
  Constitution Check confirming no principle is violated (especially fail-safe defaults, scope
  enforcement, audit emission, and no committed secrets).
- Secrets are provided via untracked `.env` (see `.env.example`); CI and deployment read them from
  the secret store. A commit that introduces a plaintext secret MUST be rejected.
- Cross-component protocol changes MUST update the shared module and bump the protocol version in
  the same change set.
- High-impact or destructive capabilities MUST be reviewed against the threat model and covered by
  audit logging and tests before release.

## Governance

This constitution supersedes ad-hoc practice. All specs, plans, and pull requests MUST verify
compliance with these principles; unavoidable deviations MUST be documented, justified against the
threat model, and time-boxed.

- **Amendments** require a documented change describing the motivation and impact, an updated Sync
  Impact Report, and a version bump per the policy below.
- **Versioning policy (semantic):** MAJOR for backward-incompatible governance/principle removals
  or redefinitions; MINOR for a new principle/section or materially expanded guidance; PATCH for
  clarifications and non-semantic refinements.
- **Compliance review:** the Constitution Check in the plan template is the enforcement point;
  reviewers MUST block changes that weaken a NON-NEGOTIABLE principle without an approved
  amendment.
- Runtime, per-feature guidance lives in each feature's spec/plan under `specs/`; this document
  holds only durable, project-wide rules.

**Version**: 1.1.0 | **Ratified**: 2026-08-16 | **Last Amended**: 2026-08-16
