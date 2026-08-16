# Implementation Plan: Phase 0 — Foundations & Contracts

**Branch**: `001-phase-0-foundations` | **Date**: 2026-08-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-phase-0-foundations/spec.md`

## Summary

Phase 0 delivers a **walking skeleton**: a deployable, hardened Kotlin/Ktor backend on AWS that an
operator reaches after **self-hosted passkey (WebAuthn)** sign-in; a Kotlin/JVM agent that enrolls
(operator-approved) to obtain a per-device certificate and then dials **outbound** to the backend
over a **mutually-authenticated (mTLS)** WebSocket; a **single shared `kotlinx.serialization`
contract** (versioned, negotiated at connect) consumed by backend + agent + web; a **minimal web
status page** that renders live status and a demonstrated sample message; an **append-only,
hash-chained audit log**; and a **CI quality gate** (build + test + secret-scan + constitution
check). Infrastructure is **Terraform** with provider-enforced cost-allocation tags and an AWS
Budget so total project cost is separately attributable. No product features (process monitoring,
alerts, approvals, dispatch) are in Phase 0 — only the secure spine they attach to.

## Technical Context

**Language/Version**: Kotlin 2.1 on JDK 21 (backend, agent, shared); TypeScript 5.x (web).

**Primary Dependencies**: Ktor 3.x (server + client, WebSockets, Auth); kotlinx.serialization;
Exposed (SQL DSL) + HikariCP; Flyway (migrations); Yubico `java-webauthn-server` (passkeys);
Bouncy Castle (device-cert issuance/CA); React 19 + Vite + TanStack Query (web); Terraform (infra).

**Storage**: PostgreSQL on Amazon RDS `db.t4g.micro` (single-AZ for Phase 0), accessed via Exposed;
schema owned by Flyway.

**Testing**: JUnit 5 + Ktor `testApplication` + Testcontainers (ephemeral Postgres) for backend and
contract/integration tests; Vitest for the web status page.

**Target Platform**: AWS — backend on **ECS Fargate** (one small task) behind an **Application Load
Balancer** that terminates TLS and performs **mutual-TLS client-certificate verification** for
agents. Agent runs on Windows + macOS (JVM). Web runs in the browser.

**Project Type**: Multi-module monorepo — web service + companion agent + web frontend (+ a
KMP-ready shared module; mobile added in Phase 2).

**Performance Goals**: sustain ≤10 machines / ≤25 concurrent agent WebSocket sessions; status/API
p95 < 300 ms; sample-event propagation agent→web < 2 s; WebSocket heartbeat ≤ 30 s.

**Constraints**: agents outbound-only (no inbound ports); fail-safe defaults (deny/hold on
timeout/ambiguity); minimal monthly cost (single small Fargate task + `db.t4g.micro` + free-tier
SSM); every resource tagged `Project=ClaudeDriver`.

**Scale/Scope**: small fleet (≤10 machines, ≤25 sessions). Phase 0 scope is the walking skeleton
only; process monitoring, hooks, alerts, approvals, dispatch, and mobile/push are later phases.

## Constitution Check

*GATE: must pass before Phase 0 research and again after Phase 1 design.* Constitution v1.1.0.

| Principle | How this plan satisfies it | Status |
|---|---|---|
| I. Security-First & Fail-Safe | Agent auth = mTLS device certs verified at the ALB; operator auth = self-hosted WebAuthn passkeys (no external IdP); least-privilege IAM task roles + SSM-scoped secrets; enrollment defaults to **deny** (no implicit trust); no secrets in repo (gitleaks gate). No auto-approve path exists in Phase 0. | PASS |
| II. Spec-Driven & Test-Backed | Contract + integration tests precede/accompany code: mTLS-reject test, unenrolled-reject test, WebAuthn round-trip, contract-version-mismatch reject, audit-emission test (Testcontainers). | PASS |
| III. Single Shared Contract | One `shared` KMP module holds all wire DTOs (`kotlinx.serialization`) + a versioned envelope; backend, agent, and web consume it; version negotiated at connect and mismatch refused. | PASS |
| IV. Outbound-Only Agents & Explicit Enrollment | Agent initiates the WSS connection outbound; no inbound ports on machines. Enrollment is operator-approved → issues the device cert. No network discovery in Phase 0, so discovery never confers trust. | PASS |
| V. Resilient Real-Time Delivery | WSS heartbeat/ping-pong; agent reconnect with exponential backoff + jitter; per-connection monotonic sequence IDs with resume-from-last-seen; idempotent command IDs; bounded outbound queues. | PASS |
| VI. Full Auditability & Observability | Append-only, hash-chained `audit_event` table records enrollment, revocation, connection accept/refuse, auth failure. No Claude transcript parsing (out of Phase 0 scope). Structured logging. | PASS |
| VII. Resource & Cost Discipline | Single 0.25 vCPU/0.5 GB Fargate task, `db.t4g.micro`, SSM Parameter Store (free tier), scale-to-idle where possible; Terraform `default_tags` enforce `Project=ClaudeDriver`/`Environment`; AWS Budget + alert. ALB fixed cost is the one justified exception (see Complexity Tracking). | PASS (1 tracked cost tradeoff) |

**Result**: PASS. One cost tradeoff (ALB) is documented and justified in Complexity Tracking; it is
required to satisfy Principle I (managed mTLS), so Principle I takes precedence over the raw
cost-minimization preference of Principle VII.

**Post-Design Re-Check (after Phase 1 artifacts)**: PASS — no new violations. The contracts encode
fail-safe refusal (`version_mismatch`, unenrolled/forged rejection), idempotent `commandId`, and
`seq`-based resume (I, IV, V); the shared `protocol.md` is the single contract source (III); the
data model is append-only + hash-chained for audit (VI); the design adds no always-on resource
beyond those already justified (VII). No principle was weakened; Complexity Tracking is unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/001-phase-0-foundations/
├── plan.md              # This file
├── research.md          # Phase 0 output — decisions, rationale, alternatives
├── data-model.md        # Phase 1 output — entities & relationships
├── quickstart.md        # Phase 1 output — end-to-end validation guide
├── contracts/           # Phase 1 output — protocol + REST contracts
│   ├── protocol.md      #   shared WS envelope + Phase 0 message types + version negotiation
│   └── rest-api.md      #   enrollment, WebAuthn auth, status endpoints
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created here)
```

