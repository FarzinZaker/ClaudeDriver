# Implementation Plan: Phase 4 — Full Interactive Control & Hardening

**Branch**: `005-interactive-control` | **Date**: 2026-08-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/005-interactive-control/spec.md`

## Summary

Phase 4 adds **managed sessions** (run under the Claude Agent SDK) so the operator can answer any
free-form question, plus full transcript, cross-session search, and a hardening pass. The agent
starts a managed run by spawning an **SDK companion** process and bridging it over line-delimited
JSON on stdio; the companion streams transcript messages and free-form **questions** up to the agent,
which forwards them over the existing authenticated WSS; the operator's **answer** is routed back down
to the companion. Never fabricated, never no-input-as-approval (fail-safe). Transcript messages are
stored for viewing + search. A hardening pass adds credential **rotation/revocation** and a
**threat-model checklist + cost review**. Managed mode is **additive** — monitored-only sessions keep
Phase 1–3 behavior.

> **Environment limit (honored, not hidden).** The real Agent SDK (Python/TS) + Claude Code + API key
> are not present here. The bridge protocol is proven live against a **fake companion**; the **real
> companion** (`agent/companion/companion.py`) is written but its true end-to-end validation is a
> deploy/CI-with-key step. Everything on the Kotlin side is built and tested here.

## Technical Context

**Language/Version**: Kotlin 2.1 / JDK 21 (backend, agent, shared); Python 3.11 (SDK companion).

**Primary Dependencies**: none new on the JVM; the companion uses the Claude Agent SDK
(`claude-agent-sdk`, Python). Reuses Ktor, Exposed/Flyway, the Phase 2/3 `AgentHub` channel.

**Storage**: PostgreSQL + Flyway `V5__managed.sql` (`question`, `transcript_message`).

**Testing**: JUnit5 + Testcontainers (ManagedService: answer at-most-once, transcript store, search);
the live SmokeTest extended to prove managed **question → answer** end-to-end via a **fake companion**
(a script speaking the bridge protocol). The real companion is lint/structure-checked only.

**Target Platform**: unchanged; the companion runs on the machine alongside the agent.

**Performance Goals**: a question surfaces ≤5 s; an answer reaches the session ≤5 s.

**Constraints**: never fabricate an answer; never treat no-input as approval; at-most-once answers;
audited; managed mode additive.

**Scale/Scope**: small fleet, single operator.

## Constitution Check

*GATE.* Constitution v1.1.0.

| Principle | How Phase 4 satisfies it | Status |
|---|---|---|
| I. Security-First & Fail-Safe | Questions/answers ride the mutually-authenticated agent WSS; an answer is applied at most once; **no input is never an approval and no answer is ever fabricated** (SC-003); on companion crash a pending question resolves unanswered, safely. The hardening pass exercises rotation/revocation and a threat-model checklist. | PASS |
| II. Spec-Driven & Test-Backed | Answer at-most-once, transcript store, search, and the bridge protocol are unit/integration/smoke tested (fake companion). | PASS |
| III. Single Shared Contract | New types (`question_raised`, `question_answer`, `transcript_message`, `question_event`, `transcript_event`) + control type `start_managed` in `shared`; PROTOCOL_VERSION → 0.5.0 (additive). | PASS |
| IV. Outbound-Only Agents | The companion runs locally under the agent; the agent bridges it and forwards over its existing outbound WSS. Nothing new is exposed off the machine. | PASS |
| V. Resilient Real-Time Delivery | Questions/answers carry ids and are idempotent; transcript messages are ordered; a dropped link leaves a question unanswered (never fabricated). | PASS |
| VI. Auditability | Every question raised + answer/cancel appended to the Phase 0 hash-chained audit; rotations/revocations audited. | PASS |
| VII. Resource & Cost Discipline | One companion process per managed session; a bounded transcript; a **cost review** doc confirms the small-fleet envelope. | PASS |

**Result**: PASS. Post-design re-check: PASS. No new complexity beyond the Phase 0 ALB item; the
SDK-companion boundary is a documented deployment concern, not a constitution deviation.

## Project Structure (delta)

```text
shared/…/protocol/            # + QuestionRaised, QuestionAnswer, TranscriptMessage, QuestionEvent,
                              #   TranscriptEvent; control type "start_managed"; PROTOCOL_VERSION 0.5.0
backend/…/managed/            # ManagedService: questions (raise/answer at-most-once), transcript store, search
backend/…/ws/ + api/          # ingest question_raised/transcript_message; push question_event/transcript_event;
                              #   route question_answer; REST /questions, /questions/{id}/answer,
                              #   /sessions/{id}/transcript, /search; start_managed via control
backend/…/enrollment/         # + rotateDeviceCert (revoke old + fresh enrollment) — hardening
backend/resources/db/migration/V5__managed.sql
agent/…/managed/              # ManagedSessionController + CompanionLauncher (pluggable: real python, fake);
                              #   stdio bridge; handle start_managed + question_answer; forward companion events
agent/companion/companion.py  # REAL Agent-SDK companion (deploy/CI-validated), + a fake companion for tests
web/src/                      # managed-session view: transcript + questions inbox + answer box + search
docs/HARDENING.md, docs/COST.md   # threat-model checklist + cost review (FR-011/012)
```

**Structure Decision**: extend existing modules. The agent's **CompanionLauncher** is an interface so
the bridge is testable with a fake companion; production launches the Python Agent-SDK companion.
Managed sessions surface through the same monitoring model (a `session_start` marks them managed).

## Complexity Tracking

No new violations. The SDK companion (a separate runtime) is required to answer arbitrary questions
(unattainable via hooks) and is isolated behind the bridge protocol + launcher interface. The Phase 0
ALB/mTLS cost tradeoff remains the only tracked item.
