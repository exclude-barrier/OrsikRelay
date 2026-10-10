"""Persistence and security tests for the bridge device store (P13 Phase 4).

These lock the durable-credential guarantees: pairing/revoke survive a restart,
codes are single-use and atomic under concurrency, TTL and caps hold, tokens are
never stored in plaintext, the schema migrates and refuses newer versions, and a
corrupt database fails closed.
"""

import os
import tempfile
import threading
import unittest

from orsik_mobile_bridge import core
from orsik_mobile_bridge import store as store_mod


class StorePersistenceTest(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self._dir.name, "state.db")

    def tearDown(self):
        self._dir.cleanup()

    def _open(self, **kw) -> store_mod.BridgeStore:
        return store_mod.BridgeStore(self.path, **kw)

    # 1. Pairing survives a restart -----------------------------------------
    def test_pairing_survives_restart(self):
        s1 = self._open()
        code, _ = s1.codes.issue()
        self.assertTrue(s1.codes.redeem(code))
        token, record = s1.tokens.mint("laptop", "default")
        s1.close()

        s2 = self._open()
        got = s2.tokens.lookup(token)
        self.assertIsNotNone(got)
        self.assertEqual(got.device_id, record.device_id)
        self.assertEqual(got.label, "laptop")
        s2.close()

    # 2. Revoke survives a restart ------------------------------------------
    def test_revoke_survives_restart(self):
        s1 = self._open()
        token, _ = s1.tokens.mint("phone", "default")
        s1.tokens.revoke(token)
        s1.close()

        s2 = self._open()
        self.assertIsNone(s2.tokens.lookup(token))
        # Re-revoking an already-revoked device still reports it (idempotent).
        rec = s2.tokens.revoke(token)
        self.assertIsNotNone(rec)
        s2.close()

    # 3. A code can only be redeemed once ------------------------------------
    def test_code_single_use(self):
        s = self._open()
        code, _ = s.codes.issue()
        self.assertTrue(s.codes.redeem(code))
        self.assertFalse(s.codes.redeem(code))
        s.close()

    # 4. Parallel redeem: exactly one winner ---------------------------------
    def test_parallel_redeem_single_winner(self):
        s = self._open()
        code, _ = s.codes.issue()
        results = []
        barrier = threading.Barrier(8)

        def worker():
            barrier.wait()
            results.append(s.codes.redeem(code))

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(results.count(True), 1)
        s.close()

    # 5. Expiry ---------------------------------------------------------------
    def test_code_expiry(self):
        s = self._open()
        code, ttl = s.codes.issue(now=1000.0)
        self.assertFalse(s.codes.redeem(code, now=1000.0 + ttl + 1))
        s.close()

    # 6. Corrupt store fails closed ------------------------------------------
    def test_corrupt_store_fails_closed(self):
        with open(self.path, "wb") as fh:
            fh.write(b"this is not a sqlite database at all")
        with self.assertRaises(store_mod.StoreUnavailable):
            store_mod.BridgeStore(self.path)

    # 7. Schema migration -----------------------------------------------------
    def test_schema_migrates_and_reopens(self):
        s1 = self._open()
        self.assertEqual(s1._conn.execute("PRAGMA user_version").fetchone()[0], store_mod.SCHEMA_VERSION)
        s1.close()
        # Reopening an up-to-date schema is a no-op migration.
        s2 = self._open()
        s2.close()

    def test_newer_schema_refused(self):
        s1 = self._open()
        s1._conn.execute(f"PRAGMA user_version = {store_mod.SCHEMA_VERSION + 1}")
        s1.close()
        with self.assertRaises(store_mod.StoreUnavailable):
            store_mod.BridgeStore(self.path)

    # Secret handling ---------------------------------------------------------
    def test_tokens_never_stored_in_plaintext(self):
        s = self._open()
        token, _ = s.tokens.mint("laptop", "default")
        code, _ = s.codes.issue()
        s.close()
        with open(self.path, "rb") as fh:
            blob = fh.read()
        self.assertNotIn(token.encode(), blob)
        self.assertNotIn(code.encode(), blob)

    def test_database_file_is_0600(self):
        s = self._open()
        s.tokens.mint("laptop", "default")
        s.close()
        self.assertEqual(os.stat(self.path).st_mode & 0o777, 0o600)

    # Capacity caps -----------------------------------------------------------
    def test_device_cap_enforced(self):
        s = self._open(max_devices=1)
        self.assertIsNotNone(s.tokens.mint("a", "default"))
        self.assertIsNone(s.tokens.mint("b", "default"))
        s.close()

    def test_code_cap_enforced(self):
        s = self._open(max_codes=1)
        self.assertIsNotNone(s.codes.issue())
        self.assertIsNone(s.codes.issue())
        s.close()

    # Permissions least-privilege --------------------------------------------
    def test_permissions_fixed(self):
        s = self._open()
        _, record = s.tokens.mint("a", "default")
        self.assertEqual(record.permissions, core.DEVICE_PERMISSIONS)
        s.close()


if __name__ == "__main__":
    unittest.main()
