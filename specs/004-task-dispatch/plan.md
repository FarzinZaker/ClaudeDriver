# Implementation Plan: Phase 3 — Remote Control & Task Dispatch

**Branch**: `004-task-dispatch` | **Date**: 2026-08-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/004-task-dispatch/spec.md`

## Summary

Phase 3 adds remote control over the Phase 2 command channel. The operator issues **control commands**
(start-run / dispatch-task / stop) that the backend persists, routes to the owning agent over the
existing authenticated outbound WSS (the same `AgentHub` path as approval decisions), and audits. The
agent runs a **SessionController** that manages Claude Code runs it starts: **start-run** launches a
persistent session (pluggable launcher), **dispatch-task** delivers an instruction when the session is
ready (queue-until-ready, else undeliverable), and **stop** ends it gracefully then forcibly and moots
its pending approvals. The agent reports outcomes back as **control results**; started sessions appear
in monitoring. Externally-started sessions remain *observable* (Phase 1) but only agent-managed
sessions are *controllable* — an honest, documented boundary. Answering arbitrary mid-turn questions
stays out (Phase 4).

## Technical Context

**Language/Version**: Kotlin 2.1 / JDK 21 (backend, agent, shared) — no new stack.

**Primary Dependencies**: none new; reuses Ktor, Exposed/Flyway, kotlinx.serialization, the Phase 2
`AgentHub` command channel, and the JDK `ProcessBuilder` for the agent's session launcher.

**Storage**: PostgreSQL + Flyway `V4__control.sql` (`control_command`).

**Testing**: JUnit5 + Testcontainers (ControlService: at-most-once, routing, audit); the live
SmokeTest extended to prove **start-run → dispatch (delivered) → stop** end-to-end with a fake
launcher (a stdin-reading process standing in for a Claude Code run).

**Target Platform**: unchanged.

**Performance Goals**: dispatch delivered ≤5 s; start visible ≤10 s; stop reflected ≤10 s.

**Constraints**: control travels the authenticated backend→agent channel; **at-most-once**;
**fail-safe** (undelivered rather than misdelivered; nothing partial on disconnect); audited.

**Scale/Scope**: small fleet, single operator.

## Constitution Check

*GATE.* Constitution v1.1.0.

| Principle | How Phase 3 satisfies it | Status |
|---|---|---|
| I. Security-First & Fail-Safe | Control commands ride the mutually-authenticated agent WSS; only an authenticated operator issues them; a command is applied at most once and, on disconnect, is reported undelivered rather than half-applied or misrouted; stop moots approvals so nothing runs. | PASS |
| II. Spec-Driven & Test-Backed | Command routing, at-most-once, queue-until-ready/undeliverable, and audit are unit + integration + smoke tested. | PASS |
| III. Single Shared Contract | New types (`control_command`, `control_result`, `control_event`) in `shared`; PROTOCOL_VERSION → 0.4.0 (additive, same-MAJOR). | PASS |
| IV. Outbound-Only Agents | Backend routes commands down the agent's existing outbound WSS; the agent spawns/controls local processes; nothing new is exposed off the machine. | PASS |
| V. Resilient Real-Time Delivery | Commands carry a command id and are idempotent; results correlate by id; a queued dispatch is delivered once-ready or reported undeliverable (no silent loss). | PASS |
| VI. Auditability | Every control action + result appended to the Phase 0 hash-chained audit. | PASS |
| VII. Resource & Cost Discipline | No new services; a bounded set of agent-managed processes; no new cloud cost. | PASS |

**Result**: PASS. Post-design re-check: PASS — contracts encode idempotent commands + explicit
undeliverable; the agent boundary (only agent-managed sessions are controllable) is documented, not a
violation. No new complexity beyond the Phase 0 ALB item.

## Project Structure (delta)

```text
shared/…/protocol/            # + ControlCommand, ControlResult, ControlEvent; PROTOCOL_VERSION 0.4.0
backend/…/control/            # ControlService: issue, route via AgentHub, apply results, at-most-once, audit
backend/…/ws/ + api/          # ingest control_result; push control_event; REST:
                              #   POST /sessions/{id}/dispatch, POST /machines/{id}/start-run, POST /sessions/{id}/stop
backend/resources/db/migration/V4__control.sql
agent/…/control/              # SessionController (managed runs) + Launcher (pluggable: real `claude`, test fake)
agent/…/                      # handle control_command; deliver/queue/stop; report control_result
web/src/                      # session control surface: dispatch box, start-run, stop (live via control_event)
```

**Structure Decision**: extend existing modules. The agent's **Launcher** is an interface so the
control plane is fully testable with a fake process; production wires a launcher that starts `claude`
in the project. Only agent-managed sessions are controllable; monitoring still covers all sessions.

## Complexity Tracking

No new violations. The observable-but-not-controllable boundary for externally-started sessions is a
documented product limit (a persistent, controllable session must be one the agent started), not a
constitution deviation. The Phase 0 ALB/mTLS cost tradeoff remains the only tracked item.
