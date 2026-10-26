"""Terminal authority is local, per-device, durable and separate from pairing."""

import asyncio
import contextlib
import io
import os
import sqlite3
import tempfile
import unittest
from unittest import mock

from aiohttp import WSMsgType
from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import core, server, store, terminal


class TerminalGrantStoreTest(unittest.TestCase):
    def test_grant_and_revoke_survive_restart_without_changing_pairing_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "state.db")
            first = store.BridgeStore(path)
            token, device = first.mint_device("phone")
            other_token, _ = first.mint_device("other")
            self.assertFalse(first.lookup_device(token).terminal_allowed)
            self.assertTrue(first.set_terminal_access(device.device_id, True))
            first.close()
            second = store.BridgeStore(path)
            self.assertTrue(second.lookup_device(token).terminal_allowed)
            self.assertFalse(second.lookup_device(other_token).terminal_allowed)
            self.assertEqual(second.lookup_device(token).permissions, core.DEVICE_PERMISSIONS)
            self.assertTrue(second.list_devices()[0]["terminal_allowed"])
            self.assertTrue(second.set_terminal_access(device.device_id, False))
            second.close()
            third = store.BridgeStore(path)
            self.assertFalse(third.lookup_device(token).terminal_allowed)
            third.close()

    def test_real_v1_migration_defaults_to_denied_and_preserves_devices_and_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "state.db")
            with mock.patch.object(store, "_MIGRATIONS", (store._migration_v0_to_v1,)), \
                    mock.patch.object(store, "SCHEMA_VERSION", 1):
                old = store.BridgeStore(path)
                token, device = old.mint_device("old phone")
                old.close()
            migrated = store.BridgeStore(path)
            self.assertEqual(migrated.lookup_device(token).device_id, device.device_id)
            self.assertFalse(migrated.lookup_device(token).terminal_allowed)
            self.assertEqual(migrated.lookup_device(token).permissions, core.DEVICE_PERMISSIONS)
            migrated.close()
            with sqlite3.connect(path + ".pre-v1.bak") as backup:
                self.assertEqual(backup.execute("PRAGMA user_version").fetchone()[0], 1)
                self.assertNotIn("terminal_allowed", [r[1] for r in backup.execute("PRAGMA table_info(devices)")])

    def test_unknown_revoked_and_non_boolean_grants_are_refused(self):
        db = store.BridgeStore(":memory:")
        try:
            token, device = db.mint_device("phone")
            self.assertFalse(db.set_terminal_access("missing", True))
            for value in (0, 1, "true", None):
                with self.assertRaises(ValueError):
                    db.set_terminal_access(device.device_id, value)
            db.revoke_device(token)
            self.assertFalse(db.set_terminal_access(device.device_id, True))
        finally:
            db.close()

    def test_store_failure_is_not_treated_as_a_grant(self):
        db = store.BridgeStore(":memory:")
        _, device = db.mint_device("phone")
        db.close()
        with self.assertRaises(store.StoreUnavailable):
            db.set_terminal_access(device.device_id, True)

    def test_separate_operator_connection_and_idempotent_updates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "state.db")
            bridge = store.BridgeStore(path)
            token, device = bridge.mint_device("phone")
            operator = store.BridgeStore(path)
            try:
                for allowed in (True, True, False, False):
                    self.assertTrue(operator.set_terminal_access(device.device_id, allowed))
                    self.assertEqual(bridge.lookup_device(token).terminal_allowed, allowed)
                bridge.revoke_device(token)
                self.assertFalse(operator.set_terminal_access(device.device_id, True))
                self.assertFalse(operator.list_devices()[0]["terminal_allowed"])
            finally:
                operator.close()
                bridge.close()

    def test_failed_v2_migration_rolls_back_column_and_version(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "state.db")
            with mock.patch.object(store, "_MIGRATIONS", (store._migration_v0_to_v1,)), \
                    mock.patch.object(store, "SCHEMA_VERSION", 1):
                old = store.BridgeStore(path)
                token, _ = old.mint_device("phone")
                old.close()

            def broken_step(connection):
                store._migration_v1_to_v2(connection)
                raise sqlite3.OperationalError("injected migration failure")

            with mock.patch.object(store, "_MIGRATIONS", (store._migration_v0_to_v1, broken_step)):
                with self.assertRaises(store.StoreUnavailable):
                    store.BridgeStore(path)
            with sqlite3.connect(path) as connection:
                self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 1)
                self.assertNotIn("terminal_allowed", [r[1] for r in connection.execute("PRAGMA table_info(devices)")])
                self.assertEqual(connection.execute("SELECT token_hash FROM devices").fetchone()[0], core.sha256_hex(token))


class TerminalGrantRouteTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.state = server.BridgeState(upstream="http://127.0.0.1:9", master_key="test-only",
                                        terminal_command=("sh", "-c", "echo ready; exec cat"))
        self.token, self.device = self.state.store.mint_device("phone")
        self.headers = {"Authorization": "Bearer " + self.token}
        self.client = TestClient(TestServer(server.create_app(self.state)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.state.store.close()

    async def test_pairing_does_not_grant_shell_or_advertise_it(self):
        code, _ = self.state.codes.issue()
        paired = await (await self.client.post("/v1/devices/pair", json={"code": code})).json()
        self.assertEqual(set(paired), {"object", "device_id", "device_key", "profile", "label", "permissions", "issued_at"})
        self.assertEqual(paired["permissions"], list(core.DEVICE_PERMISSIONS))
        self.assertFalse(self.state.store.lookup_device(paired["device_key"]).terminal_allowed)
        caps = await (await self.client.get("/v1/capabilities", headers=self.headers)).json()
        self.assertFalse(caps["features"]["terminal"])
        self.assertNotIn("terminal", caps["endpoints"])
        with mock.patch.object(terminal.Pty, "spawn") as spawn:
            response = await self.client.get("/v1/terminal", headers=self.headers)
            self.assertEqual(response.status, 403)
            self.assertEqual((await response.json())["error"]["code"], "terminal_not_allowed")
            spawn.assert_not_called()

    async def test_revocation_during_spawn_prevents_websocket_upgrade_and_closes_pty(self):
        self.state.store.set_terminal_access(self.device.device_id, True)
        pty = mock.Mock()
        pty.close = mock.AsyncMock()

        async def spawn(_command):
            self.state.store.set_terminal_access(self.device.device_id, False)
            return pty

        with mock.patch.object(terminal.Pty, "spawn", side_effect=spawn):
            response = await self.client.get("/v1/terminal", headers={
                **self.headers, "Upgrade": "websocket", "Connection": "Upgrade",
                "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ=="})
            self.assertEqual(response.status, 403)
            self.assertEqual((await response.json())["error"]["code"], "terminal_not_allowed")
        pty.close.assert_awaited_once()
        self.assertEqual(self.state.active_terminals, 0)

    async def test_lookup_failure_never_spawns_a_pty_or_advertises_terminal(self):
        with mock.patch.object(self.state.tokens, "lookup", side_effect=store.StoreUnavailable("test failure")), \
                mock.patch.object(terminal.Pty, "spawn") as spawn:
            for route in ("/v1/terminal", "/v1/capabilities"):
                response = await self.client.get(route, headers=self.headers)
                self.assertEqual(response.status, 503)
                self.assertEqual((await response.json())["error"]["code"], "store_unavailable")
            spawn.assert_not_called()

    async def test_store_failure_after_spawn_closes_child_before_upgrade(self):
        self.state.store.set_terminal_access(self.device.device_id, True)
        pty = mock.Mock()
        pty.close = mock.AsyncMock()

        async def spawn(_command):
            lookup.side_effect = store.StoreUnavailable("injected failure")
            return pty

        with mock.patch.object(self.state.tokens, "lookup", wraps=self.state.tokens.lookup) as lookup, \
                mock.patch.object(terminal.Pty, "spawn", side_effect=spawn):
            response = await self.client.get("/v1/terminal", headers={
                **self.headers, "Upgrade": "websocket", "Connection": "Upgrade",
                "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ=="})
            self.assertEqual(response.status, 503)
            self.assertEqual((await response.json())["error"]["code"], "store_unavailable")
        pty.close.assert_awaited_once()
        self.assertEqual(self.state.active_terminals, 0)

    async def test_device_revocation_during_spawn_closes_child_before_upgrade(self):
        self.state.store.set_terminal_access(self.device.device_id, True)
        pty = mock.Mock()
        pty.close = mock.AsyncMock()

        async def spawn(_command):
            self.state.store.revoke_device(self.token)
            return pty

        with mock.patch.object(terminal.Pty, "spawn", side_effect=spawn):
            response = await self.client.get("/v1/terminal", headers={
                **self.headers, "Upgrade": "websocket", "Connection": "Upgrade",
                "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ=="})
            self.assertEqual(response.status, 401)
            self.assertEqual((await response.json())["error"]["code"], "invalid_credentials")
        pty.close.assert_awaited_once()
        self.assertEqual(self.state.active_terminals, 0)

    async def test_open_socket_fails_closed_when_store_becomes_unavailable(self):
        self.state.store.set_terminal_access(self.device.device_id, True)
        with mock.patch.object(server, "_REVOKE_CHECK_INTERVAL", 0.01):
            ws = await self.client.ws_connect("/v1/terminal", headers=self.headers)
            self.assertEqual((await asyncio.wait_for(ws.receive(), 5)).type, WSMsgType.BINARY)
            with mock.patch.object(self.state.tokens, "lookup", side_effect=store.StoreUnavailable("injected failure")):
                while True:
                    message = await asyncio.wait_for(ws.receive(), 5)
                    if message.type == WSMsgType.CLOSE:
                        break
            self.assertEqual(ws.close_code, 1011)
            self.assertEqual(self.state.active_terminals, 0)
            await ws.close()

    async def test_grant_is_per_device_and_global_switch_still_applies(self):
        self.state.store.set_terminal_access(self.device.device_id, True)
        caps = await (await self.client.get("/v1/capabilities", headers=self.headers)).json()
        self.assertTrue(caps["features"]["terminal"])
        other, _ = self.state.store.mint_device("other")
        caps = await (await self.client.get("/v1/capabilities", headers={"Authorization": "Bearer " + other})).json()
        self.assertFalse(caps["features"]["terminal"])
        self.state.terminal_command = None
        caps = await (await self.client.get("/v1/capabilities", headers=self.headers)).json()
        self.assertFalse(caps["features"]["terminal"])
        self.assertEqual((await self.client.get("/v1/terminal", headers=self.headers)).status, 403)

    async def test_grant_revocation_closes_existing_socket_but_keeps_chat_credential(self):
        self.state.store.set_terminal_access(self.device.device_id, True)
        with mock.patch.object(server, "_REVOKE_CHECK_INTERVAL", 0.01):
            ws = await self.client.ws_connect("/v1/terminal", headers=self.headers)
            self.assertEqual((await asyncio.wait_for(ws.receive(), 5)).type, WSMsgType.BINARY)
            self.state.store.set_terminal_access(self.device.device_id, False)
            while True:
                message = await asyncio.wait_for(ws.receive(), 5)
                if message.type == WSMsgType.CLOSE:
                    break
            self.assertEqual(ws.close_code, 4403)
            self.assertEqual(message.extra, "terminal_revoked")
            self.assertIsNotNone(self.state.store.lookup_device(self.token))
            caps = await (await self.client.get("/v1/capabilities", headers=self.headers)).json()
            self.assertFalse(caps["features"]["terminal"])
            await ws.close()

    async def test_no_remote_grant_route(self):
        for route in ("/v1/devices/self/terminal", "/v1/devices/" + self.device.device_id + "/terminal"):
            response = await self.client.post(route, headers=self.headers, json={"allowed": True})
            self.assertIn(response.status, (403, 404, 405))
        self.assertFalse(self.state.store.lookup_device(self.token).terminal_allowed)


class TerminalGrantCliTest(unittest.TestCase):
    def test_failed_cli_update_has_nonzero_exit_and_no_internal_error_details(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {"BRIDGE_STATE_DB": os.path.join(directory, "state.db"), "HERMES_API_KEY": "test-only"}
            output = io.StringIO()
            with mock.patch.dict(os.environ, env, clear=True), contextlib.redirect_stderr(output), \
                    mock.patch.object(store.BridgeStore, "set_terminal_access", side_effect=store.StoreUnavailable("private failure details")):
                self.assertEqual(server.main(["admin", "grant-terminal", "missing"]), 1)
            self.assertIn("device store unavailable", output.getvalue())
            self.assertNotIn("private failure details", output.getvalue())

    def test_local_cli_grants_lists_and_revokes_without_printing_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "state.db")
            db = store.BridgeStore(path)
            token, device = db.mint_device("phone")
            db.close()
            env = {"BRIDGE_STATE_DB": path, "HERMES_API_KEY": "test-only", "BRIDGE_TERMINAL": "1"}
            output = io.StringIO()
            with mock.patch.dict(os.environ, env, clear=True), contextlib.redirect_stdout(output), \
                    contextlib.redirect_stderr(output), mock.patch.object(terminal, "default_command", side_effect=AssertionError):
                self.assertEqual(server.main(["admin", "grant-terminal", device.device_id]), 0)
                self.assertEqual(server.main(["admin", "list-devices"]), 0)
                self.assertIn("terminal=allowed", output.getvalue())
                self.assertEqual(server.main(["admin", "revoke-terminal", device.device_id]), 0)
                self.assertEqual(server.main(["admin", "grant-terminal", "missing"]), 1)
            self.assertNotIn(token, output.getvalue())
            db = store.BridgeStore(path)
            self.assertFalse(db.lookup_device(token).terminal_allowed)
            db.close()
