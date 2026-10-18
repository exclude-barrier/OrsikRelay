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


if __name__ == "__main__":
    unittest.main()
