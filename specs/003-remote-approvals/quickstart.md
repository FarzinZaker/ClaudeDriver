# Phase 2 Quickstart & Validation

Validates remote approvals + push on top of a running Phase 0–1 stack (backend applies `V3` on boot;
agent installs the blocking approval hook alongside the Phase 1 hooks).

## Validate each user story

### US1 — Approve / deny a permission prompt remotely
1. In a monitored Claude Code instance, run an action that matches the approval matcher (e.g. a Bash
   command). Claude Code **pauses**.
2. In the dashboard, confirm a **pending approval** appears within ~5 s with machine, project, and the
   exact command (SC-001).
3. Click **Approve** → the instance proceeds and runs the tool (SC-002).
4. Repeat and click **Deny** → the instance does not run the tool.
5. While one is pending, stop the Claude Code instance → the approval resolves **moot** and nothing
   runs (edge/US1 #5).
6. While one is pending, kill the agent (drop the WSS) → the held hook resolves to **deny** on the
   machine (fail-safe / SC-003).
7. Decide the same approval twice → the second is `409 already_resolved`; no double-apply (SC-005).

### US2 — Push + act from the phone
1. Register a device: `POST /devices {token, platform}` (the app does this on sign-in).
2. Raise an approval → confirm the push sender recorded a notification for the device (LoggingPush
   sender in dev; SNS in prod). Tapping opens the app to the approval (on device).
3. Approve/deny from the app → takes effect identically to the web (SC-007).

### US3 — Mobile monitoring parity
Open the app; machines/sessions/alerts appear and update live, matching the web (mobile app requires
the Android/iOS toolchain to build/run — see `mobile/README.md`).

### US4 — Safety
Every approve/deny is written to the audit trail with operator, machine, session, action, decision,
and time (FR-013). Force timeout/disconnect → deny (SC-003).

## Tests
```bash
./gradlew test        # + ApprovalService (at-most-once, moot, fail-safe), push registration/dispatch;
                      #   Testcontainers integration; the live SmokeTest extended to prove approve AND deny
cd web && npm test    # approvals panel render + decide
```

The extended **SmokeTest** drives a real held blocking hook → `approval_request` over real WSS →
`POST /approvals/{id}/decide` → the held hook returns allow/deny — proving the whole loop end to end.
