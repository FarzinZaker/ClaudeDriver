# ClaudeDriver — Threat Model & Hardening Checklist

ClaudeDriver is a **remote-code-execution control plane** over a developer fleet: compromising the
backend, or forging a client/agent, means running code on every enrolled machine. This checklist
(FR-012) confirms the required controls from Constitution Principle I are present, and logs gaps.

## Threats

| # | Threat | Impact |
|---|---|---|
| T1 | Backend compromise | Fleet-wide RCE (answer prompts, dispatch tasks, start managed runs) |
| T2 | Forged/replayed client command | Unauthorized approve/answer/dispatch |
| T3 | Rogue agent impersonating a machine | False state, or receiving another machine's commands |
| T4 | Backend exposed to the public internet unhardened | Internet-reachable RCE panel |
| T5 | Credential leak (device cert, operator passkey, hook token) | Impersonation until revoked |
| T6 | Prompt-injection / no-input-as-approval | Agent runs something the operator did not sanction |

## Controls checklist

| Control | Status | Where |
|---|---|---|
| Agent identity = per-device mTLS client cert, verified at the ALB | ✅ Present | Phase 0 (ALB mutual TLS, `DeviceCa`, `TrustService`) |
| Operator auth = self-hosted WebAuthn passkeys, no external IdP | ✅ Present | Phase 0 (`WebAuthnService`) |
| Outbound-only agents; no inbound to dev machines | ✅ Present | Phases 0–4 (agent dials out; loopback-only hook/companion) |
| **Fail-safe** — timeout/disconnect/error ⇒ deny; never auto-approve; never fabricate an answer | ✅ Present | Phase 2 (`PreToolUse` deny-on-close), Phase 4 (`markUnansweredForSession`, no answer synthesis) |
| At-most-once decisions/commands/answers | ✅ Present | Phases 2–4 (`decide`, `applyResult`, `answer` apply only from pending) |
| Complete, hash-chained audit of every consequential action | ✅ Present | Phase 0 (`AuditChain`), used by all phases |
| No secrets in the repo; secrets from `.env`/Secrets Manager; gitleaks gate | ✅ Present | `.gitignore`, `.gitleaks.toml`, CI |
| Credential **rotation** + **revocation** (device cert; machine) | ✅ Present | `EnrollmentService.rotateDeviceCert`, `revokeMachine`, `DeviceStore.unregister` |
| Never internet-exposed without hardening | ⚠️ Deploy-time | AWS is internet-reachable by design; the deploy MUST keep strong operator auth + agent mTLS on (`infra/README.md`) |
| Least-privilege IAM (SSM read scoped, task roles) | ✅ Present (IaC) | `infra/governance.tf` |
| Per-operator machine/project authorization scopes | ⏳ Deferred | Multi-user; single-operator today (documented) |
| Managed-mode companion isolation (local, bridged; API key in env/secret) | ✅ By design | Phase 4 (`companion.py` local; agent bridges) |

## Gaps logged

- **Per-operator scopes** — deferred to multi-user (single operator today; audit + at-most-once +
  authenticated channel apply now).
- **Real-SDK managed mode** — validated at deploy/CI with the SDK runtime + API key, not in the build
  environment (the bridge is exercised with a fake companion; see `agent/companion/companion.py`).
- **Automatic scheduled credential rotation** — manual rotation is present; scheduled rotation is a
  future enhancement.

## Operating rules (non-negotiable)

1. Never expose the backend to the public internet without operator WebAuthn + agent mTLS enabled.
2. Never weaken a fail-safe default without an approved constitution amendment.
3. Rotate/revoke a leaked credential immediately (`rotate-cert`, `revoke`).
