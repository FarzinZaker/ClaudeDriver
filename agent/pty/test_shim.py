#!/usr/bin/env python3
"""End-to-end check for claude-shim.py: run the real shim under a PTY against a
mock bridge, and assert (1) the shim mirrors the child's output to its terminal
(passthrough), (2) the bridge receives that output as O frames after a
token-valid hello, and (3) input injected by the bridge is executed by the
child. Uses bash as the stand-in for `claude` via CLAUDEDRIVER_SHIM_TARGET."""
import json
import os
import pty
import select
import socket
import struct
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
SHIM = os.path.join(HERE, "claude-shim.py")


def recv_exactly(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def read_frame(sock):
    head = recv_exactly(sock, 5)
    if head is None:
        return None
    kind = chr(head[0])
    (length,) = struct.unpack(">I", head[1:5])
    payload = recv_exactly(sock, length) if length else b""
    return kind, payload


def main():
    tmp = tempfile.mkdtemp(prefix="cd-shim-")
    os.makedirs(os.path.join(tmp, ".claudedriver"), exist_ok=True)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]
    token = "test-token-abc123"
    with open(os.path.join(tmp, ".claudedriver", "pty-endpoint"), "w") as fh:
        json.dump({"port": port, "token": token}, fh)

    bridge = {"hello": None, "output": bytearray(), "conn": None}

    def serve():
        conn, _ = srv.accept()
        bridge["conn"] = conn
        while True:
            frame = read_frame(conn)
            if frame is None:
                break
            kind, payload = frame
            if kind == "H":
                bridge["hello"] = json.loads(payload)
            elif kind == "O":
                bridge["output"].extend(payload)
            elif kind == "X":
                break

    threading.Thread(target=serve, daemon=True).start()

    # Run the shim under a PTY (so it takes the bridging path, not the exec-native fallback).
    env = dict(os.environ, HOME=tmp, CLAUDEDRIVER_SHIM_TARGET="/bin/bash")
    pid, master = pty.fork()
    if pid == 0:
        os.environ.clear()
        os.environ.update(env)
        os.execv(sys.executable, [sys.executable, SHIM, "--norc", "-i"])
        os._exit(127)

    term_output = bytearray()
    injected = False
    deadline = time.time() + 8
    while time.time() < deadline:
        r, _, _ = select.select([master], [], [], 0.2)
        if master in r:
            try:
                data = os.read(master, 65536)
            except OSError:
                break
            if not data:
                break
            term_output.extend(data)
        # Once the bridge is connected and we've seen some output, inject a command.
        if not injected and bridge["conn"] is not None and len(bridge["output"]) > 0:
            payload = b"echo INJECTED_$((3*4))\n"
            bridge["conn"].sendall(b"I" + struct.pack(">I", len(payload)) + payload)
            injected = True
        if b"INJECTED_12" in bytes(term_output) and b"INJECTED_12" in bytes(bridge["output"]):
            break
    try:
        os.write(master, b"exit\n")
    except OSError:
        pass

    term = bytes(term_output).decode(errors="replace")
    cap = bytes(bridge["output"]).decode(errors="replace")
    hello = bridge["hello"]

    checks = {
        "bridge received hello": hello is not None,
        "hello carried the token": hello and hello.get("token") == token,
        "hello carried the cwd": hello and "cwd" in hello,
        "injection ran in the child (seen on terminal)": "INJECTED_12" in term,
        "bridge mirrored the injected output": "INJECTED_12" in cap,
    }
    ok = all(checks.values())
    for name, passed in checks.items():
        print(f"  [{'PASS' if passed else 'FAIL'}] {name}")
    print("RESULT", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
