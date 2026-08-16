# Phase 3 Quickstart & Validation

Validates remote control on a running Phase 0–2 stack (backend applies `V4` on boot; the agent runs
the SessionController with a launcher for `claude`).

## Validate each user story

### US2 — Start a new persistent run
1. Pick an enrolled, connected machine + a project path + an initial instruction; `POST
   /machines/{id}/start-run`.
2. Confirm a new session appears in monitoring under that machine within ~10 s (SC-002), still alive.
3. Try an offline machine or a bad path → refused with a reason; nothing starts (FR-006).

### US1 — Dispatch a task to a session
1. With a managed session idle, `POST /sessions/{id}/dispatch {instruction}`.
2. Confirm the session receives it and begins acting; the command shows `delivered` within ~5 s
   (SC-001), live via `control_event`.
3. Dispatch to a session that has ended → `undeliverable` (SC-006); it is not delivered elsewhere.
4. Re-issue a delivery (reconnect) → applied at most once (SC-004).

### US3 — Stop a session
1. `POST /sessions/{id}/stop` on a running managed session → its run ends (graceful → force) and it
   shows stopped within ~10 s (SC-003).
2. A pending approval for it is resolved **moot** (nothing runs).
3. Stop an already-ended session → no-op, reported already stopped.

### US4 — Safety
Every dispatch/start/stop + result is audited (operator, machine, target, action, time, result);
control travels the authenticated device channel; a disconnect mid-action yields undelivered, not a
partial effect.

## Tests
```bash
./gradlew test        # + ControlService (route, at-most-once, undeliverable, audit) Testcontainers;
                      #   the live SmokeTest: start-run → dispatch (delivered) → stop, end to end
cd web && npm test    # session control surface: dispatch box + start/stop
```

The extended **SmokeTest** uses a fake launcher (a stdin-reading process): start-run spawns it, a
dispatch writes an instruction that the process records, and stop terminates it — proving the whole
control plane over real HTTP/WSS + Postgres.
