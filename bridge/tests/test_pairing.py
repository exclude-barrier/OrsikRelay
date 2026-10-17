"""Tests for the operator pairing CLI and deep-link helpers."""

import contextlib
import io
import os
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
