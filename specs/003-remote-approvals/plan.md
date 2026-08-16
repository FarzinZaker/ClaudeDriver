# Implementation Plan: Phase 2 — Remote Approvals & Mobile

**Branch**: `003-remote-approvals` | **Date**: 2026-08-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-remote-approvals/spec.md`

## Summary

Phase 2 adds remote approvals and mobile on top of Phases 0–1. A **blocking** Claude Code
`PreToolUse` hook posts to the agent's loopback receiver, which **holds the HTTP response open**,
forwards an `approval_request` over the existing outbound WSS, and waits; the backend records the
request, pushes it to operators (web + mobile + push notification), and when the operator decides,
sends an `approval_decision` back down the WSS; the agent completes the held hook response with
allow/deny — so Claude Code proceeds or is blocked. **No ClaudeDriver timeout** (the hook is held
with a very long timeout); if Claude Code's own hard limit or a failure forces resolution, it is
**deny** (fail-safe, Principle I). A **push dispatcher** notifies registered devices. A **Compose
Multiplatform** app (iOS+Android) provides passkey sign-in, monitoring parity, and approve/deny.

## Technical Context

**Language/Version**: Kotlin 2.1 / JDK 21 (backend, agent, shared); Kotlin/Compose Multiplatform
(mobile); TypeScript (web) — all existing except mobile.

**Primary Dependencies (new)**: Compose Multiplatform + KMP (mobile app); a push sender abstraction
(pluggable — a logging sender now, an Amazon SNS/Pinpoint sender in prod). Reuses Ktor, Exposed,
kotlinx.serialization.

**Storage**: PostgreSQL + Flyway `V3__approvals.sql` (`approval_request`, `push_device`).

**Testing**: JUnit5 + Ktor testApplication + Testcontainers (backend/agent flow); the live SmokeTest
extended to prove approve AND deny end-to-end; Vitest (web). Mobile: shared logic unit tests only
(UI not run headless).

**Target Platform**: unchanged backend/agent/web; mobile targets iOS + Android.

**Performance Goals**: approval visible ≤5 s; decision takes effect ≤5 s; push to a backgrounded,
off-network phone ≤10 s.

**Constraints**: outbound-only (CC hook → agent → backend → decision back); **never auto-approve**;
at-most-once decisions; no new always-on cloud cost beyond push.

**Scale/Scope**: small fleet (≤10 machines / ≤25 sessions), single operator.

## Constitution Check

*GATE.* Constitution v1.1.0.

| Principle | How Phase 2 satisfies it | Status |
|---|---|---|
| I. Security-First & **Fail-Safe** | The approval channel rides the mutually-authenticated agent WSS; decisions require an authenticated operator session; **every non-approve resolution is deny** — timeout (platform), disconnect, crash, or error never auto-approve. Decisions are at-most-once. | PASS |
| II. Spec-Driven & Test-Backed | Blocking-hold correlation, decision application, fail-safe deny, at-most-once, and audit are unit + integration + smoke tested. | PASS |
| III. Single Shared Contract | New types (`approval_request`, `approval_decision`, `approval_event`, `device_register`) in `shared`; PROTOCOL_VERSION → 0.3.0 (additive, same-MAJOR compatible). Mobile reuses the same contract via KMP. | PASS |
| IV. Outbound-Only Agents | Claude Code → agent loopback receiver (holds the request) → agent's existing outbound WSS → backend; decision returns down the same socket. Nothing new is exposed off the machine. | PASS |
| V. Resilient Real-Time Delivery | Decisions carry the request id + are idempotent; a dropped link resolves the held hook to deny; approvals de-duped by session/prompt. | PASS |
| VI. Auditability | Every approval raised + decision (approve/deny, who, surface, reason) appended to the Phase 0 hash-chained audit. | PASS |
| VII. Resource & Cost Discipline | No new always-on resources; push uses a managed send API (per-message) with a pluggable sender; a logging sender runs at zero cost in dev. | PASS |

**Result**: PASS. Post-design re-check: PASS — contracts encode fail-safe deny + idempotent
decisions; data model records decision provenance; no new complexity beyond the Phase 0 ALB item.

## Project Structure (delta)

```text
shared/…/protocol/            # + ApprovalRequest, ApprovalDecision, ApprovalEvent, DeviceRegister; PROTOCOL_VERSION 0.3.0
agent/…/receiver/             # blocking /approve endpoint: hold response, correlate by requestId, complete on decision
agent/…/                      # forward approval_request; await approval_decision; fail-safe deny on close
backend/…/approvals/          # ApprovalService (raise, decide, at-most-once, moot-on-stop), fail-safe
backend/…/push/               # PushService + DeviceStore; pluggable Sender (LoggingSender now, SnsSender later)
backend/…/ws/ + api/          # ingest approval_request; push approval_event; REST /approvals, /approvals/{id}/decide, /devices
backend/resources/db/migration/V3__approvals.sql

web/src/                      # approvals panel (approve/deny), live via approval_event

mobile/                       # Compose Multiplatform app (SEPARATE gradle build; needs Android SDK + Xcode)
  └─ shared UI + KMP networking (reuses the wire contract), passkey sign-in, monitoring, approvals, push register
```

**Structure Decision**: extend existing modules in place. The **mobile app lives in `mobile/` as a
standalone Compose Multiplatform project, intentionally NOT in the root `settings.gradle.kts`**, so
the JVM/web build and CI stay green without the Android/iOS toolchains. It shares the wire contract
by construction (same `kotlinx.serialization` DTO definitions). Building/running it requires the
Android SDK, Xcode, and a device/simulator — out of reach in this environment; it is delivered as a
buildable scaffold with the networking/models/screens wired.

## Complexity Tracking

No new violations. The mobile module is isolated from the main build specifically to avoid coupling
the core system's CI to mobile toolchains. The Phase 0 ALB/mTLS cost tradeoff remains the only item.
