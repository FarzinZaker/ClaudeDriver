# Implementation Plan: Phase 1 — Monitoring MVP

**Branch**: `002-process-monitoring` | **Date**: 2026-08-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/002-process-monitoring/spec.md`

## Summary

Phase 1 adds monitoring on top of the Phase 0 spine. The **agent** detects Claude Code processes
(OSHI) and runs a **localhost hook receiver**; it installs a managed Claude Code hooks block that
posts activity to `127.0.0.1`, and forwards both process snapshots and activity events to the backend
over its existing authenticated **outbound** WebSocket (Constitution Principle IV — no new inbound to
the machine, nothing posted directly to the backend). The **backend** ingests these, maintains a
**session registry** with a state machine, **classifies** each event as needs-attention or
informational, raises/auto-resolves **alerts**, and audits their lifecycle. The **web dashboard**
gains live machine→session cards, an **alert inbox** (acknowledge/open), and a session detail view.
Observe-and-alert only — no approvals/dispatch/answering/push.

## Technical Context

**Language/Version**: Kotlin 2.1 / JDK 21 (agent, backend, shared); TypeScript 5 (web) — unchanged.

**Primary Dependencies (new in Phase 1)**: **OSHI** (`oshi-core`) for cross-platform process
detection; **Ktor server (CIO)** embedded in the agent for the localhost hook receiver. Everything
else reused from Phase 0 (Ktor, Exposed/Flyway, kotlinx.serialization, React/Vite/TanStack Query).

**Storage**: PostgreSQL (Phase 0 RDS) + Flyway `V2__monitoring.sql` for `session`, `activity_event`,
`alert`.

**Testing**: JUnit 5 + Ktor `testApplication` + Testcontainers (backend/integration); Vitest (web).

**Target Platform**: unchanged — AWS ECS Fargate backend; agent on Windows + macOS; browser web.

**Project Type**: multi-module monorepo (existing).

**Performance Goals**: process start/stop reflected ≤5 s; attention alert ≤5 s; offline→stale ≤30 s;
monitoring adds no perceptible latency to Claude Code; sustain ≤10 machines / ≤25 sessions.

**Constraints**: outbound-only; Claude Code hook posts are non-blocking (unreachable ⇒ CC unaffected);
idempotent + reversible hook setup; no secrets committed; no new always-on cloud cost.

**Scale/Scope**: small fleet (≤10 machines / ≤25 sessions). Monitoring only.

## Constitution Check

*GATE: pass before Phase 0 research, re-check after design.* Constitution v1.1.0.

| Principle | How Phase 1 satisfies it | Status |
|---|---|---|
| I. Security-First & Fail-Safe | Hook events carry a local token AND ride the agent's mutually-authenticated WSS; the local token lives in the machine-local agent config (not committed); backend unreachable ⇒ CC continues, state goes stale (never false-active); no answer/approve surface exists yet. | PASS |
| II. Spec-Driven & Test-Backed | Classification, session state machine, hook-config idempotency, and event→alert→resolve get unit + integration tests. | PASS |
| III. Single Shared Contract | New message types (`process_snapshot`, `activity_event`, `session_update`, `alert_event`) added to the `shared` module + a protocol MINOR bump; negotiated as before. | PASS |
| IV. Outbound-Only Agents | Claude Code → agent `127.0.0.1` receiver → agent's existing outbound WSS → backend. No inbound to the machine; nothing posts to the backend directly. | PASS |
| V. Resilient Real-Time Delivery | Event buffering on the agent, coalesced session updates, monotonic seq (Phase 0), alert de-dupe, staleness thresholds. | PASS |
| VI. Auditability | Alert raised/acknowledged/resolved appended to the Phase 0 hash-chained audit log. | PASS |
| VII. Resource & Cost Discipline | No new AWS resources — reuses Phase 0 infra; only V2 tables. Bounded event history + coalescing keep volume small. | PASS |

**Result**: PASS — no new violations, no new complexity entries (the Phase 0 ALB tradeoff still
stands). Post-design re-check: PASS (contracts encode staleness + de-dupe + non-blocking delivery;
data model bounds event history; audit reused).

## Project Structure

### Documentation (this feature)

```text
specs/002-process-monitoring/
├── plan.md, research.md, data-model.md, quickstart.md
├── contracts/{protocol-additions.md, rest-api-additions.md}
└── tasks.md   (/speckit.tasks)
```

### Source Code (delta on the existing monorepo)

```text
shared/…/protocol/           # + ProcessSnapshot, DetectedProcess, ActivityEvent, SessionUpdate, AlertEvent
                             #   + MessageType additions; PROTOCOL_VERSION -> 0.2.0

agent/…/monitor/             # OSHI process scanner (detect Claude Code by name/args/cwd, lifecycle diff)
agent/…/receiver/            # embedded localhost Ktor receiver for Claude Code hooks
agent/…/hooks/               # Claude Code settings.json managed-block installer (idempotent + teardown)
agent/…/                     # forward process snapshots + activity events over the existing WSS

backend/…/sessions/          # SessionRegistry + state machine, event correlation
backend/…/alerts/            # AttentionClassifier (configurable) + AlertService (raise/resolve/ack/dedupe)
backend/…/ws/                # ingest agent process_snapshot/activity_event; push session_update/alert_event
backend/…/api/               # + GET /sessions, GET /sessions/{id}, GET /alerts, POST /alerts/{id}/ack
backend/resources/db/migration/V2__monitoring.sql

web/src/                     # machine→session cards, alert inbox (ack/open), session detail; WS handlers
```

**Structure Decision**: extend the existing modules in place; all new wire types live only in
`shared` (Principle III). The agent gains an embedded localhost receiver so Claude Code's HTTP hooks
never leave the machine except via the agent's authenticated outbound channel.

## Complexity Tracking

No new violations. (The Phase 0 ALB/mTLS cost tradeoff remains the only tracked item.)
