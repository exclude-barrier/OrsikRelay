"""Persistence and security tests for the bridge device store.

These lock the durable-credential guarantees: pairing/revoke survive a restart,
codes are single-use and atomic under concurrency, TTL and caps hold, tokens are
never stored in plaintext, the schema migrates and refuses newer versions, and a
corrupt database fails closed.
"""

import glob
import os
import shutil
import sqlite3
import tempfile
import threading
import unittest
from unittest import mock

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

    # 8. Migration backups and operator backups --------------------------------
    def _seed_v1_db(self) -> str:
        """Create a version-1 file DB to exercise migration of existing devices."""
        with mock.patch.object(store_mod, "_MIGRATIONS", (store_mod._migration_v0_to_v1,)), \
                mock.patch.object(store_mod, "SCHEMA_VERSION", 1):
            store = self._open()
            token, _ = store.tokens.mint("laptop", "default")
            store.close()
        return token

    def test_fresh_file_db_migrates_without_backup(self):
        store = self._open()
        self.assertEqual(
            store._conn.execute("PRAGMA user_version").fetchone()[0],
            store_mod.SCHEMA_VERSION,
        )
        store.close()
        self.assertEqual(glob.glob(self.path + "*.bak"), [])

    def test_migration_n_to_n_plus_one_backs_up_and_applies(self):
        token = self._seed_v1_db()

        def step_add_column(conn):
            conn.execute("ALTER TABLE devices ADD COLUMN note TEXT")

        patched = store_mod._MIGRATIONS + (step_add_column,)
        with mock.patch.object(store_mod, "_MIGRATIONS", patched), mock.patch.object(
            store_mod, "SCHEMA_VERSION", len(patched)
        ):
            store = self._open()
            self.assertEqual(
                store._conn.execute("PRAGMA user_version").fetchone()[0], len(patched)
            )
            columns = [r[1] for r in store._conn.execute("PRAGMA table_info(devices)")]
            self.assertIn("note", columns)
            self.assertIsNotNone(store.tokens.lookup(token))
            store.close()

        backup = "%s.pre-v1.bak" % self.path
        self.assertTrue(os.path.exists(backup))
        self.assertEqual(os.stat(backup).st_mode & 0o777, 0o600)
        raw = sqlite3.connect(backup)
        try:
            self.assertEqual(raw.execute("PRAGMA user_version").fetchone()[0], 1)
            hashes = [r[0] for r in raw.execute("SELECT token_hash FROM devices")]
        finally:
            raw.close()
        self.assertEqual(hashes, [store_mod._sha256_hex(token)])

    def test_failing_migration_step_keeps_old_schema_and_data(self):
        token = self._seed_v1_db()

        def broken_step(conn):
            raise sqlite3.OperationalError("boom")

        patched = store_mod._MIGRATIONS + (broken_step,)
        with mock.patch.object(store_mod, "_MIGRATIONS", patched), mock.patch.object(
            store_mod, "SCHEMA_VERSION", len(patched)
        ):
            with self.assertRaises(store_mod.StoreUnavailable):
                self._open()

        store = self._open()
        self.assertEqual(store._conn.execute("PRAGMA user_version").fetchone()[0], store_mod.SCHEMA_VERSION)
        self.assertIsNotNone(store.tokens.lookup(token))
        store.close()

    def test_existing_backup_target_is_never_overwritten(self):
        self._seed_v1_db()
        first = "%s.pre-v1.bak" % self.path
        with open(first, "wb") as fh:
            fh.write(b"sentinel")

        def noop_step(conn):
            return None

        patched = store_mod._MIGRATIONS + (noop_step,)
        with mock.patch.object(store_mod, "_MIGRATIONS", patched), mock.patch.object(
            store_mod, "SCHEMA_VERSION", len(patched)
        ):
            store = self._open()
            store.close()

        with open(first, "rb") as fh:
            self.assertEqual(fh.read(), b"sentinel")
        fresh = glob.glob("%s.pre-v1.*.bak" % self.path)
        self.assertEqual(len(fresh), 1)
        raw = sqlite3.connect(fresh[0])
        try:
            self.assertEqual(raw.execute("PRAGMA user_version").fetchone()[0], 1)
            self.assertEqual(raw.execute("SELECT COUNT(*) FROM devices").fetchone()[0], 1)
        finally:
            raw.close()

    def test_backup_to_writes_readable_copy(self):
        dest = os.path.join(self._dir.name, "backup.db")
        store = self._open()
        token, record = store.tokens.mint("phone", "default")
        store.backup_to(dest)
        self.assertEqual(os.stat(dest).st_mode & 0o777, 0o600)
        with self.assertRaises(store_mod.StoreUnavailable):
            store.backup_to(dest)
        store.close()

        restored = store_mod.BridgeStore(dest)
        try:
            got = restored.lookup_device(token)
            self.assertIsNotNone(got)
            self.assertEqual(got.device_id, record.device_id)
        finally:
            restored.close()

    def test_restore_of_old_backup_restores_old_authorization_state(self):
        dest = os.path.join(self._dir.name, "backup.db")
        store = self._open()
        token, _ = store.tokens.mint("phone", "default")
        store.backup_to(dest)
        store.tokens.revoke(token)
        self.assertIsNone(store.tokens.lookup(token))
        store.close()

        shutil.copyfile(dest, self.path)

        restored = self._open()
        self.assertIsNotNone(restored.lookup_device(token))
        restored.close()

    def test_admin_backup_cli_writes_file_and_refuses_existing_target(self):
        from orsik_mobile_bridge import server as bridge_server

        seed = self._open()
        seed.tokens.mint("phone", "default")
        seed.close()
        dest = os.path.join(self._dir.name, "cli-backup.db")
        env = {"HERMES_API_KEY": "master_key_test", "BRIDGE_STATE_DB": self.path}
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("BRIDGE_ENV_FILE", None)
            self.assertEqual(bridge_server.main(["admin", "backup", dest]), 0)
            self.assertEqual(os.stat(dest).st_mode & 0o777, 0o600)
            self.assertEqual(bridge_server.main(["admin", "backup", dest]), 1)
        raw = sqlite3.connect(dest)
        try:
            self.assertEqual(raw.execute("SELECT COUNT(*) FROM devices").fetchone()[0], 1)
        finally:
            raw.close()

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


