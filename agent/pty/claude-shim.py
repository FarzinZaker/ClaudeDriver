#!/usr/bin/env python3
"""Transparent `claude` shim (POSIX).

Runs the *real* `claude` inside a pseudo-terminal so ClaudeDriver's agent can
mirror the live output to the dashboard and inject operator keystrokes — while
the local user sees and drives an ordinary terminal. It is deliberately
fail-open: if the agent is not running, the endpoint is unreadable, the socket
refuses, or stdin/stdout is not a TTY, the shim `exec`s the real binary and adds
nothing at all. Bridging is a bonus layered on top of a normal `claude`, never a
prerequisite for it.

Wire protocol (framed: 1 type byte + 4-byte big-endian length + payload):
  shim -> agent : H hello{token,cwd,sid,cols,rows} · O output-bytes · R resize{cols,rows} · X exit{code}
  agent -> shim : I input-bytes-to-inject
"""
import json
import os
import pty
import select
import signal
import socket
import struct
import sys
import termios
import tty


def real_claude() -> str:
    """The next `claude` on PATH that is not this shim (resolved by inode)."""
    override = os.environ.get("CLAUDEDRIVER_SHIM_TARGET")  # test seam only
    if override:
        return override
    me = os.path.realpath(__file__)
    for d in os.environ.get("PATH", "").split(os.pathsep):
        cand = os.path.join(d, "claude")
        if os.path.isfile(cand) and os.access(cand, os.X_OK) and os.path.realpath(cand) != me:
            return cand
    # Common install locations, in case PATH is minimal.
    home = os.path.expanduser("~")
    for cand in (f"{home}/.local/bin/claude", f"{home}/.claude/local/claude",
                 "/opt/homebrew/bin/claude", "/usr/local/bin/claude", "/usr/bin/claude"):
        if os.path.realpath(cand) != me and os.access(cand, os.X_OK):
            return cand
    return "claude"  # last resort; will error the same way a bare `claude` would


def exec_real(args):
    """Replace this process with the real claude — zero overhead, fully transparent."""
    real = real_claude()
    os.execv(real, [real] + args)


def read_endpoint():
    """(host, port, token) the agent is listening on, or None if unavailable."""
    path = os.path.join(os.path.expanduser("~"), ".claudedriver", "pty-endpoint")
    try:
        with open(path) as fh:
            data = json.load(fh)
        return "127.0.0.1", int(data["port"]), str(data["token"])
    except Exception:
        return None


def winsize(fd):
    try:
        import fcntl
        rows, cols, _, _ = struct.unpack("HHHH", fcntl.ioctl(fd, termios.TIOCGWINSZ, b"\0" * 8))
        return cols, rows
    except Exception:
        return 80, 24


def set_winsize(fd, cols, rows):
    try:
        import fcntl
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    except Exception:
        pass


def send_frame(sock, kind: bytes, payload: bytes):
    try:
        sock.sendall(kind + struct.pack(">I", len(payload)) + payload)
    except Exception:
        pass


def main():
    args = sys.argv[1:]

    # Only bridge a genuine interactive terminal; anything else runs native.
    if not (sys.stdin.isatty() and sys.stdout.isatty()):
        exec_real(args)
    endpoint = read_endpoint()
    if endpoint is None:
        exec_real(args)
    host, port, token = endpoint
    try:
        agent = socket.create_connection((host, port), timeout=1.0)
        agent.settimeout(None)
    except Exception:
        exec_real(args)  # agent down / refusing → plain claude

    real = real_claude()
    cols, rows = winsize(sys.stdin.fileno())
    sid = f"pty-{os.getpid()}"

    pid, master = pty_fork_exec(real, args, cols, rows)

    send_frame(agent, b"H", json.dumps(
        {"token": token, "cwd": os.getcwd(), "sid": sid, "cols": cols, "rows": rows}).encode())

    old = termios.tcgetattr(sys.stdin.fileno())
    tty.setraw(sys.stdin.fileno())

    def on_winch(_sig, _frm):
        c, r = winsize(sys.stdin.fileno())
        set_winsize(master, c, r)
        send_frame(agent, b"R", json.dumps({"cols": c, "rows": r}).encode())
    signal.signal(signal.SIGWINCH, on_winch)

    inbuf = b""  # partial frames from the agent
    code = 0
    try:
        while True:
            r, _, _ = select.select([sys.stdin, master, agent], [], [])
            if sys.stdin in r:
                data = os.read(sys.stdin.fileno(), 65536)
                if data:
                    os.write(master, data)
            if master in r:
                try:
                    data = os.read(master, 65536)
                except OSError:
                    data = b""
                if not data:
                    break  # child exited / PTY closed
                os.write(sys.stdout.fileno(), data)  # user sees it
                send_frame(agent, b"O", data)         # dashboard sees it too
            if agent in r:
                try:
                    chunk = agent.recv(65536)
                except OSError:
                    chunk = b""
                if not chunk:
                    agent = None  # agent went away; keep running native from here
                    continue
                inbuf += chunk
                inbuf = drain_inject(inbuf, master)
            if agent is None:
                # Fall back to a plain passthrough loop with the agent gone.
                r2, _, _ = select.select([sys.stdin, master], [], [])
                if sys.stdin in r2:
                    d = os.read(sys.stdin.fileno(), 65536)
                    if d:
                        os.write(master, d)
                if master in r2:
                    try:
                        d = os.read(master, 65536)
                    except OSError:
                        d = b""
                    if not d:
                        break
                    os.write(sys.stdout.fileno(), d)
    finally:
        termios.tcsetattr(sys.stdin.fileno(), termios.TCSADRAIN, old)
        try:
            _, status = os.waitpid(pid, 0)
            code = os.waitstatus_to_exitcode(status)
        except Exception:
            pass
        if agent is not None:
            send_frame(agent, b"X", json.dumps({"code": code}).encode())
            try:
                agent.close()
            except Exception:
                pass
    sys.exit(code if isinstance(code, int) and code >= 0 else 1)


def drain_inject(buf: bytes, master: int) -> bytes:
    """Parse whole frames from the agent; only 'I' (inject) frames are honored."""
    while len(buf) >= 5:
        kind = buf[0:1]
        (length,) = struct.unpack(">I", buf[1:5])
        if len(buf) < 5 + length:
            break
        payload = buf[5:5 + length]
        buf = buf[5 + length:]
        if kind == b"I":
            os.write(master, payload)
    return buf


def pty_fork_exec(real: str, args, cols: int, rows: int):
    """Fork a child running `real` in a fresh PTY; return (child_pid, master_fd)."""
    pid, master = pty.fork()
    if pid == 0:
        os.execv(real, [real] + args)
        os._exit(127)
    set_winsize(master, cols, rows)
    return pid, master


if __name__ == "__main__":
    main()
