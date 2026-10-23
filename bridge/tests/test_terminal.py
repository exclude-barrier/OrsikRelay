"""Terminal: off by default, device token, no browser origin, bounded, and the
child never sees the bridge's environment."""

import asyncio
import os
import sys
import unittest
from unittest import mock

from aiohttp import WSMsgType, web
from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import core, terminal
from orsik_mobile_bridge import server as bridge_server

PY = sys.executable
# Raw, no echo: what the client sends comes back exactly once.
ECHO = ("sh", "-c", "stty raw -echo; exec cat")


class ParseTest(unittest.TestCase):
    def test_enabled_only_with_1_and_typos_refused(self):
        self.assertFalse(terminal.parse_enabled(None))
        self.assertFalse(terminal.parse_enabled(""))
        self.assertFalse(terminal.parse_enabled("0"))
        self.assertTrue(terminal.parse_enabled(" 1 "))
        for raw in ("true", "yes", "on", "2"):
            with self.assertRaises(SystemExit, msg=raw):
                terminal.parse_enabled(raw)

    def test_resize_needs_exactly_type_cols_rows_within_bounds(self):
        self.assertEqual(terminal.parse_resize({"type": "resize", "cols": 120, "rows": 40}), (120, 40))
        for bad in (
            {"type": "resize", "cols": 0, "rows": 40},
            {"type": "resize", "cols": 501, "rows": 40},
            {"type": "resize", "cols": 80, "rows": 301},
            {"type": "resize", "cols": True, "rows": 40},
            {"type": "resize", "cols": 80.0, "rows": 40},
            {"type": "resize", "cols": 80, "rows": 40, "x": 1},
            {"type": "input", "cols": 80, "rows": 40},
            [80, 40],
            "resize",
        ):
            self.assertIsNone(terminal.parse_resize(bad), bad)

    def test_child_environment_carries_no_bridge_setting(self):
        with mock.patch.dict(os.environ, {"HERMES_API_KEY": "secret-key", "BRIDGE_TERMINAL": "1"}):
            env = terminal.child_env()
        self.assertNotIn("HERMES_API_KEY", env)
        self.assertNotIn("BRIDGE_TERMINAL", env)
        self.assertNotIn("secret-key", "".join(env.values()))
        self.assertEqual(env["TERM"], "xterm-256color")
        self.assertEqual(
            set(env), {"HOME", "USER", "LOGNAME", "SHELL", "PATH", "TERM", "LANG"})

    def test_default_command_refuses_to_start_without_tmux(self):
        with mock.patch.object(terminal.shutil, "which", return_value=None):
            with self.assertRaises(SystemExit):
                terminal.default_command()
        with mock.patch.object(terminal.shutil, "which", side_effect=lambda name: "/usr/bin/" + name):
            self.assertEqual(
                terminal.default_command(),
                ("/usr/bin/setsid", "--ctty", "/usr/bin/tmux", "new-session", "-A", "-s", "orsik"))

    def test_capabilities_advertise_the_terminal_only_when_enabled(self):
        off = core.build_capabilities(terminal=False)
        self.assertIs(off["features"]["terminal"], False)
        self.assertNotIn("terminal", off["endpoints"])
        on = core.build_capabilities(terminal=True)
        self.assertIs(on["features"]["terminal"], True)
        self.assertEqual(on["endpoints"]["terminal"], {"method": "GET", "path": "/v1/terminal"})

    def test_env_switch_builds_the_command_or_leaves_it_off(self):
        base = {"HERMES_API_KEY": "k", "BRIDGE_STATE_DB": ":memory:"}
        off = bridge_server.build_state_from_env(base)
        off.store.close()
        self.assertIsNone(off.terminal_command)
        with mock.patch.object(terminal, "default_command", return_value=("x",)):
            state = bridge_server.build_state_from_env({**base, "BRIDGE_TERMINAL": "1"})
        state.store.close()
        self.assertEqual(state.terminal_command, ("x",))


