"""Adversarial tests for the bridge terminal (GET /v1/terminal).

Security review T13. Every child command here is harmless (sh, cat, sleep, a
tiny Python line reader); tmux is never started. Findings and their severity
are written up in REPORT-T13.md at the worktree root.
"""

from __future__ import annotations

import asyncio
import base64
import logging
import os
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import tracemalloc
import unittest
from unittest import mock

from aiohttp import WSMsgType, WSServerHandshakeError
from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import server as bridge_server
from orsik_mobile_bridge import terminal

PY = sys.executable
UPSTREAM = "http://127.0.0.1:9"
ECHO = ("sh", "-c", "stty raw -echo; exec cat")
SIZE_REPORTER = (
    PY,
    "-c",
    "import os,sys\nfor line in sys.stdin:\n    print('size', *os.get_terminal_size(0), flush=True)\n",
)


def fd_targets() -> list[str]:
    targets = []
    for name in os.listdir("/proc/self/fd"):
        try:
            targets.append(os.readlink(f"/proc/self/fd/{name}"))
        except OSError:
            pass
    return targets


def pty_fds() -> list[str]:
    return [target for target in fd_targets() if target == "/dev/ptmx"]


def pidfd_count() -> int:
    return sum("pidfd" in target for target in fd_targets())


def frame(opcode: int, data: bytes, fin: bool = True) -> bytes:
    first = (0x80 if fin else 0) | opcode
    mask = os.urandom(4)
    length = len(data)
    if length < 126:
        header = bytes((first, 0x80 | length))
    elif length < 65536:
        header = bytes((first, 0x80 | 126)) + struct.pack(">H", length)
    else:
        header = bytes((first, 0x80 | 127)) + struct.pack(">Q", length)
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(data))
    return header + mask + masked


