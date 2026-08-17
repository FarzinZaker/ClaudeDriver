# Phase 4 Research & Decisions (delta on Phases 0–3)

Format: **Decision → Rationale → Alternatives**.

## D1 — Managed mode via an SDK companion + stdio bridge

**Decision**: A managed session runs a **companion process** (Claude Agent SDK, Python) that the
Kotlin agent spawns and bridges over **line-delimited JSON on stdio**. Companion → agent events:
`transcript` (role, text), `question` (questionId, text), `ended`. Agent → companion: `answer`
(questionId, text), `cancel` (questionId), `prompt` (text), `stop`. The agent forwards events over
its outbound WSS and writes answers back to the companion's stdin.
**Rationale**: The Agent SDK is the only supported way to answer arbitrary questions programmatically;
it is Python/TS, so a companion bridged over stdio is the clean JVM integration. Outbound-only is
preserved (Principle IV) — the companion is local; only the agent talks to the backend.
**Alternatives**: rewrite the agent in Python (loses the Kotlin fleet code — rejected); parse Claude
Code transcript files (unsupported/unstable — rejected).

## D2 — Managed mode is additive; the launcher is pluggable

**Decision**: Managed mode is opt-in per session (started via a `start_managed` control command).
Monitored-only sessions keep Phase 1–3 hook behavior. A `CompanionLauncher` interface starts the
companion; production launches `companion.py`, tests use a **fake companion** (a script speaking the
bridge protocol) so the whole flow is exercisable without the real SDK.
**Rationale**: Additive avoids regressing existing behavior; the launcher interface makes the bridge
testable here (the real SDK cannot run in this environment).
**Alternatives**: force all sessions into managed mode (heavier, unnecessary — rejected).

## D3 — Questions: raise/answer, at-most-once, never fabricate, no timeout

**Decision**: A companion `question` becomes a backend `Question` (pending). The operator answers or
cancels; the answer/cancel is routed to the companion and applied **at most once** (only while
pending). No ClaudeDriver timeout (consistent with Phase 2). The system **never** fabricates an answer
and **never** treats no-input as approval; on companion crash a pending question resolves `unanswered`.
**Rationale**: Implements FR-001..004/007 and SC-003 — the load-bearing safety of full interactive
mode.
**Alternatives**: default-answer on timeout (forbidden — rejected).

## D4 — Transcript store + cross-session search

**Decision**: Companion `transcript` messages are stored in `transcript_message` (session, role, text,
ordered). A managed session's transcript is read from this store (never from an unsupported internal
file). Cross-session search is a bounded, paged case-insensitive text match over stored messages.
**Rationale**: A stable, supported source (the SDK stream) satisfies FR-008; a simple indexed text
search satisfies FR-009 at this scale without new infrastructure (Principle VII).
**Alternatives**: full-text engine (overkill for a small fleet — deferred).

## D5 — Hardening: rotation/revocation + checklist + cost review

**Decision**: **Rotate** a machine's device identity = revoke its active device certs + issue a fresh
enrollment (the agent re-enrolls with a new CSR → new cert); revocation reuses Phase 0. Operator
passkey revocation reuses Phase 0 credential model. A **threat-model checklist** (`docs/HARDENING.md`)
confirms each required control; a **cost review** (`docs/COST.md`) checks the small-fleet envelope.
All rotations/revocations are audited.
**Rationale**: Implements FR-010/011/012 as concrete flows + reviewable docs, not a re-architecture.
**Alternatives**: automatic scheduled rotation (nice-to-have — deferred).

## Environment note (retained)

The real companion cannot be run here (no SDK runtime / Claude Code / API key). The bridge protocol,
question/answer control-plane, transcript store, search, and rotation are all built and tested in
Kotlin; the live SmokeTest exercises the bridge with a **fake companion**. Real-SDK validation is a
deploy/CI-with-key follow-up.

## Deferred (product edges)

- Making all sessions managed by default. Multi-user per-operator scopes.
