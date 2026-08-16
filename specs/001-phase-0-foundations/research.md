# Phase 0 Research & Decisions

Consolidated from prior architecture/tech research and the ratified constitution (v1.1.0). Each
entry: **Decision → Rationale → Alternatives considered**. All Technical-Context unknowns for
Phase 0 are resolved here (no open `NEEDS CLARIFICATION`).

## D1 — Backend framework: Ktor 3.x (Kotlin/JVM)

**Decision**: Ktor 3.x on JDK 21.
**Rationale**: Coroutine-native, WebSocket-first, low idle footprint for many mostly-idle agent
sockets (Principle VII); Ktor Client is multiplatform, enabling the same `shared` contract module to
be used by backend, agent, and (Phase 2) mobile (Principle III).
**Alternatives**: Spring Boot — heavier, no KMP contract sharing, but batteries-included; rejected
for a small Kotlin-fluent team optimizing footprint and code sharing.

## D2 — Backend hosting & ingress: ECS Fargate behind an ALB with mutual TLS

**Decision**: One small ECS Fargate task (0.25 vCPU / 0.5 GB) behind an Application Load Balancer.
The ALB terminates public TLS (ACM cert) and performs **mutual-TLS client-certificate verification**
for the agent listener against our device-CA trust store; it forwards verified cert details to the
backend. Operator/web traffic uses a separate listener/host without client-cert (WebAuthn instead).
**Rationale**: Only managed AWS ingress that gives true mTLS (Principle I/IV) plus stable long-lived
WebSockets, with minimal ops. Fits the small fleet within a bounded cost.
**Alternatives**: **App Runner** — simplest + scale-to-idle, but **no client-cert mTLS** → rejected
for the agent channel. **NLB (L4) + app-terminated TLS** — true mTLS but self-managed cert
lifecycle/ops → rejected. **Single EC2 + reverse proxy** — cheapest, but patching/ops burden →
rejected. ALB fixed cost is tracked in the plan's Complexity Tracking as justified by Principle I.

## D3 — Agent identity & mutual auth: per-device certificate (mTLS), issued at enrollment

**Decision**: On operator-approved enrollment the backend's device CA (Bouncy Castle) issues a
unique short-lived client certificate to the agent. The agent presents it on every outbound WSS
connection; the ALB verifies it against the trust store; the backend maps the cert to a `Machine`.
Revocation removes the cert from the trust/allow set.
**Rationale**: Per-device cryptographic identity + mutual authentication is the core trust primitive
(Principle I/IV). Certificates (not bearer tokens alone) prevent relay/replay.
**Alternatives**: Bearer token only — rejected (relayable, no device binding). Cloud IoT/device
services — rejected (extra managed cost/complexity for a small fleet).

## D4 — Operator authentication: self-hosted WebAuthn passkeys

**Decision**: Yubico `java-webauthn-server` implements a self-hosted WebAuthn relying party. A
one-time bootstrap code registers the first operator passkey; thereafter sign-in is passkey-only.
Sessions are signed server-side (`SESSION_SIGNING_KEY`).
**Rationale**: Phishing-resistant, self-hosted, no external identity provider (Principle I, per the
clarified decision). Standard browser + platform authenticators.
**Alternatives**: OIDC/Cognito/Entra — rejected by explicit decision (no external IdP). Password +
TOTP — weaker, more attack surface; rejected.

## D5 — Shared contract: kotlinx.serialization over JSON, versioned envelope

**Decision**: All messages are `@Serializable` types in `shared/commonMain`, wrapped in an envelope
carrying `protocolVersion`, message `type`, monotonic `seq`, and `payload`. JSON over WSS.
Compatibility is negotiated in the connect handshake; mismatch is refused with a reason.
**Rationale**: One source of truth for backend/agent/web (Principle III); JSON keeps traffic
debuggable/observable (Principle VI). Version-at-connect prevents silent drift.
**Alternatives**: Protobuf/CBOR — more efficient but less debuggable and heavier tooling; not needed
at this scale; rejected for Phase 0 (can add a binary codec later behind the same envelope).

## D6 — Persistence: PostgreSQL on RDS `db.t4g.micro`, Exposed + Flyway

