"""Interactive terminal: one PTY per WebSocket, attached to a tmux session.

Off unless the operator sets ``BRIDGE_TERMINAL=1``: a shell as the bridge user
is far more than the Hermes tools (the user may, for example, be in the
``docker`` group). Hermes approvals do not apply to it.

The child is started without a shell and with a minimal environment, so it
does not inherit the Hermes API key (``HERMES_API_KEY``) or other bridge
settings. (Running as the bridge user it can still read what that user can,
e.g. the bridge's own ``/proc/<pid>/environ`` and the Hermes env file: the
terminal is meant for the server owner.)
``setsid --ctty`` makes the PTY the controlling terminal, so a resize reaches
tmux as SIGWINCH. Closing the WebSocket ends only the tmux client; the tmux
session (and what runs in it) stays until the user exits it.
"""

from __future__ import annotations

import asyncio
import fcntl
import os
import pwd
import shutil
import signal
import struct
import termios

SESSION_NAME = "orsik"
MAX_TERMINALS = 4
# Bound of one client message (typing, a paste, a resize).
MAX_INPUT_BYTES = 64 * 1024
# Bound of one output read; the sender awaits each WebSocket write, so a slow
# client throttles the PTY instead of the bridge buffering without limit.
READ_CHUNK = 64 * 1024
MAX_COLS = 500
MAX_ROWS = 300

_ENABLE_ERROR = "BRIDGE_TERMINAL must be 1 (on), 0 or empty (off)"


def parse_enabled(raw: str | None) -> bool:
    value = (raw or "").strip()
    if value in ("", "0"):
        return False
    if value == "1":
        return True
    raise SystemExit(_ENABLE_ERROR)


def default_command() -> tuple[str, ...]:
    """The tmux attach command; refuses to start when a tool is missing."""
    tmux = shutil.which("tmux")
    setsid = shutil.which("setsid")
    if tmux is None or setsid is None:
        raise SystemExit("BRIDGE_TERMINAL=1 needs tmux and setsid (util-linux) on the PATH")
    return (setsid, "--ctty", tmux, "new-session", "-A", "-s", SESSION_NAME)


def child_env() -> dict[str, str]:
    """A login-like environment; nothing of the bridge's own environment
    except PATH and the locale."""
    user = pwd.getpwuid(os.geteuid())
    env = {
        "HOME": user.pw_dir,
        "USER": user.pw_name,
        "LOGNAME": user.pw_name,
        "SHELL": user.pw_shell or "/bin/sh",
        "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
        "TERM": "xterm-256color",
        "LANG": os.environ.get("LANG") or "C.UTF-8",
    }
    return env


def parse_resize(payload: object) -> tuple[int, int] | None:
    """``{"type": "resize", "cols": n, "rows": n}`` with sane bounds, else None."""
    if not isinstance(payload, dict) or set(payload) != {"type", "cols", "rows"}:
        return None
    if payload["type"] != "resize":
        return None
    cols, rows = payload["cols"], payload["rows"]
    # bool is an int subclass; a JSON true is not a size.
    if type(cols) is not int or type(rows) is not int:
        return None
    if not (1 <= cols <= MAX_COLS and 1 <= rows <= MAX_ROWS):
        return None
    return cols, rows


class Pty:
    """A child process on a fresh PTY with non-blocking master I/O."""

    def __init__(self, master: int, process: asyncio.subprocess.Process) -> None:
        self.master = master
        self.process = process
        self._closed = False

    @classmethod
    async def spawn(cls, command: tuple[str, ...], cols: int = 80, rows: int = 24) -> "Pty":
        master, slave = os.openpty()
        try:
            set_size(master, cols, rows)
            os.set_blocking(master, False)
            env = child_env()
            # No start_new_session: "setsid --ctty" creates the session itself
            # (a group leader would make setsid fork and return at once).
            process = await asyncio.create_subprocess_exec(
                *command,
                stdin=slave, stdout=slave, stderr=slave,
                env=env,
                cwd=env["HOME"],
                close_fds=True,
            )
        except BaseException:
            os.close(master)
            raise
        finally:
            os.close(slave)
        return cls(master, process)

    async def read(self) -> bytes:
        """The next output chunk; b"" once the child closed its side."""
        loop = asyncio.get_running_loop()
        while True:
            try:
                return os.read(self.master, READ_CHUNK)
            except BlockingIOError:
                pass
            except OSError:
                # EIO: every slave fd is closed (the child exited).
                return b""
            ready = loop.create_future()
            loop.add_reader(self.master, lambda: ready.done() or ready.set_result(None))
            try:
                await ready
            finally:
                loop.remove_reader(self.master)

    async def write(self, data: bytes) -> None:
        loop = asyncio.get_running_loop()
        view = memoryview(data)
        while view:
            try:
                written = os.write(self.master, view)
                view = view[written:]
                continue
            except BlockingIOError:
                pass
            ready = loop.create_future()
            loop.add_writer(self.master, lambda: ready.done() or ready.set_result(None))
            try:
                await ready
            finally:
                loop.remove_writer(self.master)

    def resize(self, cols: int, rows: int) -> None:
        set_size(self.master, cols, rows)

    async def close(self) -> None:
        """Ends the tmux client (HUP, then KILL); the session stays."""
        if self._closed:
            return
        self._closed = True
        if self.process.returncode is None:
            self._signal(signal.SIGHUP)
            try:
                await asyncio.wait_for(self.process.wait(), timeout=2.0)
            except TimeoutError:
                self._signal(signal.SIGKILL)
                await self.process.wait()
        os.close(self.master)

    def _signal(self, sig: int) -> None:
        # The child's own session/group once setsid ran; before that (or for
        # a command without setsid) only the child itself.
        try:
            os.killpg(self.process.pid, sig)
        except ProcessLookupError:
            try:
                self.process.send_signal(sig)
            except ProcessLookupError:
                pass


def set_size(fd: int, cols: int, rows: int) -> None:
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