class RawWebSocket:
    """A minimal RFC 6455 client, for what aiohttp's client API cannot send
    (fragmentation, knowingly invalid UTF-8, a bare PING) and for RSTs."""

    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.reader = reader
        self.writer = writer
        self.response_head = b""

    @classmethod
    async def connect(cls, client: TestClient, token: str, *, headers=None) -> "RawWebSocket":
        reader, writer = await asyncio.open_connection(client.server.host, client.server.port)
        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            "GET /v1/terminal HTTP/1.1\r\n"
            f"Host: {client.server.host}:{client.server.port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            f"Authorization: Bearer {token}\r\n"
        )
        for name, value in (headers or {}).items():
            request += f"{name}: {value}\r\n"
        writer.write((request + "\r\n").encode())
        await writer.drain()
        raw = cls(reader, writer)
        raw.response_head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 5)
        return raw

    @property
    def status(self) -> int:
        return int(self.response_head.split(b" ")[1])

    async def send(self, opcode: int, data: bytes, *, fin: bool = True) -> None:
        self.writer.write(frame(opcode, data, fin))
        await self.writer.drain()

    async def _read_frame(self) -> tuple[int, bytes]:
        first, second = await self.reader.readexactly(2)
        opcode = first & 0x0F
        length = second & 0x7F
        if length == 126:
            length = struct.unpack(">H", await self.reader.readexactly(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", await self.reader.readexactly(8))[0]
        masked = bool(second & 0x80)
        mask = await self.reader.readexactly(4) if masked else b"\x00\x00\x00\x00"
        data = await self.reader.readexactly(length)
        if masked:
            data = bytes(byte ^ mask[index % 4] for index, byte in enumerate(data))
        return opcode, data

    async def read_frame(self, timeout: float = 5.0) -> tuple[int, bytes]:
        return await asyncio.wait_for(self._read_frame(), timeout)

    async def data_until(self, needle: bytes, timeout: float = 5.0) -> bytes:
        seen = b""
        while needle not in seen:
            opcode, data = await self.read_frame(timeout)
            if opcode == 0x8:
                raise AssertionError(f"closed before {needle!r}: {data!r}")
            if opcode in (0x1, 0x2):
                seen += data
        return seen

    async def wait_close(self, timeout: float = 5.0) -> int | None:
        while True:
            opcode, data = await self.read_frame(timeout)
            if opcode == 0x8:
                code = int.from_bytes(data[:2], "big") if len(data) >= 2 else 1000
                try:
                    self.writer.write(frame(0x8, struct.pack(">H", code)))
                    await self.writer.drain()
                except (ConnectionError, OSError):
                    pass
                return code

    def abort(self) -> None:
        sock = self.writer.get_extra_info("socket")
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        except OSError:
            pass
        self.writer.transport.abort()

    def close(self) -> None:
        self.abort()


async def start_bridge(command):
    state = bridge_server.BridgeState(
        upstream=UPSTREAM, master_key="master_key_test", terminal_command=command)
    client = TestClient(TestServer(bridge_server.create_app(state)))
    await client.start_server()
    issued = state.codes.issue()
    resp = await client.post("/v1/devices/pair", json={"code": issued[0]})
    token = (await resp.json())["device_key"]
    state.store.set_terminal_access(state.tokens.lookup(token).device_id, True)
    return state, client, token


class TerminalCase(unittest.IsolatedAsyncioTestCase):
    command = ECHO

    async def asyncSetUp(self):
        self.state, self.client, self.token = await start_bridge(self.command)
        self._sessions: list = []
        self._raws: list[RawWebSocket] = []
        self._bridges = [(self.state, self.client)]
        self._pids: list[int] = []

    async def asyncTearDown(self):
        for ws in self._sessions:
            try:
                await asyncio.wait_for(ws.close(), 5)
            except Exception:
                pass
        for raw in self._raws:
            raw.close()
        for pid in self._pids:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        for state, client in reversed(self._bridges):
            try:
                await client.close()
            finally:
                state.store.close()

    def auth(self, token: str | None = None) -> dict[str, str]:
        return {"Authorization": "Bearer " + (token or self.token)}

    def upgrade_headers(self, token: str | None = None, **extra: str) -> dict[str, str]:
        headers = {
            "Connection": "Upgrade",
            "Upgrade": "websocket",
            "Sec-WebSocket-Version": "13",
            "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==",
            **extra,
        }
        if token is not False:
            headers.update(self.auth(token))
        return headers

    async def connect(self, token: str | None = None, **kwargs):
        ws = await self.client.ws_connect("/v1/terminal", headers=self.auth(token), **kwargs)
        self._sessions.append(ws)
        return ws

    async def raw(self, token: str | None = None, **kwargs) -> RawWebSocket:
        raw = await RawWebSocket.connect(self.client, token or self.token, **kwargs)
        self._raws.append(raw)
        return raw

    async def extra_bridge(self, command):
        state, client, token = await start_bridge(command)
        self._bridges.append((state, client))
        return state, client, token

    async def read_until(self, ws, needle: bytes, timeout: float = 5.0) -> bytes:
        seen = b""
        while needle not in seen:
            msg = await asyncio.wait_for(ws.receive(), timeout)
            if msg.type != WSMsgType.BINARY:
                self.fail(f"connection ended before {needle!r}: {msg!r}")
            seen += msg.data
        return seen

    async def next_close(self, ws, timeout: float = 5.0):
        while True:
            msg = await asyncio.wait_for(ws.receive(), timeout)
            if msg.type != WSMsgType.BINARY:
                return msg

    async def wait_slots(self, count: int = 0, timeout: float = 6.0, state=None) -> None:
        state = state or self.state
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while state.active_terminals != count:
            if loop.time() > deadline:
                self.fail(f"active_terminals={state.active_terminals}, expected {count}")
            await asyncio.sleep(0.02)

    async def wait_pid_gone(self, pid: int, timeout: float = 5.0) -> None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while os.path.exists(f"/proc/{pid}"):
            if loop.time() > deadline:
                self.fail(f"child {pid} outlived its WebSocket")
            await asyncio.sleep(0.02)


class AuthTest(TerminalCase):
    async def test_no_credential_shapes_start_a_process(self):
        bad = (
            {},
            {"Authorization": ""},
            {"Authorization": "Bearer"},
            {"Authorization": "Bearer "},
            {"Authorization": "Bearer " + "a" * 64},
            {"Authorization": "Bearer hd_" + "x" * 40},
            {"Authorization": "Basic dXNlcjpwYXNz"},
            {"Authorization": "Bearer\ttok"},
        )
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            for headers in bad:
                resp = await self.client.get(
                    "/v1/terminal", headers={**self.upgrade_headers(token=False), **headers})
                self.assertEqual(resp.status, 401, headers)
                self.assertEqual((await resp.json())["error"]["code"], "invalid_credentials")
            spawn.assert_not_called()
        self.assertEqual(self.state.active_terminals, 0)
        self.assertEqual(pty_fds(), [])

    async def test_a_revoked_device_cannot_open_a_new_terminal(self):
        issued = self.state.codes.issue()
        resp = await self.client.post("/v1/devices/pair", json={"code": issued[0]})
        second = (await resp.json())["device_key"]
        resp = await self.client.post(
            "/v1/devices/self/revoke", headers={"Authorization": "Bearer " + second})
        self.assertEqual(resp.status, 200)
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            resp = await self.client.get(
                "/v1/terminal", headers=self.upgrade_headers(second))
            self.assertEqual(resp.status, 401)
            spawn.assert_not_called()

    async def test_a_token_in_the_query_string_is_never_accepted(self):
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            resp = await self.client.get(
                "/v1/terminal?token=" + self.token, headers=self.upgrade_headers())
            self.assertEqual(resp.status, 403)
            self.assertEqual((await resp.json())["error"]["code"], "query_not_allowed")
            resp = await self.client.get("/v1/terminal?token=" + self.token)
            self.assertEqual(resp.status, 401)
            resp = await self.client.get("/v1/terminal?x=1", headers=self.upgrade_headers())
            self.assertEqual(resp.status, 403)
            spawn.assert_not_called()

    async def test_the_bearer_prefix_is_case_insensitive_but_the_token_is_not(self):
        for prefix in ("bearer ", "BEARER ", "BeArEr "):
            ws = await self.client.ws_connect(
                "/v1/terminal", headers={"Authorization": prefix + self.token})
            await ws.send_bytes(b"x")
            await self.read_until(ws, b"x")
            await ws.close()
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            resp = await self.client.get(
                "/v1/terminal", headers=self.upgrade_headers(self.token.upper()))
            self.assertEqual(resp.status, 401)
            resp = await self.client.get(
                "/v1/terminal", headers=self.upgrade_headers(self.token + "x"))
            self.assertEqual(resp.status, 401)
            spawn.assert_not_called()

    async def test_a_query_token_never_reaches_the_access_log(self):
        # The bridge's own access logger (main() installs it) writes method,
        # path and status only; a key-shaped path segment is redacted too.
        logger = logging.getLogger("orsik.test.access")
        access = bridge_server._AccessLogger(logger, "")
        request = mock.Mock(method="GET", path="/v1/terminal/" + self.token, path_qs="/v1/terminal?token=" + self.token)
        with self.assertLogs("orsik.test.access", level="INFO") as logs:
            access.log(request, mock.Mock(status=403), 0.01)
        line = "\n".join(logs.output)
        self.assertNotIn(self.token, line)
        self.assertIn("GET [redacted] 403", line)

    async def test_a_bad_token_with_a_query_is_answered_as_bad_credentials(self):
        resp = await self.client.get(
            "/v1/terminal?token=" + self.token, headers=self.upgrade_headers("hd_" + "y" * 40))
        self.assertEqual(resp.status, 401)

    async def test_a_plain_get_never_starts_a_process(self):
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            resp = await self.client.get("/v1/terminal", headers=self.auth())
            self.assertEqual(resp.status, 400)
            self.assertEqual((await resp.json())["error"]["code"], "websocket_required")
            spawn.assert_not_called()
        self.assertEqual(self.state.active_terminals, 0)

    async def test_head_with_upgrade_headers_does_not_start_a_process(self):
        # aiohttp 3.14's handshake no longer refuses a non-GET method; the
        # route is GET only, so HEAD falls through to the closed catch-all.
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            resp = await self.client.head("/v1/terminal", headers=self.upgrade_headers())
        spawn.assert_not_called()
        self.assertEqual(resp.status, 403)
        self.assertEqual(self.state.active_terminals, 0)


class OriginTest(TerminalCase):
    async def test_every_origin_is_refused_before_a_process_starts(self):
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            for origin in ("https://evil.example", "null", "", "http://127.0.0.1:9999"):
                with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
                    resp = await self.client.get(
                        "/v1/terminal", headers=self.upgrade_headers(Origin=origin))
                self.assertEqual(resp.status, 403, origin)
                self.assertEqual((await resp.json())["error"]["code"], "origin_not_allowed")
                joined = "\n".join(logs.output)
                self.assertIn("action=terminal", joined)
                self.assertIn("status=403", joined)
                self.assertNotIn(self.token, joined)
            spawn.assert_not_called()
        resp = await self.client.get(
            "/v1/terminal", headers={**self.auth(), "origin": "https://evil.example"})
        self.assertEqual(resp.status, 403)
        self.assertEqual(self.state.active_terminals, 0)

    async def test_a_non_browser_client_without_origin_is_allowed(self):
        ws = await self.connect()
        await ws.send_bytes(b"ok")
        await self.read_until(ws, b"ok")
        await ws.close()


class DisabledTest(TerminalCase):
    command = None

    async def test_capabilities_and_the_route_hide_a_disabled_terminal(self):
        caps = await (await self.client.get("/v1/capabilities", headers=self.auth())).json()
        self.assertIs(caps["features"]["terminal"], False)
        self.assertNotIn("terminal", caps["endpoints"])
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            resp = await self.client.get("/v1/terminal", headers=self.upgrade_headers())
            self.assertEqual(resp.status, 403)
            self.assertEqual((await resp.json())["error"]["code"], "endpoint_out_of_scope")
            spawn.assert_not_called()

    async def test_capabilities_do_not_disclose_the_terminal_to_a_bad_token(self):
        resp = await self.client.get(
            "/v1/capabilities", headers={"Authorization": "Bearer hd_" + "z" * 40})
        self.assertEqual(resp.status, 401)
        body = await resp.json()
        self.assertNotIn("features", body)
        self.assertNotIn("terminal", str(body))


class StartupTest(unittest.TestCase):
    def _env(self, terminal_value="0"):
        return {
            "HERMES_API_KEY": "key",
            "BRIDGE_STATE_DB": ":memory:",
            "BRIDGE_TERMINAL": terminal_value,
        }

    def test_an_unparseable_switch_fails_closed(self):
        for raw in ("true", "yes", "on", "2", "-1", "01"):
            with self.assertRaises(SystemExit, msg=raw):
                bridge_server._terminal_command_from_env(self._env(raw))

    def test_one_without_tmux_or_setsid_fails_closed(self):
        with mock.patch.object(terminal.shutil, "which", return_value=None):
            with self.assertRaises(SystemExit):
                bridge_server._terminal_command_from_env(self._env("1"))

    def test_off_and_empty_build_no_command(self):
        for raw in ("0", "", " 0 "):
            self.assertIsNone(bridge_server._terminal_command_from_env(self._env(raw)))


class QuotaTest(TerminalCase):
    async def test_the_quota_is_enforced_and_released(self):
        sessions = [await self.connect() for _ in range(terminal.MAX_TERMINALS)]
        self.assertEqual(self.state.active_terminals, terminal.MAX_TERMINALS)
        with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
            with mock.patch.object(terminal.Pty, "spawn") as spawn:
                with self.assertRaises(WSServerHandshakeError) as caught:
                    await self.client.ws_connect("/v1/terminal", headers=self.auth())
                self.assertEqual(caught.exception.status, 429)
                spawn.assert_not_called()
        self.assertIn("status=429", "\n".join(logs.output))
        self.assertNotIn(self.token, "\n".join(logs.output))
        await sessions[0].close()
        await self.wait_slots(terminal.MAX_TERMINALS - 1)
        replacement = await self.connect()
        self.assertEqual(self.state.active_terminals, terminal.MAX_TERMINALS)
        for ws in sessions[1:] + [replacement]:
            await ws.close()
        await self.wait_slots(0)

    async def test_rejected_handshakes_do_not_consume_or_release_slots(self):
        self.state.active_terminals = terminal.MAX_TERMINALS
        resp = await self.client.get("/v1/terminal", headers=self.upgrade_headers())
        self.assertEqual(resp.status, 429)
        with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
            resp = await self.client.get(
                "/v1/terminal", headers=self.upgrade_headers(Origin="https://evil.example"))
            self.assertEqual(resp.status, 403)
        self.assertIn("action=terminal", "\n".join(logs.output))
        self.assertNotIn(self.token, "\n".join(logs.output))
        resp = await self.client.get(
            "/v1/terminal", headers=self.upgrade_headers("hd_" + "q" * 40))
        self.assertEqual(resp.status, 401)
        self.assertEqual(self.state.active_terminals, terminal.MAX_TERMINALS)
        self.state.active_terminals = 0


class LeakTest(TerminalCase):
    command = ("sh", "-c", "stty raw -echo; echo pid=$$; exec cat")

    async def test_normal_protocol_error_and_spawn_failure_sessions_leak_nothing(self):
        before = len(fd_targets())
        ws = await self.connect()
        await ws.send_bytes(b"hi")
        await self.read_until(ws, b"hi")
        await ws.close()
        await self.wait_slots(0)

        ws = await self.connect()
        await ws.send_str("not json")
        self.assertEqual((await self.next_close(ws)).type, WSMsgType.CLOSE)
        self.assertEqual(ws.close_code, 1008)
        await self.wait_slots(0)

        ws = await self.connect()
        await ws.send_bytes(b"x" * (terminal.MAX_INPUT_BYTES + 1))
        self.assertEqual((await self.next_close(ws)).type, WSMsgType.CLOSE)
        self.assertEqual(ws.close_code, 1009)
        await self.wait_slots(0)

        self.state.terminal_command = ("/nonexistent/terminal-binary",)
        resp = await self.client.get("/v1/terminal", headers=self.upgrade_headers())
        self.assertEqual(resp.status, 503)
        self.state.terminal_command = self.command

        await asyncio.sleep(0.2)
        self.assertEqual(pty_fds(), [])
        self.assertEqual(pidfd_count(), 0)
        self.assertLessEqual(len(fd_targets()) - before, 1)
        self.assertEqual(self.state.active_terminals, 0)

    async def test_a_client_vanishing_after_the_handshake_frees_everything(self):
        raw = await self.raw()
        self.assertEqual(raw.status, 101)
        seen = await raw.data_until(b"pid=")
        pid = int(seen.split(b"pid=")[1].split()[0])
        raw.abort()
        await self.wait_slots(0)
        await self.wait_pid_gone(pid)
        self.assertEqual(pty_fds(), [])

    async def test_a_client_vanishing_during_the_handshake_leaks_nothing(self):
        for _ in range(5):
            reader, writer = await asyncio.open_connection(
                self.client.server.host, self.client.server.port)
            key = base64.b64encode(os.urandom(16)).decode()
            writer.write((
                "GET /v1/terminal HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                f"Authorization: Bearer {self.token}\r\n\r\n").encode())
            await writer.drain()
            writer.transport.abort()
        await asyncio.sleep(0.5)
        self.assertEqual(self.state.active_terminals, 0)
        self.assertEqual(pty_fds(), [])

    async def test_an_exception_in_the_output_pump_closes_with_1011(self):
        with mock.patch.object(terminal.Pty, "read", side_effect=RuntimeError("boom")):
            ws = await self.connect()
            msg = await self.next_close(ws)
        self.assertEqual(msg.type, WSMsgType.CLOSE)
        self.assertEqual(ws.close_code, 1011)
        await self.wait_slots(0)
        await asyncio.sleep(0.1)
        self.assertEqual(pty_fds(), [])

    async def test_a_slow_client_does_not_buffer_the_output(self):
        script = (
            "import sys\n"
            "for _ in range(128):\n"
            "    sys.stdout.write('x' * 65535 + '\\n')\n"
            "    sys.stdout.flush()\n"
        )
        state, client, token = await self.extra_bridge((PY, "-c", script))
        tracemalloc.start()
        try:
            ws = await client.ws_connect(
                "/v1/terminal", headers={"Authorization": "Bearer " + token})
            await asyncio.sleep(1.5)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertLess(peak, 4 * 1024 * 1024)
        self.assertEqual(state.active_terminals, 1)
        started = asyncio.get_running_loop().time()
        resp = await client.get("/v1/capabilities", headers={"Authorization": "Bearer " + token})
        self.assertEqual(resp.status, 200)
        self.assertLess(asyncio.get_running_loop().time() - started, 1.0)
        await asyncio.wait_for(ws.close(), 10)
        deadline = asyncio.get_running_loop().time() + 5
        while state.active_terminals and asyncio.get_running_loop().time() < deadline:
            await asyncio.sleep(0.02)
        self.assertEqual(state.active_terminals, 0)

    async def test_an_input_flood_to_a_child_that_never_reads_is_bounded(self):
        command = ("sh", "-c", "stty raw -echo; echo pid=$$; exec sleep 300")
        state, client, token = await self.extra_bridge(command)
        raw = await RawWebSocket.connect(client, token)
        self._raws.append(raw)
        seen = await raw.data_until(b"pid=")
        pid = int(seen.split(b"pid=")[1].split()[0])
        self._pids.append(pid)
        payload = b"x" * (terminal.MAX_INPUT_BYTES - 1)
        sent = 0
        blocked = False
        tracemalloc.start()
        try:
            while sent < 32 * 1024 * 1024:
                try:
                    await asyncio.wait_for(raw.send(0x2, payload), 1.0)
                except TimeoutError:
                    blocked = True
                    break
                sent += len(payload)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertTrue(blocked, "the peer never applied backpressure")
        self.assertLess(sent, 32 * 1024 * 1024)
        self.assertLess(peak, 8 * 1024 * 1024)
        os.kill(pid, signal.SIGKILL)
        raw.close()
        deadline = asyncio.get_running_loop().time() + 5
        while state.active_terminals and asyncio.get_running_loop().time() < deadline:
            await asyncio.sleep(0.02)
        self.assertEqual(state.active_terminals, 0)


class EnvironmentTest(TerminalCase):
    async def test_the_child_sees_none_of_the_bridge_settings(self):
        markers = {
            "HERMES_API_KEY": "marker-hermes-key-71a",
            "BRIDGE_TERMINAL": "marker-bridge-terminal-71a",
            "BRIDGE_UPSTREAM": "http://127.0.0.1:9",
            "BRIDGE_HERMES_ENV": "/home/bridge/hermes-71a.env",
            "BRIDGE_STATE_DB": "/home/bridge/state-71a.db",
            "MASTER_KEY": "marker-master-key-71a",
        }
        state, client, token = await self.extra_bridge(("sh", "-c", "env; echo __T13_DONE__"))
        with mock.patch.dict(os.environ, markers):
            ws = await client.ws_connect(
                "/v1/terminal", headers={"Authorization": "Bearer " + token})
            seen = await asyncio.wait_for(self.read_until(ws, b"__T13_DONE__"), 5)
        await ws.close()
        for name, value in markers.items():
            self.assertNotIn(name.encode(), seen)
            self.assertNotIn(value.encode(), seen)
        self.assertIn(b"TERM=xterm-256color", seen)

    async def test_a_same_uid_shell_can_read_the_bridge_environment_via_proc(self):
        marker = "marker-proc-env-9f1c"
        script = '''
import asyncio

from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import server as bridge_server

COMMAND = ("sh", "-c", "echo __T13_PARENT__; cat /proc/$PPID/environ; echo; echo __T13_CHILD__; env; echo __T13_DONE__")


async def main():
    state = bridge_server.BridgeState(
        upstream="http://127.0.0.1:9", master_key="m", terminal_command=COMMAND)
    client = TestClient(TestServer(bridge_server.create_app(state)))
    await client.start_server()
    issued = state.codes.issue()
    resp = await client.post("/v1/devices/pair", json={"code": issued[0]})
    token = (await resp.json())["device_key"]
    state.store.set_terminal_access(state.tokens.lookup(token).device_id, True)
    ws = await client.ws_connect(
        "/v1/terminal", headers={"Authorization": "Bearer " + token})
    seen = b""
    while b"__T13_DONE__" not in seen:
        msg = await asyncio.wait_for(ws.receive(), 5)
        if msg.type != WSMsgType.BINARY:
            break
        seen += msg.data
    print(seen.decode("utf-8", "replace"))
    await ws.close()
    await client.close()
    state.store.close()


asyncio.run(main())
'''
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "probe.py")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(script)
            env = {
                **os.environ,
                "HERMES_API_KEY": marker,
                "BRIDGE_TERMINAL": "1",
            }
            completed = await asyncio.to_thread(
                subprocess.run, [sys.executable, path], cwd=os.path.dirname(os.path.dirname(__file__)),
                env=env, capture_output=True, timeout=60)
        self.assertEqual(completed.returncode, 0, completed.stderr.decode("utf-8", "replace"))
        output = completed.stdout.decode("utf-8", "replace")
        parent, _, child = output.partition("__T13_CHILD__")
        self.assertIn(marker, parent)
        self.assertNotIn(marker, child)

    async def test_audit_never_records_input_output_or_the_token(self):
        with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
            ws = await self.connect()
            secret = b"hd_" + b"Z" * 40
            await ws.send_bytes(secret)
            await self.read_until(ws, secret)
            await ws.close()
            for _ in range(100):
                if any("terminal_close" in line for line in logs.output):
                    break
                await asyncio.sleep(0.02)
        joined = "\n".join(logs.output)
        self.assertIn("action=terminal_open", joined)
        self.assertIn("action=terminal_close", joined)
        self.assertIn("reason=client", joined)
        self.assertNotIn(secret.decode(), joined)
        self.assertNotIn(self.token, joined)

    async def test_a_revoked_device_is_closed_with_4401_and_audited(self):
        with mock.patch.object(bridge_server, "_REVOKE_CHECK_INTERVAL", 0.05):
            with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
                ws = await self.connect()
                resp = await self.client.post(
                    "/v1/devices/self/revoke", headers=self.auth())
                self.assertEqual(resp.status, 200)
                msg = await self.next_close(ws)
                self.assertEqual(msg.type, WSMsgType.CLOSE)
                self.assertEqual(ws.close_code, 4401)
                for _ in range(100):
                    if any("terminal_close" in line for line in logs.output):
                        break
                    await asyncio.sleep(0.02)
        self.assertIn("reason=revoked", "\n".join(logs.output))
        await self.wait_slots(0)


class ProcessTest(TerminalCase):
    async def test_a_child_that_ignores_sighup_is_killed_after_the_grace_period(self):
        command = ("sh", "-c", "stty raw -echo; echo pid=$$; trap '' HUP; exec sleep 300")
        state, client, token = await self.extra_bridge(command)
        ws = await client.ws_connect(
            "/v1/terminal", headers={"Authorization": "Bearer " + token})
        seen = await self.read_until(ws, b"pid=")
        pid = int(seen.split(b"pid=")[1].split()[0])
        started = asyncio.get_running_loop().time()
        await ws.close()
        while state.active_terminals and asyncio.get_running_loop().time() - started < 6:
            await asyncio.sleep(0.02)
        elapsed = asyncio.get_running_loop().time() - started
        self.assertGreaterEqual(elapsed, 1.5)
        self.assertLess(elapsed, 5)
        self.assertEqual(state.active_terminals, 0)
        await self.wait_pid_gone(pid)

    @unittest.skipUnless(shutil.which("setsid"), "setsid is required for BRIDGE_TERMINAL")
    async def test_the_real_setsid_group_reaches_grandchildren(self):
        command = (
            shutil.which("setsid"), "--ctty", "sh", "-c",
            "stty raw -echo; trap '' HUP TERM; echo pid=$$; sleep 300 & echo gpid=$!; wait",
        )
        state, client, token = await self.extra_bridge(command)
        ws = await client.ws_connect(
            "/v1/terminal", headers={"Authorization": "Bearer " + token})
        seen = b""
        while b"gpid=" not in seen:
            msg = await asyncio.wait_for(ws.receive(), 5)
            if msg.type != WSMsgType.BINARY:
                self.fail(f"closed early: {msg!r}")
            seen += msg.data
        pid = int(seen.split(b"pid=")[1].split()[0])
        gpid = int(seen.split(b"gpid=")[1].split()[0])
        self._pids.extend([pid, gpid])
        started = asyncio.get_running_loop().time()
        await ws.close()
        while state.active_terminals and asyncio.get_running_loop().time() - started < 6:
            await asyncio.sleep(0.02)
        self.assertEqual(state.active_terminals, 0)
        self.assertLess(asyncio.get_running_loop().time() - started, 5)
        await self.wait_pid_gone(pid)
        await self.wait_pid_gone(gpid)

    async def test_a_non_setsid_child_does_not_share_a_killable_group_with_the_bridge(self):
        command = (
            "sh", "-c",
            "stty raw -echo; echo pid=$$; read _ _ _ _ pgrp _ < /proc/$$/stat; echo pgid=$pgrp; exec sleep 300",
        )
        state, client, token = await self.extra_bridge(command)
        ws = await client.ws_connect(
            "/v1/terminal", headers={"Authorization": "Bearer " + token})
        seen = await self.read_until(ws, b"pgid=")
        pid = int(seen.split(b"pid=")[1].split()[0])
        pgid = int(seen.split(b"pgid=")[1].split()[0])
        self.assertEqual(pgid, os.getpgrp())
        self.assertNotEqual(pgid, pid)
        await ws.close()
        loop = asyncio.get_running_loop()
        deadline = loop.time() + 5
        while state.active_terminals and loop.time() < deadline:
            await asyncio.sleep(0.02)
        self.assertEqual(state.active_terminals, 0)
        resp = await client.get("/v1/capabilities", headers={"Authorization": "Bearer " + token})
        self.assertEqual(resp.status, 200)

    async def test_closing_one_terminal_leaves_the_other_running(self):
        command = ("sh", "-c", "stty raw -echo; echo pid=$$; exec cat")
        state, client, token = await self.extra_bridge(command)
        headers = {"Authorization": "Bearer " + token}
        sessions = []
        pids = []
        for _ in range(2):
            ws = await client.ws_connect("/v1/terminal", headers=headers)
            seen = await self.read_until(ws, b"pid=")
            pids.append(int(seen.split(b"pid=")[1].split()[0]))
            sessions.append(ws)
        await sessions[0].close()
        await self.wait_slots(1, state=state)
        await self.wait_pid_gone(pids[0])
        await sessions[1].send_bytes(b"still-here")
        await self.read_until(sessions[1], b"still-here")
        self.assertTrue(os.path.exists(f"/proc/{pids[1]}"))
        await sessions[1].close()
        await self.wait_slots(0, state=state)

    async def test_the_grace_period_does_not_block_other_requests(self):
        command = ("sh", "-c", "stty raw -echo; trap '' HUP; exec sleep 300")
        state, client, token = await self.extra_bridge(command)
        ws = await client.ws_connect(
            "/v1/terminal", headers={"Authorization": "Bearer " + token})
        closing = asyncio.create_task(ws.close())
        loop = asyncio.get_running_loop()
        latencies = []
        for _ in range(8):
            started = loop.time()
            resp = await client.get(
                "/v1/capabilities", headers={"Authorization": "Bearer " + token})
            latencies.append(loop.time() - started)
            self.assertEqual(resp.status, 200)
            await asyncio.sleep(0.2)
        await asyncio.wait_for(closing, 10)
        self.assertLess(max(latencies), 0.5)
        deadline = loop.time() + 5
        while state.active_terminals and loop.time() < deadline:
            await asyncio.sleep(0.02)
        self.assertEqual(state.active_terminals, 0)


class ProtocolTest(TerminalCase):
    async def test_text_frames_are_control_frames_and_never_reach_the_child(self):
        with tempfile.TemporaryDirectory() as tmp:
            capture = os.path.join(tmp, "stdin.bin")
            state, client, token = await self.extra_bridge(("sh", "-c", f"cat > {capture}"))
            ws = await client.ws_connect(
                "/v1/terminal", headers={"Authorization": "Bearer " + token})
            await ws.send_str("hello\n")
            msg = await self.next_close(ws)
            self.assertEqual(msg.type, WSMsgType.CLOSE)
            self.assertEqual(ws.close_code, 1008)
            deadline = asyncio.get_running_loop().time() + 5
            while state.active_terminals and asyncio.get_running_loop().time() < deadline:
                await asyncio.sleep(0.02)
            self.assertEqual(state.active_terminals, 0)
            self.assertEqual(os.path.getsize(capture) if os.path.exists(capture) else 0, 0)

    async def test_a_binary_frame_that_looks_like_a_resize_is_typed(self):
        state, client, token = await self.extra_bridge(SIZE_REPORTER)
        ws = await client.ws_connect(
            "/v1/terminal", headers={"Authorization": "Bearer " + token})
        try:
            await ws.send_bytes(b'{"type": "resize", "cols": 120, "rows": 40}\n')
            await self.read_until(ws, b"size 80 24")
            await ws.send_str('{"type": "resize", "cols": 120, "rows": 40}')
            await ws.send_bytes(b"x\n")
            await self.read_until(ws, b"size 120 40")
        finally:
            await asyncio.wait_for(ws.close(), 5)

    async def test_a_flood_of_resize_frames_stays_responsive(self):
        state, client, token = await self.extra_bridge(SIZE_REPORTER)
        ws = await client.ws_connect(
            "/v1/terminal", headers={"Authorization": "Bearer " + token})
        try:
            for _ in range(2000):
                await ws.send_str('{"type": "resize", "cols": 400, "rows": 200}')
            loop = asyncio.get_running_loop()
            started = loop.time()
            resp = await client.get(
                "/v1/capabilities", headers={"Authorization": "Bearer " + token})
            self.assertEqual(resp.status, 200)
            self.assertLess(loop.time() - started, 0.5)
            await ws.send_bytes(b"x\n")
            await self.read_until(ws, b"size 400 200")
        finally:
            await asyncio.wait_for(ws.close(), 5)
        await self.wait_slots(0, state=state)

    async def test_odd_and_deep_json_is_a_protocol_close(self):
        cases = (
            "not json",
            "[" * 20000,
            '{"type": "resize", "cols": ' + "9" * 5000 + ', "rows": 1}',
            '{"type": "resize", "cols": NaN, "rows": 1}',
            '{"type": "resize", "cols": 1e999, "rows": 1}',
            '{"type": "resize", "cols": "80", "rows": 40}',
            '{"type": "resize", "cols": 80, "rows": 40, "extra": 1}',
            '{"type": "resize", "cols": 80}',
            '{"a": ' * 6000 + "1" + "}" * 6000,
            "null",
            '"resize"',
            "[]",
            "",
        )
        for payload in cases:
            ws = await self.connect()
            await ws.send_str(payload)
            msg = await self.next_close(ws)
            self.assertEqual(msg.type, WSMsgType.CLOSE, payload[:24])
            self.assertEqual(ws.close_code, 1008, payload[:24])
            await self.wait_slots(0)

    async def test_the_documented_message_bound_is_64_kib_inclusive(self):
        state, client, token = await self.extra_bridge(
            ("sh", "-c", "stty raw -echo; echo READY; head -c 65536 > /dev/null; echo GOT"))
        ws = await client.ws_connect(
            "/v1/terminal", headers={"Authorization": "Bearer " + token})
        # The PTY starts in canonical mode, which can discard a large input
        # before the child executes stty. Wait for its raw-mode handshake.
        await self.read_until(ws, b"READY")
        await ws.send_bytes(b"x" * terminal.MAX_INPUT_BYTES)
        await self.read_until(ws, b"GOT")
        await ws.close()

        ws = await self.connect()
        await ws.send_bytes(b"x" * (terminal.MAX_INPUT_BYTES + 1))
        msg = await self.next_close(ws)
        self.assertEqual(msg.type, WSMsgType.CLOSE)
        self.assertEqual(ws.close_code, 1009)
        await self.wait_slots(0)

    async def test_ping_is_answered_and_an_unsolicited_pong_is_ignored(self):
        raw = await self.raw()
        await raw.send(0x9, b"probe")
        opcode, data = await raw.read_frame()
        self.assertEqual(opcode, 0xA)
        self.assertEqual(data, b"probe")
        await raw.send(0xA, b"unsolicited")
        await raw.send(0x2, b"alive")
        self.assertIn(b"alive", await raw.data_until(b"alive"))
        raw.close()

    async def test_fragmented_messages_are_reassembled_and_still_bounded(self):
        raw = await self.raw()
        await raw.send(0x2, b"he", fin=False)
        await raw.send(0x0, b"llo", fin=True)
        self.assertIn(b"hello", await raw.data_until(b"hello"))
        raw.close()

        state, client, token = await self.extra_bridge(SIZE_REPORTER)
        raw = await RawWebSocket.connect(client, token)
        self._raws.append(raw)
        await raw.send(0x1, b'{"type": "res', fin=False)
        await raw.send(0x0, b'ize", "cols": 100, "rows": 30}', fin=True)
        await raw.send(0x2, b"x\n")
        self.assertIn(b"size 100 30", await raw.data_until(b"size 100 30"))
        raw.close()

        raw = await self.raw()
        await raw.send(0x1, b"hel", fin=False)
        await raw.send(0x0, b"lo", fin=True)
        self.assertEqual(await raw.wait_close(), 1008)
        await self.wait_slots(0)

        raw = await self.raw()
        await raw.send(0x1, b"a" * 40000, fin=False)
        await raw.send(0x0, b"b" * 40000, fin=True)
        self.assertEqual(await raw.wait_close(), 1009)
        await self.wait_slots(0)

    async def test_invalid_utf8_text_is_closed_with_1007(self):
        raw = await self.raw()
        await raw.send(0x1, b"\xff\xfe\xfd")
        self.assertEqual(await raw.wait_close(), 1007)
        await self.wait_slots(0)
        self.assertEqual(pty_fds(), [])

    async def test_a_client_close_is_answered_with_1000(self):
        ws = await self.connect()
        await ws.send_bytes(b"x")
        await self.read_until(ws, b"x")
        await ws.close()
        self.assertEqual(ws.close_code, 1000)
        await self.wait_slots(0)


class SpawnFailureTest(TerminalCase):
    async def test_a_failure_to_build_the_child_environment_is_a_closed_503(self):
        # pwd.getpwuid raises KeyError (no passwd entry), not an OSError.
        with mock.patch.object(
                terminal.pwd, "getpwuid", side_effect=KeyError("no passwd entry")):
            resp = await self.client.get("/v1/terminal", headers=self.upgrade_headers())
        self.assertEqual(resp.status, 503)
        self.assertEqual((await resp.json())["error"]["code"], "terminal_unavailable")
        self.assertEqual(self.state.active_terminals, 0)


if __name__ == "__main__":
    unittest.main()
