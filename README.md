# ClaudeDriver

A self-hostable control plane to monitor and remotely operate multiple Claude Code instances across
developer machines (Windows + macOS), surfacing prompts/status to a web + mobile UI and letting an
authorized operator answer, approve, and dispatch work while away. Because it can answer permission
prompts and dispatch tasks, it is a **remote-code-execution control plane** — security is the
governing constraint (see [the constitution](.specify/memory/constitution.md)).

This repository currently implements **Phase 0 — Foundations & Contracts** (the walking skeleton).
See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the full plan and
[`specs/001-phase-0-foundations/`](specs/001-phase-0-foundations/) for the spec, plan, and tasks.

## What Phase 0 delivers

- **Backend** (`backend/`, Kotlin/Ktor): self-hosted **WebAuthn passkey** operator auth,
  operator-approved machine **enrollment** issuing per-device certificates, **mutual-TLS** device
  identity resolution (fail-safe), a **WebSocket hub** (version-negotiated, heartbeat, seq/resume,
  `sample_event` relay), a hash-chained **audit** log, and PostgreSQL via Flyway/Exposed.
- **Agent** (`agent/`, Kotlin/JVM): outbound-only, mutually-authenticated WSS client with backoff.
- **Shared contract** (`shared/`): the wire protocol, defined once and consumed by both.
- **Web** (`web/`, React + Vite): a minimal operator status page (passkey login, live status,
  sample-event inbox).
- **Infra** (`infra/`, Terraform): AWS ECS Fargate + ALB (mTLS) + RDS + SSM, with enforced
  `Project=ClaudeDriver` cost tags and a budget.
- **CI** (`.github/workflows/ci.yml`): build + test (incl. Testcontainers), web build/test, gitleaks
  secret scan, Terraform validate, constitution check.

## Build & test locally

Requires JDK 21, Node 20+, Docker (for the DB-backed integration tests).

```bash
./gradlew build        # compile + unit tests (integration tests run when Docker is present)
cd web && npm ci && npm run build && npm test
```

## Run locally

See [`specs/001-phase-0-foundations/quickstart.md`](specs/001-phase-0-foundations/quickstart.md) for
the end-to-end run/validate guide (start Postgres, run backend, web, and the agent; enroll; observe
a live sample event).

Copy `.env.example` → `.env` and fill values. `.env` is gitignored — **never commit secrets**.

## Deploy (AWS)

Deployment is Terraform-driven and **not run automatically** (it creates billable resources and
needs your AWS credentials). See [`infra/README.md`](infra/README.md) for the runbook: build/push
the backend image (`infra/docker/Dockerfile`), supply an ACM cert + domain and the device-CA trust
bundle, then `terraform init/plan/apply`. Never expose the backend without the mTLS + WebAuthn
hardening in place.

## Spec-driven development

This project uses GitHub Spec Kit (generic integration). Commands live in `.claude/commands/`
(`/speckit.specify`, `/speckit.plan`, `/speckit.tasks`, `/speckit.implement`, …) and their prompts
in `.speckit/commands/`.
