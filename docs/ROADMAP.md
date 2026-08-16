# ClaudeDriver — Modules, Phases & Priorities

Derived from the research findings and product decisions (2026-08-16). This is the build plan the
Spec Kit specs will be carved from. Priorities: **P0** = must-have for a usable product, **P1** =
core value, **P2** = later/optional.

## Product decisions (locked)

| Decision | Choice |
|---|---|
| Remote reach | Fully remote / off-network — backend hosted on **Azure**, internet-reachable, hardened |
| Per-machine model | **Installed agent** (Kotlin/JVM daemon) on each Win/Mac machine |
| Users / risk | **Single operator**, treated as **high-risk** (RCE control plane) |
| Hosting | **Azure**, cost-conscious (minimal SKUs, scale-to-idle); secrets via `.env` / Key Vault |

## The integration reality (shapes scope)

| Capability | Supported? | Mechanism |
|---|---|---|
| Detect "needs attention" (permission / question / idle) | ✅ | `Notification` HTTP hook |
| Detect completion / stop | ✅ | `Stop` HTTP hook |
| Register sessions / machines | ✅ | `SessionStart` / `SessionEnd` hooks + agent |
| Remotely **approve/deny a tool permission** | ✅ | Blocking `PreToolUse` HTTP hook (fail-safe deny on timeout) |
| Dispatch a **new task/message to an idle session** | ✅ (partial) | Channels / `claude -p --resume` via the local agent |
| Remotely **answer an arbitrary mid-flight question** | ❌ (CLI) | Requires **Agent-SDK-managed sessions** (Phase 4 spike) |
| Read live state | ⚠️ | From hook events + `/export`; **never** parse internal `.jsonl` as an API |

---

## Phase 0 — Foundations & Contracts  **(P0)**

Goal: a skeleton that compiles, deploys cheaply to Azure, and pins the shared contract + security model.

- **M0.1 Monorepo & build** — Gradle multi-module: `shared` (protocol), `backend` (Ktor), `agent`
  (JVM daemon); separate `web` (Vite/React) and `mobile` (Compose Multiplatform). Wire the shared
  module into backend + agent + mobile.
- **M0.2 Shared protocol module** — `kotlinx.serialization` DTOs + versioned envelope for all
  agent↔backend↔client messages. Single source of truth (Principle III).
- **M0.3 Azure infra baseline** — Container Apps (backend, scale-to-idle), Azure DB for PostgreSQL
  Flexible (smallest tier), Key Vault, Azure Notification Hubs (unified FCM/APNs). Flyway baseline
  migration. `.env` wired to Key Vault.
- **M0.4 Security architecture spike** — mTLS device-enrollment design, operator AuthN (Entra ID /
  OIDC or passkey), scope model. **Blocks everything that follows** (Principle I).
- **M0.5 CI** — build + test + secret-scan gate; Constitution Check checklist.

## Phase 1 — Monitoring MVP  **(P0)**  — *"See everything"*

Goal: watch all Claude Code instances across machines and show them live. No control yet.

- **M1.1 Per-machine agent** — outbound WebSocket (mTLS), OSHI process watch to detect Claude Code
  processes (name/args/cwd/lifecycle), install as Windows Service / launchd.
- **M1.2 Machine enrollment & identity** — operator-approved pairing → issues device cert.
- **M1.3 Backend WS hub & registry** — agent + client sockets, heartbeat, sequence IDs + replay;
  machine/session registry persisted to Postgres.
- **M1.4 Claude Code hook installer** — the agent writes `Notification`/`Stop`/`SessionStart`/
  `SessionEnd` HTTP hooks pointing at the backend, with allowlisted URLs + env-var auth headers.
- **M1.5 Web dashboard (read-only)** — virtualized machine/session cards, live status, alert inbox
  (view only); connection-health indicator.
- **M1.6 Operator AuthN** — single-user login (OIDC/passkey).

## Phase 2 — Alerting & Remote Approvals  **(P0 core / P1 polish)**  — *"Get notified & approve"*

Goal: be alerted anywhere and approve/deny tool permissions remotely — safely.

- **M2.1 Alert pipeline** — Notification events → alert model, unread/ack, severity, dedupe.
- **M2.2 Mobile app + push** — Compose Multiplatform shell, FCM+APNs via Notification Hubs,
  device-token registration, tap-through to the alert. Remote-by-default (no LAN dependency).
- **M2.3 Blocking approval flow** — `PreToolUse` blocking hook → backend holds the decision until
  the operator answers on web/mobile; **timeout ⇒ deny** (Principle I fail-safe). Idempotent.
- **M2.4 Audit log** — append-only, tamper-evident record of every decision (Principle VI).

## Phase 3 — Remote Control & Task Dispatch  **(P1)**  — *"Take action"*

Goal: give the operator real control over idle sessions and projects.

- **M3.1 Send message / new task** to an idle session (channels or `--resume` via the agent),
  idempotent + audited.
- **M3.2 Session control surface** — transcript tail (via `/export` / streamed hook events),
  pause/stop, project context view.
- **M3.3 Scoped authorization** — per-machine / per-project action scopes; short-lived, revocable
  credentials.
- **M3.4 Full session lifecycle** — start a new Claude Code run on a chosen project/machine.

## Phase 4 — Full Interactive Control & Hardening  **(P2)**

Goal: close the "answer any question remotely" gap and harden for the risk profile.

- **M4.1 Agent-SDK managed-session mode (spike → build)** — run sessions under the Agent SDK so the
  backend can answer arbitrary prompts programmatically. Evaluate cost/complexity vs. the hook mode
  before committing.
- **M4.2 History, search & multi-project management** UI.
- **M4.3 Cost & resource optimization** — scale-to-zero tuning, event coalescing, connection budget
  review (Principle VII).
- **M4.4 Security hardening pass** — threat-model review, credential rotation/revocation, an
  adversarial test of the RCE surface.

---

## Recommended starting order

1. **M0.4 security spike** and **M0.2 shared protocol** first — they constrain everything.
2. Stand up **M0.1/M0.3** skeleton + Azure.
3. Build **Phase 1** end-to-end for one machine, then fan out to many.
4. Layer **Phase 2** (alerts + approvals) — this is where the product becomes genuinely useful.
5. **Phase 3 → 4** as the value and risk tolerance grow.

## Open questions to resolve during `/speckit.specify`

- Operator AuthN: Entra ID (fits Azure) vs. self-contained passkey? (Leaning Entra ID.)
- Which Claude Code events to treat as "needs attention" vs. "informational" for alert noise.
- Task dispatch to idle sessions: channels vs. `--resume` — pick per reliability.
- Phase 4: is SDK-managed mode worth the added footprint, or is approve+dispatch enough?
