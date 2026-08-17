# Phase 4 Quickstart & Validation

Validates managed sessions + hardening on a running Phase 0–3 stack (backend applies `V5`; the agent
launches the SDK companion, or a fake companion in tests).

## Validate each user story

### US2/US1 — Managed session + answer an arbitrary question
1. `POST /machines/{id}/start-managed {projectPath, instruction}` → a managed session appears in
   monitoring (marked managed).
2. When the session asks a free-form question, confirm it appears in `GET /questions` (pending) within
   ~5 s (SC-001) with the question text + context.
3. `POST /questions/{id}/answer {answer}` → the answer reaches the session and it continues; the
   question resolves `answered` (SC-002). The system never fabricates an answer (SC-003).
4. Instead answer with `{cancel:true}` → the session is told no answer was given and handles it safely.
5. Decide the same question twice → `409 already_resolved` (at-most-once).

### US3 — Transcript, history & search
1. `GET /sessions/{id}/transcript` → the full ordered conversation; it updates live via
   `transcript_event`.
2. `GET /search?q=<term>` → matching sessions/snippets, paged, without blocking live control (SC-004).

### US4 — Hardening
1. `POST /machines/{id}/rotate-cert` → old device certs revoked, a fresh enrollment code issued; the
   old identity is refused, the re-enrolled one works (SC-005).
2. Review `docs/HARDENING.md` (threat-model checklist — each control present) and `docs/COST.md` (cost
   within the small-fleet envelope) (SC-006).

## Tests
```bash
./gradlew test        # + ManagedService (answer at-most-once, transcript store, search) Testcontainers;
                      #   the live SmokeTest: start_managed → fake companion asks a question →
                      #   POST /questions answer → companion records the answer → ended
cd web && npm test    # managed-session view: transcript + questions inbox + answer box + search
```

The extended **SmokeTest** uses a **fake companion** (a script speaking the bridge protocol) — it
asks a question, the test answers it, and the companion records the answer — proving the full
managed question/answer loop over real HTTP/WSS + Postgres, without the real SDK. Real-SDK validation
is a deploy/CI-with-key step (see `agent/companion/companion.py`).
