# Tasks: Phase 4 — Full Interactive Control & Hardening

Design docs in `specs/005-interactive-control/`. Tests INCLUDED. Builds on Phases 0–3. `[P]` = parallel.

## Phase 1: Setup & Foundational

- [ ] T001 [P] `shared`: add `QuestionRaised`, `QuestionAnswer`, `TranscriptMessage`, `QuestionEvent`,
  `TranscriptEvent` + `MessageType` constants; `PROTOCOL_VERSION` → `0.5.0`.
- [ ] T002 backend: Flyway `V5__managed.sql` + Exposed tables `question`, `transcript_message`.

## Phase 2: US2/US1 — Managed sessions + answer questions (P1)

- [ ] T003 [US1] backend `managed/ManagedService`: raise question, answer/cancel (at-most-once, never
  fabricate), store transcript; route `question_answer` via `AgentHub`; audit; push question/transcript events.
- [ ] T004 [US1] backend: `GET /questions`, `POST /questions/{id}/answer`; ingest `question_raised` +
  `transcript_message` on the agent WS; `POST /machines/{id}/start-managed` (start_managed control).
- [ ] T005 [US2] agent `managed/ManagedSessionController` + `CompanionLauncher` (interface; real python
  companion, fake for tests): spawn companion, bridge stdio, forward events, write answers/prompts.
- [ ] T006 [US2] agent: handle `control_command` (start_managed) + `question_answer`; report via
  `question_raised`/`transcript_message`/`session_start`/`session_end`.
- [ ] T007 [US2] `agent/companion/companion.py`: REAL Claude Agent SDK companion speaking the bridge;
  plus a fake companion script for tests.
- [ ] T008 [US1] tests: ManagedService (answer at-most-once, cancel, transcript) Testcontainers; extend
  the live SmokeTest: start_managed → fake companion question → answer → companion records it.

## Phase 3: US3 — Transcript, history & search (P2)

- [ ] T009 [US3] backend: `GET /sessions/{id}/transcript`, `GET /search?q=`; store transcript on ingest.
- [ ] T010 [P] [US3] web: managed-session transcript view + questions inbox + answer box + search.
- [ ] T011 [US3] test: transcript store + search returns matches (Testcontainers).

## Phase 4: US4 — Hardening (P3)

- [ ] T012 [US4] backend `enrollment`: `rotateDeviceCert` (revoke active + fresh enrollment); `POST
  /machines/{id}/rotate-cert`; audit.
- [ ] T013 [P] [US4] docs: `docs/HARDENING.md` (threat-model checklist) + `docs/COST.md` (cost review).
- [ ] T014 [US4] test: rotate revokes old cert (old fingerprint no longer resolves) + issues new.

## Phase 5: Polish

- [ ] T015 [P] Run quickstart; ensure CI green; README pointers.

## Order
Setup (T001–T002) blocks all. US2/US1 (T003–T008) then US3 (T009–T011) then US4 (T012–T014). The
companion + bridge (T005–T007) reuse the AgentHub channel; web (T010) after the REST + events exist.
