# Tasks: Phase 1 — Monitoring MVP

**Input**: design docs in `specs/002-process-monitoring/`. **Tests**: INCLUDED (Principle II).
Builds on Phase 0. `[P]` = parallelizable.

## Phase 1: Setup & Foundational (blocks stories)

- [ ] T001 [P] `shared`: add `DetectedProcess`, `ProcessSnapshot`, `ActivityEvent`, `SessionUpdate`,
  `AlertEvent` + `MessageType` constants; bump `PROTOCOL_VERSION` → `0.2.0` (contracts/protocol-additions.md).
- [ ] T002 backend: Flyway `V2__monitoring.sql` + Exposed tables for `session`, `activity_event`, `alert` (data-model.md).
- [ ] T003 [P] agent: add `oshi-core` + `ktor-server-cio` deps (version catalog + agent build).

## Phase 2: US1 — See live Claude Code instances (P1)

- [ ] T004 [US1] agent `monitor/`: OSHI scan on interval, match Claude Code by name/args, read cwd, diff → send `process_snapshot`.
- [ ] T005 [US1] backend `sessions/`: ingest `process_snapshot`; upsert sessions (`process_present`, project) correlated by machine+cwd/sessionId.
- [ ] T006 [US1] backend: `GET /sessions`, extend `GET /status` (online/lastSeen/sessionCount); push `session_update` on the operator WS.
- [ ] T007 [P] [US1] web: machine→session cards, live via `session_update`; offline/stale indication.
- [ ] T008 [P] [US1] test: session upsert + state from process snapshot (Testcontainers); web card render.

## Phase 3: US2 — Alert when an instance needs attention (P1)

- [ ] T009 [US2] agent `receiver/`: localhost Ktor CIO server on `127.0.0.1`, token-checked; map Claude Code hook payload → `activity_event`; forward over WSS.
- [ ] T010 [US2] agent `hooks/`: install a managed hooks block into user `settings.json` (Notification/Stop/SessionStart/SessionEnd → localhost; allowlist loopback; env-var auth), idempotent + teardown.
- [ ] T011 [US2] backend `alerts/AttentionClassifier`: configurable event→attention mapping with defaults (research D4).
- [ ] T012 [US2] backend `sessions/`: apply session state machine from `activity_event` (waiting/finished/stopped).
- [ ] T013 [US2] backend `alerts/AlertService`: raise on entry to waiting (dedupe), auto-resolve on exit/stop/process-gone, ack; audit lifecycle.
- [ ] T014 [US2] backend: `GET /alerts`, `POST /alerts/{id}/ack`; push `alert_event`.
- [ ] T015 [P] [US2] web: alert inbox (urgency sort, acknowledge, open session), live via `alert_event`.
- [ ] T016 [P] [US2] tests: classifier (unit), event→alert→auto-resolve + ack + no-alert-on-informational (Testcontainers).

## Phase 4: US3 — Inspect session state & activity (P2)

- [ ] T017 [US3] backend: `GET /sessions/{id}` with bounded recent event history; prune old events.
- [ ] T018 [US3] backend `sessions/`: staleness sweep — mark `unknown_stale` when machine offline or last-activity threshold exceeded; emit `session_update`.
- [ ] T019 [P] [US3] web: session detail view (state, project, last-active, recent events), live.
- [ ] T020 [P] [US3] test: staleness transition; session-detail assembly.

## Phase 5: US4 — Safe setup/teardown (P3)

- [ ] T021 [US4] test: hook-install idempotency (re-run = identical config), teardown removes only the managed block, user config preserved, no plaintext secret in shared/committed location.

## Phase 6: Polish

- [ ] T022 [P] Run `quickstart.md` validation; update root README pointer.
- [ ] T023 [P] Ensure CI green (build+test incl. Testcontainers, web, gitleaks, terraform validate, constitution check).

## Dependencies & order
- T001–T003 (setup) block all. US1 (T004–T008) then US2 (T009–T016); US2 depends on the contract + session registry from US1. US3 (T017–T020) after US2. US4 (T021) validates the agent hook installer from T010.
- Within a story: tests alongside; ingest/services before endpoints before web.
