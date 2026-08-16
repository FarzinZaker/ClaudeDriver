# Phase 0 Quickstart & Validation

How to run the walking skeleton locally and prove each Phase 0 user story. This is a validation
guide, not implementation — see `contracts/` and `data-model.md` for shapes, `tasks.md` (from
`/speckit.tasks`) for the build steps.

## Prerequisites

- JDK 21, Gradle (wrapper committed), Node 20+ (web), Docker (Testcontainers + local Postgres).
- Terraform ≥ 1.7 and AWS credentials (only for the cloud validation, not local).
- `cp .env.example .env` and fill local values (local Postgres URL, `SESSION_SIGNING_KEY`,
  `WEBAUTHN_RP_ID=localhost`, `WEBAUTHN_ORIGIN=http://localhost:5173`, `OPERATOR_BOOTSTRAP_CODE`).

## Run locally

```bash
# 1. Start Postgres (local) + apply migrations (Flyway runs on backend boot)
docker compose up -d postgres          # infra/compose for local dev

# 2. Backend (Ktor) — serves REST + WSS
./gradlew :backend:run

# 3. Web status page
cd web && npm install && npm run dev    # http://localhost:5173

# 4. Agent (in another terminal) — enroll then connect
./gradlew :agent:run --args="enroll --code <ENROLLMENT_CODE>"   # obtains device cert
./gradlew :agent:run                    # connects outbound over (m)TLS WSS
```

> Local dev may relax ALB mTLS to app-terminated TLS; the cloud validation exercises true ALB mTLS.

## Validate each user story

### US1 — Reach a secured, deployed backend
1. `curl -i http://localhost:8080/healthz` → `200 {"status":"ok",...}`.
2. `curl -i http://localhost:8080/status` (no session) → **401** and an `auth_failure` row appears
   in `audit_event`.
3. In the web page, register the first passkey with `OPERATOR_BOOTSTRAP_CODE`, then sign in → the
   status view loads.
4. `git log -p | grep -iE 'secret|password|BEGIN .*PRIVATE KEY'` and the gitleaks CI job → no live
   secrets. ✅ SC-001, SC-002.

### US2 — Enroll a machine and establish trusted identity
1. Start the agent **without** a device cert / with a forged cert → backend refuses; no `Machine`
   becomes `enrolled`; `connection_refused` audit row. ✅ (US2 #1/#4, FR-011)
2. As operator: `POST /machines` then `POST /machines/{id}/enrollment` → one-time code.
3. `agent enroll --code <code>` → receives a device certificate; machine flips to `enrolled`
   (`enrollment_consumed` audit).
4. Start the agent → it dials **outbound**, connects, and appears `connected` in `/status` with no
   inbound firewall change. ✅ SC-003, SC-004 (US2 #2/#3/#5)
5. `POST /machines/{id}/revoke` → the live connection drops and a reconnect is refused. ✅ (FR-012)

### US3 — Exchange a versioned message over the shared contract
1. With the agent connected, trigger a `sample_event`; confirm the web status page renders it with
   identical fields (no component-local redefinition). ✅ SC-006 (FR-016)
2. Point an agent build at an incompatible `protocolVersion` (e.g. `9.9.9`) → backend replies
   `version_mismatch` and closes; nothing is coerced. ✅ SC-005 (FR-015)

### US4 — Automated quality gate
1. Open a PR that adds a fake secret (e.g. `AKIA...`) → **gitleaks** job fails, merge blocked. ✅
2. Open a PR that breaks a contract type or a test → build/test job fails. ✅
3. Open a clean PR → all jobs pass. ✅ SC-007 (FR-019/020)

## Resilience & cost checks
- Kill the backend while the agent runs, then restart it → the agent reconnects automatically with
  backoff, resuming from `last_seq`; no manual action. ✅ SC-008 (FR-017)
- After `terraform apply`, open AWS Resource Groups filtered by `Project=ClaudeDriver` → every
  resource appears; Cost Explorer filtered by that tag shows a single attributable figure; the AWS
  Budget alert exists. ✅ SC-009 (FR-021/022)

## Test suites

```bash
./gradlew test          # backend contract + integration (Testcontainers): mTLS/enrollment reject,
                        # WebAuthn round-trip, version-mismatch reject, audit emission, reconnect
cd web && npm test      # Vitest: status render + sample-event render
```

Expected: all green; the security-relevant tests above must pass for the phase to be "done".
