#!/usr/bin/env python3
"""Drive the REAL claude shim under a PTY against the REAL agent bridge, to prove
the live-terminal pipeline end to end (shim -> agent -> prod backend -> dashboard).

Targets /bin/bash (via CLAUDEDRIVER_SHIM_TARGET) so we don't consume a real Claude
session. Emits a unique marker, then stays alive so a browser can attach, read the
mirrored output, and inject input. Reads a control file for injected-echo checks."""
import os
import pty
import select
import signal
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SHIM = os.path.join(os.path.dirname(HERE), "agent", "pty", "claude-shim.py")
MARKER = f"CD_LIVE_MARKER_{os.getpid()}"
DURATION = int(sys.argv[1]) if len(sys.argv) > 1 else 180
CAP = os.path.join(HERE, "live-shim-capture.log")

pid, master = pty.fork()
if pid == 0:
    env = dict(os.environ, CLAUDEDRIVER_SHIM_TARGET="/bin/bash")
    os.environ.clear(); os.environ.update(env)
    # Interactive bash with a stable prompt so injected commands are visible.
    os.execv(sys.executable, [sys.executable, SHIM, "--norc", "-i"])
    os._exit(127)

print(f"MARKER={MARKER}")
sys.stdout.flush()
cap = open(CAP, "wb")
time.sleep(1.0)
os.write(master, b"PS1='cd$ '\n")
time.sleep(0.3)
os.write(master, f"echo {MARKER}\n".encode())

deadline = time.time() + DURATION
try:
    while time.time() < deadline:
        r, _, _ = select.select([master], [], [], 0.5)
        if master in r:
            try:
                data = os.read(master, 65536)
            except OSError:
                break
            if not data:
                break
            cap.write(data); cap.flush()
finally:
    try:
        os.write(master, b"exit\n")
    except OSError:
        pass
    try:
        os.kill(pid, signal.SIGTERM)
    except OSError:
        pass
    cap.close()
