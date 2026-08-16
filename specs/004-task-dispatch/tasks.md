# Tasks: Phase 3 — Remote Control & Task Dispatch

Design docs in `specs/004-task-dispatch/`. Tests INCLUDED. Builds on Phases 0–2. `[P]` = parallel.

## Phase 1: Setup & Foundational

- [ ] T001 [P] `shared`: add `ControlCommand`, `ControlResult`, `ControlEvent` + `MessageType`
  constants; `PROTOCOL_VERSION` → `0.4.0`.
- [ ] T002 backend: Flyway `V4__control.sql` + Exposed table `control_command`.

## Phase 2: US2 — Start a new persistent run (P1)

- [ ] T003 [US2] backend `control/ControlService`: issue commands (persist pending), route
  `control_command` via `AgentHub`, apply `control_result` (at-most-once), audit; push `control_event`.
- [ ] T004 [US2] backend: `POST /machines/{id}/start-run`, `GET /commands`; ingest `control_result` on
  the agent WS.
- [ ] T005 [US2] agent `control/SessionController` + `Launcher` (interface; real `claude`, fake for
  tests): start a persistent run, assign a `claudeSessionId`, emit `session_start`, report `started`.
- [ ] T006 [US2] agent: handle `control_command` (start_run) → SessionController; send `control_result`.

## Phase 3: US1 — Dispatch a task (P1)

- [ ] T007 [US1] backend: `POST /sessions/{id}/dispatch` → command routed to the owning agent.
- [ ] T008 [US1] agent SessionController: deliver an instruction to a managed session when ready
  (queue-until-ready); report `delivered`/`done`, or `undeliverable` if the session is gone.
- [ ] T009 [US1] tests: ControlService route + at-most-once + undeliverable (Testcontainers); extend
  the live SmokeTest: start-run → dispatch (a stdin fake records it) → verify delivered.

## Phase 4: US3 — Stop a session (P2)

- [ ] T010 [US3] backend: `POST /sessions/{id}/stop` → command routed; on `stopped`, moot approvals.
- [ ] T011 [US3] agent SessionController: stop graceful → force; emit `session_end`; report `stopped`.
- [ ] T012 [US3] test: stop ends the managed process + moots approvals (extend SmokeTest / integration).

## Phase 5: US4 — Control surface & safety (P3)

- [ ] T013 [P] [US4] web: session control surface — dispatch box, start-run, stop; live via
  `control_event`.
- [ ] T014 [US4] test: every command + result audited; at-most-once (covered by T009 + an audit assert).

## Phase 6: Polish

- [ ] T015 [P] Run quickstart; ensure CI green; README pointer.

## Order
Setup (T001–T002) blocks all. US2 (T003–T006) then US1 (T007–T009) then US3 (T010–T012); all reuse the
AgentHub command channel. Web (T013) after the REST + control_event exist.
