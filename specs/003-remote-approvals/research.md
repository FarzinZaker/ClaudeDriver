# Phase 2 Research & Decisions (delta on Phases 0–1)

Only new decisions. Prior stack decisions carry forward. Format: **Decision → Rationale →
Alternatives**.

## D1 — Blocking approval via a held HTTP request correlated to a WSS decision

**Decision**: Claude Code's blocking `PreToolUse` hook POSTs to the agent's loopback `/approve`
endpoint. The handler **suspends** (holds the HTTP response open), registers a `CompletableDeferred`
keyed by a generated `requestId`, forwards an `approval_request` over the agent's outbound WSS, and
awaits the deferred. When the backend sends `approval_decision` (matched by `requestId`), the agent
completes the deferred and returns Claude Code's permission decision (`allow`/`deny`) as the hook
response.
**Rationale**: This is the only supported way to *answer* a Claude Code permission prompt remotely,
and it keeps everything outbound-only (Principle IV). Correlation by `requestId` makes decisions
idempotent (Principle V).
**Alternatives**: non-blocking Notification hook (can alert but cannot answer — that's Phase 1);
direct backend posting (breaks outbound-only). Both rejected.

## D2 — No ClaudeDriver timeout; fail-safe DENY as the backstop

**Decision**: The blocking hook is configured with a very long timeout so the instance stays paused
until the operator decides; ClaudeDriver imposes no timeout of its own. If the WSS drops, the hook
connection closes, or Claude Code's own hard hook limit is reached, the agent completes the held
response as **deny** (and the backend marks the request denied). Never auto-approve.
**Rationale**: Honors the clarified "wait until decided" intent as far as the platform allows, while
keeping Constitution Principle I's non-negotiable fail-safe as the ultimate backstop.
**Alternatives**: default-approve on timeout (forbidden); a short ClaudeDriver timeout (contradicts
the clarified decision). Rejected.

## D3 — Which tools require approval: scope via the hook matcher (≈ what Claude Code gates)

**Decision**: Escalate remote approval only for the tool categories Claude Code normally gates —
configured via the `PreToolUse` hook **matcher** (default `Bash|Write|Edit` and other mutating
tools), not read-only tools. The matcher set is configurable.
**Rationale**: The blocking hook fires for all `PreToolUse` events, so "respect Claude Code's own
rules" is approximated by scoping the matcher to the mutating/gated tools — low noise, still safe.
**Alternatives**: escalate every tool call (the rejected "every tool" option — noisy/slow); try to
read Claude Code's internal allowlist per call (not exposed to hooks). Rejected.

## D4 — Push dispatch: a pluggable sender behind a service

**Decision**: `PushService` sends to an operator's registered devices via a `PushSender` interface.
Dev uses a `LoggingPushSender` (zero cost, testable); prod uses an `SnsPushSender` (Amazon SNS mobile
push → FCM/APNs) added when deploying. Devices register/unregister via authenticated REST and are
stored in `push_device`; invalid tokens are pruned; a send failure never blocks the approval path.
**Rationale**: Lets the whole flow be built and tested now without real push credentials, and swaps
in real delivery at deploy (Principle VII cost-aware, FR-009 non-blocking).
**Alternatives**: hard-wire FCM/APNs now (needs credentials + network; untestable here). Rejected.

## D5 — Decision lifecycle: at-most-once, moot-on-stop, audited

**Decision**: `approval_request` moves `pending → approved | denied | moot`. `decide()` applies only
when `pending` (at-most-once); a decision on a non-pending request is a no-op reported as
"already resolved". If the session stops/disappears while pending, the request is marked **moot** and
a **deny** decision is sent to the agent so nothing runs. Every raise + decision is audited.
**Rationale**: Implements FR-004/005/013 and SC-005 (0 double-applies) and the moot edge case.
**Alternatives**: allow re-decision (contradicts at-most-once). Rejected.

## D6 — Mobile: Compose Multiplatform, isolated from the core build

**Decision**: The mobile app is a standalone Compose Multiplatform project under `mobile/`, NOT in
the root `settings.gradle.kts`, sharing the wire contract by construction. It has passkey (WebAuthn)
sign-in, live monitoring parity, approve/deny, and push registration.
**Rationale**: Keeps the core JVM/web build + CI green without Android SDK/Xcode; the mobile app is
built separately with the mobile toolchain. CMP for iOS is production-stable (Phase 0 research).
**Alternatives**: put mobile in the root build (couples CI to Android/iOS toolchains — rejected);
native per-platform apps (no code sharing — rejected).
**Caveat**: this environment has no Android SDK/Xcode/simulator, so the app is delivered as a wired
scaffold; compiling/running it is a follow-up with the mobile toolchain.

## Deferred (later phases)

- Answering arbitrary free-form questions (Phase 4, SDK-managed sessions). Task dispatch (Phase 3).
- Per-operator machine/project authorization scopes (multi-user; deferred per clarification).
