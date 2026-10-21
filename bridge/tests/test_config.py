"""Configuration-hardening tests for the bridge.

``BRIDGE_UPSTREAM`` decides where the bridge attaches the Hermes master key. A
misconfigured value is therefore a master-key-exfiltration primitive. These lock
the contract: only a loopback plaintext-HTTP IP literal with an explicit port is
accepted, the rejected value is never echoed back, and a rejection happens
before any store is opened.
"""

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from orsik_mobile_bridge import server as bridge_server

DEFAULT_UPSTREAM = "http://127.0.0.1:8642"

# The mandated error text is fixed and public; it intentionally contains the
# example loopback URL. It must never be built from the rejected value.
ERROR_MESSAGE = "BRIDGE_UPSTREAM must be loopback HTTP, e.g. http://127.0.0.1:8642"

ACCEPTED = (
    "http://127.0.0.1:8899",
    "http://[::1]:8899",
    "http://127.0.0.2:9000",
)

REJECTED = (
    "https://127.0.0.1:8899",
    "http://example.com:8899",
    "http://localhost:8899",
    "http://10.0.0.1:8899",
    "http://0.0.0.0:8899",
    "http://user:pw@127.0.0.1:8899",
    "http://127.0.0.1:8899/",
    "http://127.0.0.1:8899/x",
    "http://127.0.0.1:8899?q=1",
    "http://127.0.0.1:8899#f",
    "http://127.0.0.1:notaport",
    "http://127.0.0.1",
    "",
    "http://127.0.0.1.evil.example:8899",
)

# A literal substring check cannot hold for the empty string (contained in
# everything) nor for a value that is itself part of the fixed example URL.
ECHO_EXEMPT = ("", "http://127.0.0.1")


def _env(**overrides: str) -> dict[str, str]:
    env = {"HERMES_API_KEY": "master_key_test", "BRIDGE_STATE_DB": ":memory:"}
    env.update(overrides)
    return env


class UpstreamValidationTest(unittest.TestCase):
    def test_missing_upstream_defaults_to_loopback(self):
        state = bridge_server.build_state_from_env(_env())
        self.addCleanup(state.store.close)
        self.assertEqual(state.upstream, DEFAULT_UPSTREAM)

    def test_accepted_upstreams_are_kept_verbatim(self):
        for value in ACCEPTED:
            with self.subTest(upstream=value):
                state = bridge_server.build_state_from_env(
                    _env(BRIDGE_UPSTREAM=value))
                self.addCleanup(state.store.close)
                self.assertEqual(state.upstream, value)

    def test_rejected_upstreams_exit(self):
        for value in REJECTED:
            with self.subTest(upstream=value):
                with self.assertRaises(SystemExit) as cm:
                    bridge_server.build_state_from_env(_env(BRIDGE_UPSTREAM=value))
                message = str(cm.exception)
                # No chained ValueError (its text may contain the value).
                exc = cm.exception
                self.assertTrue(exc.__context__ is None or exc.__suppress_context__)
                # The message is the fixed public text, so it can never carry a
                # config-derived value (e.g. userinfo secrets).
                self.assertEqual(message, ERROR_MESSAGE)
                if value not in ECHO_EXEMPT:
                    self.assertNotIn(value, message)

    def test_rejection_precedes_store_creation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "state.db")
            with self.assertRaises(SystemExit):
                bridge_server.build_state_from_env(
                    _env(BRIDGE_STATE_DB=path, BRIDGE_UPSTREAM="http://10.0.0.1:8899"))
            self.assertFalse(os.path.exists(path))


class BindValidationTest(unittest.TestCase):
    def test_literal_loopback_ips_accepted(self):
        for value in ("127.0.0.1", "::1", "127.0.0.2"):
            with self.subTest(bind=value):
                self.assertEqual(bridge_server._validate_bind(value), value)

    def test_localhost_and_non_loopback_refused(self):
        for value in ("localhost", "0.0.0.0", "::", "10.0.0.1", "example.com", ""):
            with self.subTest(bind=value):
                with self.assertRaises(SystemExit) as cm:
                    bridge_server._validate_bind(value)
                self.assertIn("literal loopback", str(cm.exception))


class SecretFileHardeningTest(unittest.TestCase):
    MARKER = "fixture_marker_must_not_leak"

    def _write(self, directory, mode, name="hermes.env"):
        path = os.path.join(directory, name)
        Path(path).write_text(f"API_SERVER_KEY={self.MARKER}\n")
        os.chmod(path, mode)
        return path

    def test_group_or_world_accessible_secret_file_aborts(self):
        for mode in (0o640, 0o604, 0o644, 0o660, 0o610):
            with self.subTest(mode=oct(mode)), tempfile.TemporaryDirectory() as d:
                path = self._write(d, mode)
                with self.assertRaises(SystemExit) as cm:
                    bridge_server.build_state_from_env(_env(BRIDGE_HERMES_ENV=path))
                message = str(cm.exception)
                self.assertIn(path, message)
                self.assertIn("chmod 600", message)
                self.assertNotIn(self.MARKER, message)

    def test_non_regular_secret_file_aborts(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "as-directory")
            os.mkdir(path)
            with self.assertRaises(SystemExit) as cm:
                bridge_server.build_state_from_env(_env(BRIDGE_HERMES_ENV=path))
            self.assertIn(path, str(cm.exception))
            self.assertIn("chmod 600", str(cm.exception))

    def test_0600_secret_file_is_read(self):
        with tempfile.TemporaryDirectory() as d:
            path = self._write(d, 0o600)
            state = bridge_server.build_state_from_env(_env(BRIDGE_HERMES_ENV=path))
            self.addCleanup(state.store.close)
            self.assertEqual(state.master_key, self.MARKER)

    def test_bridge_env_file_is_checked_before_reading(self):
        with tempfile.TemporaryDirectory() as d:
            path = self._write(d, 0o644, name="bridge.env")
            with mock.patch.dict(os.environ, {"BRIDGE_ENV_FILE": path}, clear=True):
                with self.assertRaises(SystemExit) as cm:
                    bridge_server._load_env()
            self.assertIn(path, str(cm.exception))
            self.assertIn("chmod 600", str(cm.exception))


class StartupUmaskTest(unittest.TestCase):
    def test_main_sets_restrictive_umask_before_state_creation(self):
        calls = []
        state = bridge_server.BridgeState("http://127.0.0.1:8642", "test_key")
        self.addCleanup(state.store.close)

        def fake_build(_env):
            calls.append("state")
            return state

        with mock.patch.object(
                bridge_server.os, "umask",
                side_effect=lambda mode: calls.append(("umask", mode))), \
                mock.patch.object(bridge_server, "_load_env", return_value={}), \
                mock.patch.object(bridge_server, "build_state_from_env", side_effect=fake_build), \
                mock.patch.object(bridge_server.web, "run_app"):
            self.assertEqual(bridge_server.main([]), 0)
        self.assertEqual(calls, [("umask", 0o077), "state"])


if __name__ == "__main__":
    unittest.main()