class StoreHardeningTest(unittest.TestCase):
    def test_journal_is_hardened_when_present(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "state.db")
            store = store_mod.BridgeStore(path)
            store.close()
            journal = path + "-journal"
            with open(journal, "wb") as fh:
                fh.write(b"")
            os.chmod(journal, 0o644)
            store_mod._harden_file(path)
            self.assertEqual(os.stat(journal).st_mode & 0o777, 0o600)

    def test_failed_chmod_refuses_to_open_the_store(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "state.db")
            with mock.patch.object(store_mod.os, "chmod", side_effect=OSError("denied")):
                with self.assertRaises(store_mod.StoreUnavailable):
                    store_mod.BridgeStore(path)


if __name__ == "__main__":
    unittest.main()


class StoreDirectoryModeTest(unittest.TestCase):
    def test_an_existing_directory_keeps_its_mode_and_a_new_one_is_private(self):
        import os, tempfile
        from orsik_mobile_bridge import store as store_mod
        with tempfile.TemporaryDirectory() as root:
            shared = os.path.join(root, "shared")
            os.mkdir(shared)
            os.chmod(shared, 0o755)
            store_mod.BridgeStore(os.path.join(shared, "state.db")).close()
            self.assertEqual(os.stat(shared).st_mode & 0o777, 0o755)
            fresh = os.path.join(root, "fresh")
            store_mod.BridgeStore(os.path.join(fresh, "state.db")).close()
            self.assertEqual(os.stat(fresh).st_mode & 0o077, 0)