### Source Code (repository root)

```text
settings.gradle.kts          # Gradle multi-module root
build.gradle.kts

shared/                       # KMP module (commonMain now targets JVM; iOS/Android added Phase 2)
└── src/commonMain/kotlin/    # protocol DTOs, versioned envelope, message types (single source of truth)

backend/                      # Ktor service (JVM)
├── src/main/kotlin/
│   ├── api/                  # REST routes: /healthz, /status, /enroll, /auth (WebAuthn)
│   ├── ws/                   # WebSocket hub: agent + client sockets, heartbeat, sequence/replay
│   ├── auth/                 # WebAuthn (operator) + device-cert verification (agent)
│   ├── enrollment/           # operator-approved enrollment + device CA issuance
│   ├── audit/                # append-only hash-chained audit writer
│   └── persistence/          # Exposed tables, Flyway migrations, connection pool
└── src/test/kotlin/          # contract + integration tests (Testcontainers)

agent/                        # Kotlin/JVM daemon
├── src/main/kotlin/          # enrollment client, outbound mTLS WSS client, backoff/heartbeat
│                             #   (OSHI process monitoring is Phase 1, not here)
└── src/test/kotlin/

web/                          # Vite + React + TS — minimal status page
├── src/                      # WebAuthn login, status view, sample-message render, WS client
└── tests/                    # Vitest

infra/                        # Terraform — AWS
└── (ECS Fargate, ALB+mTLS, RDS, SSM params, Budget; default_tags enforce Project/Environment)

.github/workflows/            # CI: build + test + gitleaks secret scan + constitution check

# mobile/  → deferred to Phase 2 (shared module kept KMP-ready so it can be added without rework)
```

**Structure Decision**: A Gradle multi-module monorepo for the JVM components (`shared`, `backend`,
`agent`) maximizes contract reuse (Principle III); the `web` app and `infra` live as sibling
directories in the same repo for atomic, versioned changes. `shared` is a Kotlin Multiplatform
module compiled for the JVM now and extended with iOS/Android targets in Phase 2 so the mobile app
reuses the exact same contract without a rewrite. Mobile is intentionally not scaffolded in Phase 0
to keep the phase lean (Principle VII).

## Complexity Tracking

| Violation / Tradeoff | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Application Load Balancer (fixed ~$16–20/mo) rather than the cheapest possible ingress | Only managed AWS ingress that performs **mutual-TLS client-certificate verification**, which Principle I requires for agent device identity | **App Runner** rejected: no client-cert mTLS. **NLB + app-terminated TLS** rejected: more ops, self-managed cert rotation. **EC2 + reverse proxy** rejected: patching/ops burden and weaker managed posture. The ALB cost is bounded and justified by a NON-NEGOTIABLE security principle. |
