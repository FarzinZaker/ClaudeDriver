# Phase 1 Quickstart & Validation

Validates the monitoring MVP on top of a running Phase 0 stack. Assumes backend + Postgres + web +
an enrolled, connected agent are up (see Phase 0 quickstart) and Claude Code is installed on the
agent's machine.

## Setup

```bash
./gradlew :backend:run          # backend (applies V2 migration on boot)
cd web && npm run dev           # dashboard
./gradlew :agent:run            # enrolled agent: starts process monitor + localhost hook receiver,
                                #   installs the managed Claude Code hooks block
```

The agent, on connect, installs a managed hooks section into the user Claude Code `settings.json`
pointing at its localhost receiver. Verify it merged (did not overwrite your hooks):

```bash
grep -A3 'claudedriver-managed' ~/.claude/settings.json     # managed marker present
```

## Validate each user story

### US1 — See live Claude Code instances per machine
1. Start Claude Code in a project on the enrolled machine.
2. In the dashboard, confirm the machine shows a session for that project within ~5 s (SC-001).
3. Exit Claude Code → the session shows finished/stopped (not silently gone).
4. Stop the agent (simulate offline) → its sessions show **stale** within ~30 s (SC-005), not active.

### US2 — Be alerted when an instance needs attention
1. In a monitored Claude Code instance, trigger a permission prompt (e.g. a Bash command needing
   approval) or a question.
2. Confirm an **attention alert** appears in the inbox within ~5 s (SC-002) with machine, project,
   and a summary of the request; exactly one alert (SC-003).
3. Answer it at the terminal → the alert **auto-resolves** within ~5 s and leaves the active inbox.
4. Let an instance finish a turn / run a tool → confirm **no** attention alert is raised (SC-004);
   a completion shows as a low-urgency signal only.
5. `POST /alerts/{id}/ack` (or click Acknowledge) → status becomes acknowledged; an audit row exists.

### US3 — Inspect session state & recent activity
1. Open a waiting session → see its state, project, last-active time, and the recent event that
   caused the wait.
2. Resolve at the terminal → watch the state transition and the history update live.

### US4 — Safe setup/teardown
1. Re-run the agent → the managed hooks block is unchanged (idempotent, no duplicates) (SC-006).
2. Confirm your pre-existing Claude Code hooks/settings are still present.
3. Disable the machine / run teardown → the managed block is removed; your settings remain.

## Tests

```bash
./gradlew test        # + classification, session state machine, hook-config idempotency,
                      #   Testcontainers: activity_event -> session + alert -> resolve, ack, staleness
cd web && npm test    # session cards + alert inbox render/update
```

Resilience check: stop the backend while Claude Code runs → Claude Code is unaffected (hook posts to
the agent are non-blocking); on reconnect the agent forwards buffered events and state reconciles.
