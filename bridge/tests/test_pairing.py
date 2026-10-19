"""Tests for the operator pairing CLI and deep-link helpers."""

import contextlib
import io
import os
import subprocess
import tempfile
import unittest
from unittest import mock

from orsik_mobile_bridge import pairing
from orsik_mobile_bridge import server as bridge_server
from orsik_mobile_bridge import store as store_mod


class PairingUriTest(unittest.TestCase):
    def test_uri_exact_encoding(self):
        uri = pairing.pairing_uri("https://cf.example.ts.net", "ABCD2345EFGH6789JKMN2345")
        self.assertEqual(
            uri,
            "orsik://pair?server=https%3A%2F%2Fcf.example.ts.net&code=ABCD2345EFGH6789JKMN2345",
        )

    def test_uri_rejects_non_https_and_userinfo(self):
        for bad in ("http://x.example", "https://user:pass@x.example", "https://", "ftp://x.example"):
            with self.assertRaises(ValueError, msg=bad):
                pairing.pairing_uri(bad, "ABCD2345EFGH6789JKMN2345")

    def test_format_code_groups_of_four(self):
        self.assertEqual(pairing.format_code("ABCD2345EFGH6789JKMN2345"), "ABCD-2345-EFGH-6789-JKMN-2345")


class RenderAsciiQrTest(unittest.TestCase):
    TEXT = "orsik://pair?server=https%3A%2F%2Fcf.example.ts.net&code=ABCD2345EFGH6789JKMN2345"

    def test_missing_renderer_returns_none(self):
        with mock.patch.object(pairing.shutil, "which", return_value=None):
            self.assertIsNone(pairing.render_ascii_qr(self.TEXT))

    def test_uses_ansiutf8_with_quiet_zone_and_stdin(self):
        completed = mock.Mock(stdout="\x1b[40;37;1m▀▄█\x1b[0m\n".encode())
        with mock.patch.object(pairing.shutil, "which", return_value="/usr/bin/qrencode"), \
                mock.patch.object(pairing.subprocess, "run", return_value=completed) as run:
            self.assertEqual(pairing.render_ascii_qr(self.TEXT), "\x1b[40;37;1m▀▄█\x1b[0m\n")
        argv = run.call_args.args[0]
        self.assertEqual(
            argv,
            ["/usr/bin/qrencode", "-t", "ANSIUTF8", "-o", "-", "-m", "2"],
        )
        self.assertNotIn(self.TEXT, argv)
        self.assertEqual(
            run.call_args.kwargs,
            {
                "input": self.TEXT.encode("utf-8"),
                "capture_output": True,
                "check": True,
                "timeout": 10,
            },
        )

    def test_renderer_failure_returns_none(self):
        failures = (
            OSError("no qrencode"),
            subprocess.TimeoutExpired(cmd="qrencode", timeout=10),
            subprocess.CalledProcessError(1, "qrencode"),
        )
        for failure in failures:
            with self.subTest(failure=type(failure).__name__), \
                    mock.patch.object(pairing.shutil, "which", return_value="/usr/bin/qrencode"), \
                    mock.patch.object(pairing.subprocess, "run", side_effect=failure):
                self.assertIsNone(pairing.render_ascii_qr(self.TEXT))


class AdminCliTest(unittest.TestCase):
    def test_pairing_code_cli_issues_redeemable_code(self):
        with tempfile.TemporaryDirectory() as d:
            env = {"BRIDGE_STATE_DB": os.path.join(d, "state.db"), "HERMES_API_KEY": "master_test"}
            out = io.StringIO()
            with mock.patch.dict(os.environ, env, clear=False), contextlib.redirect_stdout(out):
                rc = bridge_server.main(["admin", "pairing-code", "--base-url", "https://cf.example.ts.net"])
            self.assertEqual(rc, 0)
            text = out.getvalue()
            self.assertIn("Pairing code:", text)
            self.assertIn("orsik://pair?server=https%3A%2F%2Fcf.example.ts.net", text)

            # Extract the grouped code and confirm it redeems against the store.
            grouped = text.split("Pairing code:", 1)[1].split("(", 1)[0].strip()
            raw = grouped.replace("-", "")
            store = store_mod.BridgeStore(env["BRIDGE_STATE_DB"])
            try:
                self.assertTrue(store.redeem_code(raw))
                self.assertFalse(store.redeem_code(raw))  # single-use
            finally:
                store.close()

    def test_revoke_device_cli(self):
        with tempfile.TemporaryDirectory() as d:
            db = os.path.join(d, "state.db")
            store = store_mod.BridgeStore(db)
            token, record = store.mint_device("phone", "default")
            store.close()
            env = {"BRIDGE_STATE_DB": db, "HERMES_API_KEY": "master_test"}
            out = io.StringIO()
            with mock.patch.dict(os.environ, env, clear=False), contextlib.redirect_stdout(out):
                rc = bridge_server.main(["admin", "revoke-device", record.device_id])
            self.assertEqual(rc, 0)
            store = store_mod.BridgeStore(db)
            try:
                self.assertIsNone(store.lookup_device(token))
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