class TerminalRouteTest(unittest.IsolatedAsyncioTestCase):
    async def _start(self, command):
        self.state = bridge_server.BridgeState(
            upstream="http://127.0.0.1:9", master_key="master_key_test", terminal_command=command)
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        issued = self.state.codes.issue()
        resp = await self.client.post("/v1/devices/pair", json={"code": issued[0]})
        self.token = (await resp.json())["device_key"]

    async def asyncTearDown(self):
        await self.client.close()
        self.state.store.close()

    def _auth(self, token=None):
        return {"Authorization": "Bearer " + (token or self.token)}

    async def _read_until(self, ws, needle: bytes, timeout=5.0) -> bytes:
        seen = b""
        while needle not in seen:
            msg = await asyncio.wait_for(ws.receive(), timeout)
            self.assertEqual(msg.type, WSMsgType.BINARY, msg)
            seen += msg.data
        return seen

    async def test_off_by_default_even_with_a_valid_token(self):
        await self._start(None)
        resp = await self.client.get("/v1/terminal", headers=self._auth())
        self.assertEqual(resp.status, 403)
        self.assertEqual((await resp.json())["error"]["code"], "endpoint_out_of_scope")
        caps = await (await self.client.get("/v1/capabilities", headers=self._auth())).json()
        self.assertIs(caps["features"]["terminal"], False)

    async def test_token_origin_query_and_upgrade_are_checked_before_a_process_starts(self):
        await self._start(ECHO)
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            self.assertEqual((await self.client.get("/v1/terminal")).status, 401)
            self.assertEqual(
                (await self.client.get("/v1/terminal", headers=self._auth("hd_" + "x" * 40))).status, 401)
            resp = await self.client.get(
                "/v1/terminal", headers={**self._auth(), "Origin": "https://evil.example"})
            self.assertEqual(resp.status, 403)
            self.assertEqual((await resp.json())["error"]["code"], "origin_not_allowed")
            self.assertEqual((await self.client.get("/v1/terminal?x=1", headers=self._auth())).status, 403)
            resp = await self.client.get("/v1/terminal", headers=self._auth())
            self.assertEqual(resp.status, 400)
            self.assertEqual((await resp.json())["error"]["code"], "websocket_required")
            spawn.assert_not_called()
        caps = await (await self.client.get("/v1/capabilities", headers=self._auth())).json()
        self.assertIs(caps["features"]["terminal"], True)

    async def test_bytes_round_trip_and_the_client_closing_ends_the_child(self):
        await self._start(("sh", "-c", "stty raw -echo; echo pid=$$; exec cat"))
        ws = await self.client.ws_connect("/v1/terminal", headers=self._auth())
        seen = await self._read_until(ws, b"\n")
        pid = int(seen.split(b"pid=")[1].split()[0])
        await ws.send_bytes(b"hello \x00\xff")
        await self._read_until(ws, b"hello \x00\xff")
        await ws.close()
        for _ in range(100):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
            await asyncio.sleep(0.05)
        else:
            self.fail("the terminal child outlived its WebSocket")
        for _ in range(100):
            if self.state.active_terminals == 0:
                break
            await asyncio.sleep(0.05)
        self.assertEqual(self.state.active_terminals, 0)

    async def test_resize_reaches_the_pty(self):
        script = "import os,sys\nfor line in sys.stdin:\n    print('size', *os.get_terminal_size(0), flush=True)\n"
        await self._start((PY, "-c", script))
        ws = await self.client.ws_connect("/v1/terminal", headers=self._auth())
        await ws.send_str('{"type": "resize", "cols": 120, "rows": 40}')
        await ws.send_bytes(b"x\n")
        await self._read_until(ws, b"size 120 40")
        await ws.close()

    async def test_a_malformed_control_message_closes_with_1008(self):
        await self._start(ECHO)
        for bad in ("not json", '{"type": "resize", "cols": 9999, "rows": 1}', "[" * 20000):
            ws = await self.client.ws_connect("/v1/terminal", headers=self._auth())
            await ws.send_str(bad)
            msg = await asyncio.wait_for(ws.receive(), 5)
            while msg.type == WSMsgType.BINARY:
                msg = await asyncio.wait_for(ws.receive(), 5)
            self.assertEqual(msg.type, WSMsgType.CLOSE, bad[:20])
            self.assertEqual(ws.close_code, 1008, bad[:20])

    async def test_an_oversized_message_is_refused(self):
        await self._start(ECHO)
        ws = await self.client.ws_connect("/v1/terminal", headers=self._auth())
        await ws.send_bytes(b"x" * (terminal.MAX_INPUT_BYTES + 1))
        msg = await asyncio.wait_for(ws.receive(), 5)
        while msg.type == WSMsgType.BINARY:
            msg = await asyncio.wait_for(ws.receive(), 5)
        self.assertEqual(msg.type, WSMsgType.CLOSE)
        self.assertEqual(ws.close_code, 1009)

    async def test_the_shell_exiting_closes_with_1000(self):
        await self._start(("sh", "-c", "echo bye"))
        ws = await self.client.ws_connect("/v1/terminal", headers=self._auth())
        seen = b""
        while True:
            msg = await asyncio.wait_for(ws.receive(), 5)
            if msg.type != WSMsgType.BINARY:
                break
            seen += msg.data
        self.assertIn(b"bye", seen)
        self.assertEqual(ws.close_code, 1000)

    async def test_the_child_sees_none_of_the_bridge_environment(self):
        await self._start(("sh", "-c", "env; echo END"))
        with mock.patch.dict(os.environ, {"HERMES_API_KEY": "secret-key-xyz"}):
            ws = await self.client.ws_connect("/v1/terminal", headers=self._auth())
            seen = await self._read_until(ws, b"END")
        self.assertNotIn(b"secret-key-xyz", seen)
        self.assertNotIn(b"HERMES_API_KEY", seen)
        self.assertIn(b"TERM=xterm-256color", seen)
        await ws.close()

    async def test_the_number_of_terminals_is_bounded(self):
        await self._start(ECHO)
        self.state.active_terminals = terminal.MAX_TERMINALS
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            with self.assertRaises(Exception):
                await self.client.ws_connect("/v1/terminal", headers=self._auth())
            spawn.assert_not_called()
        self.state.active_terminals = 0

    async def test_a_spawn_failure_is_a_closed_503(self):
        await self._start(("/nonexistent/terminal-binary",))
        resp = await self.client.get(
            "/v1/terminal",
            headers={**self._auth(), "Connection": "Upgrade", "Upgrade": "websocket",
                     "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ=="})
        self.assertEqual(resp.status, 503)
        self.assertEqual((await resp.json())["error"]["code"], "terminal_unavailable")
        self.assertEqual(self.state.active_terminals, 0)

    async def test_a_revoked_device_loses_an_open_terminal(self):
        await self._start(ECHO)
        with mock.patch.object(bridge_server, "_REVOKE_CHECK_INTERVAL", 0.05):
            ws = await self.client.ws_connect("/v1/terminal", headers=self._auth())
            resp = await self.client.post("/v1/devices/self/revoke", headers=self._auth())
            self.assertEqual(resp.status, 200)
            msg = await asyncio.wait_for(ws.receive(), 5)
            while msg.type == WSMsgType.BINARY:
                msg = await asyncio.wait_for(ws.receive(), 5)
            self.assertEqual(ws.close_code, 4401)

    async def test_open_and_close_are_audited_without_content(self):
        await self._start(ECHO)
        with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
            ws = await self.client.ws_connect("/v1/terminal", headers=self._auth())
            await ws.send_bytes(b"top-secret-input")
            await self._read_until(ws, b"top-secret-input")
            await ws.close()
            for _ in range(100):
                if any("terminal_close" in line for line in logs.output):
                    break
                await asyncio.sleep(0.05)
        joined = "\n".join(logs.output)
        self.assertIn("action=terminal_open", joined)
        self.assertIn("action=terminal_close", joined)
        self.assertIn("reason=client", joined)
        self.assertNotIn("top-secret-input", joined)


if __name__ == "__main__":
    unittest.main()
