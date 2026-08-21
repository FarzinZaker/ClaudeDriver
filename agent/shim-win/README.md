# Transparent `claude` shim — Windows (ConPTY)

The Windows counterpart to `agent/pty/claude-shim.py` (POSIX). It runs the real
`claude` inside a **ConPTY** (Win32 pseudo console) so the agent can mirror the
live console to the dashboard and inject operator keystrokes, while the local
user drives an ordinary console.

- **Zero runtime deps.** A single static `claude.exe` (stdlib-only Go, no cgo,
  no external modules, no bundled Python/JRE). Ships like the POSIX script does.
- **Same wire protocol** as the POSIX shim and the agent `PtyBridge`
  (`H`/`O`/`R`/`X` + `I`), so it reuses the exact bridge/backend/dashboard path.
- **Fail-open**: no console, no agent endpoint, or a refused socket → it runs the
  real `claude` with inherited handles and adds nothing.

## Build

    ./build.sh          # → dist/claude-amd64.exe, dist/claude-arm64.exe

Cross-compiles from any host (macOS/Linux). Verified: `go test ./...` (shared
wire protocol) and `GOOS=windows go build` for amd64 + arm64.

## Integration (ShimInstaller, Windows branch)

On Windows the agent installs the shim by:
1. Extracting the arch-matched `claude.exe` to `%USERPROFILE%\.claudedriver\bin\`.
2. Prepending that dir to the **user** `PATH` (HKCU\Environment) — new consoles
   pick it up; running ones are untouched, mirroring the POSIX rc-block behavior.
3. Refusing to install unless a real `claude` exists elsewhere on PATH to wrap.

The bridge writes its handshake to `%USERPROFILE%\.claudedriver\pty-endpoint`
(same relative path the shim reads).

## Validation status

- ✅ Compiles for windows/amd64 + windows/arm64, stdlib-only, `go vet` clean.
- ✅ Wire protocol + endpoint discovery + arg quoting unit-tested on host.
- ⏳ **Needs an on-Windows smoke test**: ConPTY create/spawn/resize and the live
  round-trip (start `claude`, see it in the dashboard, type into it) can only be
  exercised on a real Windows machine.
