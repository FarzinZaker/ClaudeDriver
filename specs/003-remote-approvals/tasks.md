# Tasks: Phase 2 — Remote Approvals & Mobile

Design docs in `specs/003-remote-approvals/`. Tests INCLUDED. Builds on Phases 0–1. `[P]` = parallel.

## Phase 1: Setup & Foundational

- [ ] T001 [P] `shared`: add `ApprovalRequest`, `ApprovalDecision`, `ApprovalEvent`, `DeviceRegister`
  + `MessageType` constants; `PROTOCOL_VERSION` → `0.3.0`.
- [ ] T002 backend: Flyway `V3__approvals.sql` + Exposed tables `approval_request`, `push_device`.

## Phase 2: US1 — Remote approve/deny (P1)

- [ ] T003 [US1] backend `approvals/ApprovalService`: raise (ingest `approval_request`), decide
  (at-most-once), moot-on-stop, fail-safe deny; audit; push `approval_event`.
- [ ] T004 [US1] backend: `GET /approvals`, `POST /approvals/{id}/decide`; ingest `approval_request`
  on the agent WS; route `approval_decision` back to the owning agent connection.
- [ ] T005 [US1] agent `receiver/`: blocking `/approve` endpoint — hold response, `CompletableDeferred`
  by `requestId`, forward `approval_request`, await; complete with allow/deny; **deny on close/drop**.
- [ ] T006 [US1] agent `hooks/`: add a blocking `PreToolUse` http hook (matcher = mutating tools,
  very long timeout) to the managed block; idempotent + teardown (extends Phase 1 installer).
- [ ] T007 [P] [US1] web: approvals panel (pending list, Approve/Deny, live via `approval_event`).
- [ ] T008 [US1] tests: ApprovalService (at-most-once, moot, fail-safe) unit + Testcontainers;
  extend the live SmokeTest to prove held-hook → approve AND deny end to end.

## Phase 3: US2 — Push + mobile decisions (P1)

- [ ] T009 [US2] backend `push/`: `PushService` + `PushSender` (LoggingPushSender now, SnsPushSender
  stub), `DeviceStore`; `POST /devices`, `DELETE /devices/{token}`; notify on attention/approval.
- [ ] T010 [P] [US2] tests: device register/prune; a raise triggers a recorded push send.
- [ ] T011 [P] [US2] mobile: Compose Multiplatform app scaffold under `mobile/` (standalone gradle) —
  passkey sign-in, backend client (shared contract), approvals + decide, push registration.

## Phase 4: US3 — Mobile monitoring parity (P2)

- [ ] T012 [P] [US3] mobile: machines/sessions/alerts screens live over the operator WS (scaffold).

## Phase 5: US4 — Safety (P3)

- [ ] T013 [US4] tests: every decision audited; at-most-once; disconnect/stop → deny (covered by T008
  + an explicit audit assertion).

## Phase 6: Polish

- [ ] T014 [P] Run quickstart; ensure CI green (build+test, web, gitleaks, terraform validate,
  constitution check). Root README + `mobile/README.md` pointers.

## Order
Setup (T001–T002) blocks all. US1 core (T003–T006) before web (T007) and tests (T008). US2 push
(T009–T010) parallel to mobile (T011–T012). Mobile is a standalone build (not in root CI).
