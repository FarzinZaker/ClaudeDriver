# Tasks: Phase 0 — Foundations & Contracts

**Input**: Design documents in `specs/001-phase-0-foundations/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/
**Tests**: INCLUDED — the spec + Constitution Principle II require test-backed, security-critical behavior.

Format: `[ID] [P?] [Story] Description` · `[P]` = parallelizable (different files, no dep).

## Phase 1: Setup (shared infrastructure)

- [ ] T001 Create Gradle multi-module root: `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml` (version catalog), Gradle wrapper (JDK 21, Kotlin 2.1).
- [ ] T002 [P] Scaffold `shared/` KMP module (`build.gradle.kts`, `commonMain`) with kotlinx.serialization.
- [ ] T003 [P] Scaffold `backend/` Ktor module (`build.gradle.kts`, `Application.kt`, `application.conf`).
- [ ] T004 [P] Scaffold `agent/` JVM module (`build.gradle.kts`, `Main.kt`).
- [ ] T005 [P] Scaffold `web/` Vite + React + TS app (`package.json`, `vite.config.ts`, `tsconfig.json`).
- [ ] T006 [P] Scaffold `infra/` Terraform root (`main.tf`, `variables.tf`, `versions.tf`, `provider.tf` with `default_tags`).
- [ ] T007 [P] Add `.editorconfig`, ktlint, `.gitleaks.toml`, and `infra/docker/Dockerfile` (backend image).

## Phase 2: Foundational (blocks all user stories)

- [ ] T008 [P] Shared contract in `shared/src/commonMain/kotlin/.../protocol/`: `Envelope`, message types (`Hello`, `HelloAck`, `VersionMismatch`, `Ping`, `Pong`, `SampleEvent`), `ProtocolVersion` + compatibility check. (contracts/protocol.md)
- [ ] T009 Persistence base in `backend/.../persistence/`: HikariCP + Exposed wiring, Flyway `V1__init.sql` for all Phase 0 tables (data-model.md).
- [ ] T010 [P] Config/env loading in `backend/.../config/` (env + SSM-ready), structured logging, error responses.
- [ ] T011 Audit writer in `backend/.../audit/`: append-only, SHA-256 hash-chained `audit_event` insert + chain-verify.
- [ ] T012 App bootstrap in `backend/.../Application.kt`: Ktor modules, content negotiation (kotlinx), routing skeleton, `GET /healthz`.

**Checkpoint**: builds; `GET /healthz` returns ok; migrations run.

## Phase 3: User Story 1 — Reach a secured, deployed backend (P1) 🎯 MVP

**Goal**: authenticated operator reaches `/status`; unauthenticated refused; no secrets in repo.

### Tests (write first, must fail)
- [ ] T013 [P] [US1] Contract test: unauthenticated `GET /status` → 401 + `auth_failure` audit (`backend/src/test/.../StatusAuthTest.kt`).
- [ ] T014 [P] [US1] Integration test: WebAuthn register (bootstrap) + login → session → `/status` 200 (`.../WebAuthnFlowTest.kt`).

### Implementation
- [ ] T015 [P] [US1] `Operator` + `WebAuthnCredential` entities/tables + repo (`backend/.../persistence/`).
- [ ] T016 [US1] Self-hosted WebAuthn RP (Yubico) in `backend/.../auth/`: register/login options+verify, `sign_count` regression guard.
- [ ] T017 [US1] Signed session cookie + auth middleware; refuse + audit on unauthenticated non-public routes.
- [ ] T018 [US1] `GET /status` returning server + machines + recent sample events (contracts/rest-api.md).

**Checkpoint**: US1 independently testable.

## Phase 4: User Story 2 — Enroll a machine & establish trusted identity (P1)

**Goal**: operator-approved enrollment issues device identity; agent dials out & is recognized; forged/unenrolled/revoked refused.

### Tests (write first, must fail)
- [ ] T019 [P] [US2] Contract test: unenrolled/forged client identity on `/agent/connect` → refused + `connection_refused` audit.
- [ ] T020 [P] [US2] Integration test: approve enrollment → agent enroll (CSR→cert) → connect → machine `enrolled`/`connected`.
- [ ] T021 [P] [US2] Test: revoke → live connection dropped, reconnect refused (FR-012).

### Implementation
- [ ] T022 [P] [US2] `Machine`, `EnrollmentRequest`, `DeviceCertificate`, `AgentConnection` entities/tables + repos.
- [ ] T023 [US2] Device CA in `backend/.../auth/ca/` (Bouncy Castle): issue short-lived client cert from CSR; trust-store/fingerprint lookup.
- [ ] T024 [US2] Enrollment endpoints: `POST /machines`, `POST /machines/{id}/enrollment` (one-time code), `POST /agent/enroll` (CSR→cert), `POST /machines/{id}/revoke`.
- [ ] T025 [US2] Client-cert → machine resolution + fail-safe refusal (unenrolled/forged/expired/revoked) at connect; `GET /agent/whoami`.
- [ ] T026 [P] [US2] Agent: enrollment client (keypair + CSR) in `agent/.../enroll/`.
- [ ] T027 [US2] Agent: outbound (m)TLS WSS client with exponential backoff + jitter + heartbeat (`agent/.../conn/`).

**Checkpoint**: US2 independently testable.

## Phase 5: User Story 3 — Exchange a versioned message over the shared contract (P2)

**Goal**: sample message flows agent→backend→web identically; version mismatch refused at connect.

### Tests (write first, must fail)
- [ ] T028 [P] [US3] Test: `hello` with incompatible `protocolVersion` → `version_mismatch` + close (FR-015).
- [ ] T029 [P] [US3] Test: `sample_event` relayed agent→backend→operator WSS unchanged (FR-016).

### Implementation
- [ ] T030 [US3] WSS hub in `backend/.../ws/`: `hello`/`hello_ack` negotiation, ping/pong, per-conn `seq` + resume, bounded queues.
- [ ] T031 [US3] Operator WSS channel + relay of `sample_event`; seed via `/status`.
- [ ] T032 [P] [US3] Agent: emit a `sample_event` after connect.
- [ ] T033 [P] [US3] Web: WebAuthn login (browser API), status view, sample-event render, WS client (TanStack Query + WS) in `web/src/`.
- [ ] T034 [P] [US3] Web tests (Vitest): status render + sample-event render.

**Checkpoint**: US3 independently testable end-to-end.

## Phase 6: User Story 4 — Automated quality gate (P3)

**Goal**: CI blocks secret leaks, broken contract, failing tests.

- [ ] T035 [US4] GitHub Actions `.github/workflows/ci.yml`: Gradle build+test (with Docker service for integration), web build+test.
- [ ] T036 [P] [US4] gitleaks job + `.gitleaks.toml`; fail on plaintext secret.
- [ ] T037 [P] [US4] Constitution-check job (verifies no committed secrets, contract-version bump on shared changes).

## Phase 7: Infrastructure & Deployment (enables SC-001, SC-009)

- [ ] T038 [P] Terraform networking: VPC, subnets, security groups (outbound from tasks; ALB ingress) in `infra/network.tf`.
- [ ] T039 [P] Terraform data: RDS PostgreSQL `db.t4g.micro` + SSM SecureString params in `infra/data.tf`.
- [ ] T040 Terraform compute: ECS Fargate service (one small task), ALB with two listeners incl. **mutual-TLS** trust store, ACM cert, target groups in `infra/compute.tf`.
- [ ] T041 [P] Terraform governance: AWS Resource Group by tag, AWS Budget + alert, IAM task roles (least privilege) in `infra/governance.tf`.
- [ ] T042 Backend `Dockerfile` + `terraform apply` runbook in `infra/README.md` (deploy steps, mTLS trust bootstrap).

## Phase 8: Polish & cross-cutting

- [ ] T043 Run `quickstart.md` validation end-to-end (local); record results.
- [ ] T044 [P] `terraform validate` + `fmt`; confirm every resource carries `Project=ClaudeDriver`.
- [ ] T045 [P] README at repo root: architecture summary, run/deploy pointers.

## Dependencies & order

- Setup (P1) → Foundational (P2) blocks all stories.
- US1, US2 (both P1) can proceed after Foundational; US2 depends on the contract (T008) + persistence.
- US3 depends on the contract (T008) + a connected agent (US2) for the true end-to-end demo.
- US4 (CI) can be built any time after Setup but validates the whole.
- Infra (Phase 7) is independent of story code and needed for SC-001/SC-009 (deploy + cost).
- Within a story: tests first (must fail) → entities → services → endpoints → integration.

## Parallelization

- T002–T007 (module scaffolds) all `[P]`.
- Entities within a story (`[P]`) parallel; endpoints serialize on shared routing.
- Web (T033/T034) parallel with backend once the contract (T008) + REST shapes exist.
- Infra (T038/T039/T041) mostly parallel; T040 depends on network + data.