**Decision**: Amazon RDS for PostgreSQL `db.t4g.micro`, single-AZ for Phase 0; Exposed SQL DSL;
Flyway versioned SQL migrations; HikariCP pool.
**Rationale**: Managed, cheapest managed Postgres tier (Principle VII); concurrent writers (agents +
operator) rule out SQLite; Exposed avoids heavy JPA; Flyway is simple for a single-DB target.
**Alternatives**: Aurora Serverless v2 — min 0.5 ACU always-on is costlier; deferred to a scale
phase. SQLite/embedded — single-writer, rejected. JPA/Hibernate — overkill; rejected.

## D7 — Real-time transport & resilience

**Decision**: Agent holds one persistent **outbound** WSS connection. Both directions use
ping/pong heartbeat (≤30 s); the agent reconnects with exponential backoff + jitter; every event
carries a per-connection monotonic `seq` and the peer resumes from last-seen on reconnect; commands
are idempotent by `commandId`; per-connection outbound queues are bounded with state coalescing.
**Rationale**: Directly implements Principle V; outbound-only implements Principle IV (no inbound
firewall changes).
**Alternatives**: SSE + POST — one-directional, rejected for a bidirectional control channel.
Long-poll — fallback only.

## D8 — Secrets & configuration: AWS SSM Parameter Store (SecureString)

**Decision**: SSM Parameter Store SecureString under a `claudedriver/` prefix for config/secrets,
injected into the Fargate task; local dev uses `.env` (gitignored). CA private key stored as a
SecureString/Secrets Manager entry.
**Rationale**: SSM standard SecureString is effectively free (Principle VII) and integrates with IAM
task roles; no secret in the repo (Principle I). `.env.example` documents required keys.
**Alternatives**: Secrets Manager everywhere — $0.40/secret/mo, reserved for values needing managed
rotation; used sparingly. Committed config — forbidden.

## D9 — Infrastructure as code: Terraform with enforced cost tags

**Decision**: Terraform provisions all AWS resources; the AWS provider `default_tags` stamps
`Project=ClaudeDriver` and `Environment=<env>` on every resource; an AWS Resource Group collects
them and an AWS Budget with an alert is defined.
**Rationale**: Reproducible provisioning enforces the tagging that makes project cost separately
attributable (FR-021/FR-022, Principle VII) instead of relying on manual tagging.
**Alternatives**: AWS CDK — weak Kotlin ergonomics; rejected. Manual console setup — untracked, no
tag enforcement; rejected.

## D10 — Audit log: append-only, hash-chained table

**Decision**: An `audit_event` table where each row stores the SHA-256 of `(prev_hash || row
payload)`, forming a tamper-evident chain. Phase 0 records enrollment, revocation, connection
accepted/refused, and auth failures.
**Rationale**: Tamper-evident attributability (Principle VI) without external infrastructure.
**Alternatives**: External append-only log (e.g. QLDB) — extra managed cost; rejected for Phase 0.
Plain table — no tamper evidence; rejected.

## D11 — CI quality gate: GitHub Actions + gitleaks

**Decision**: GitHub Actions runs Gradle build + tests (Testcontainers) + web Vitest + **gitleaks**
secret scan + a constitution-check step; any failure blocks merge.
**Rationale**: Implements Principle II and the secret-hygiene rule (FR-019/020). Repo already on
GitHub.
**Alternatives**: Other CIs — no benefit here. No secret scanner — rejected (violates FR-020).

## D12 — Testing strategy

**Decision**: Backend contract/integration tests via Ktor `testApplication` + Testcontainers
Postgres, covering the acceptance scenarios: mTLS/enrollment rejection, WebAuthn round-trip,
contract-version-mismatch refusal, audit emission, and agent reconnect/backoff. Web status page via
Vitest. Security-relevant behavior is test-gated (Principle I/II).
**Rationale**: The risk profile requires proving trust and fail-safe behavior by test.
**Alternatives**: Manual verification only — rejected.

## Deferred to later phases (explicitly not researched for Phase 0)

- Cross-platform process detection (OSHI) and Claude Code HTTP-hook installation — **Phase 1**.
- Alerts, blocking `PreToolUse` approvals, task dispatch — **Phase 2/3**.
- Mobile (Compose Multiplatform) + push via SNS/Pinpoint — **Phase 2**.
- Agent-SDK managed sessions for answering arbitrary prompts — **Phase 4 spike**.
