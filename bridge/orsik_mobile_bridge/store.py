"""Persistent, fail-closed device/pairing store for the Orsik Mobile Bridge.

Replaces the volatile in-memory stores from the PoC so a paired device
survives a bridge restart and a revoke stays in effect across restarts.

Design constraints:

* Pairing codes and device tokens are stored **only as SHA-256 hashes** — the
  plaintext code/token is returned once and never persisted or logged.
* Redeem is a single atomic ``UPDATE`` guarded by ``used = 0`` and an expiry
  check, so a code can only ever be redeemed once even under concurrent calls.
* Hard capacity caps for live codes and active devices; expired/used codes and
  revoked devices are pruned.
* The schema is versioned (``PRAGMA user_version``) and migrates forward; a
  newer/unknown schema is refused closed, never silently downgraded.
* Any database error raises :class:`StoreUnavailable` — callers must fail closed
  (503/401), never fail open.
* The database file is created 0600 inside a 0700 directory.

Only ``sqlite3`` from the standard library is used — no extra infrastructure.
"""

from __future__ import annotations

import hashlib
import os
import secrets
import sqlite3
import tempfile
import threading
import time
import uuid
from pathlib import Path

from . import core

# Contract alphabet: A-Z without I/L/O/U plus 1-9 (31 characters).
_CODE_TTL_SECONDS = core.CODE_TTL_SECONDS
_MAX_CODES = 1024
_MAX_DEVICES = 4096


class StoreUnavailable(RuntimeError):
    """Raised when the store cannot serve a request; callers fail closed."""


def _sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _harden_file(path: str) -> None:
    # Harden the main DB and its SQLite sidecars (WAL/SHM/journal) — all hold
    # only hashes, but must never be group/world readable. A failed chmod is
    # fail-closed: the store refuses to open rather than run with loose modes.
    for candidate in (path, path + "-wal", path + "-shm", path + "-journal"):
        try:
            os.chmod(candidate, 0o600)
        except FileNotFoundError:
            continue
        except OSError as exc:
            raise StoreUnavailable(
                "could not restrict permissions on the device store") from exc


def _migration_v0_to_v1(conn: sqlite3.Connection) -> None:
    """Create the initial pairing-code and device tables."""
    conn.execute(
        "CREATE TABLE IF NOT EXISTS pairing_codes ("
        " code_hash TEXT PRIMARY KEY,"
        " expires_at REAL NOT NULL,"
        " used INTEGER NOT NULL DEFAULT 0,"
        " created_at REAL NOT NULL)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS devices ("
        " token_hash TEXT PRIMARY KEY,"
        " device_id TEXT NOT NULL,"
        " label TEXT NOT NULL,"
        " profile TEXT NOT NULL,"
        " permissions TEXT NOT NULL,"
        " revoked INTEGER NOT NULL DEFAULT 0,"
        " issued_at REAL NOT NULL,"
        " created_at REAL NOT NULL)"
    )
    conn.execute("CREATE INDEX IF NOT EXISTS devices_revoked ON devices(revoked)")


# Ordered schema steps: element ``i`` migrates version ``i`` to ``i + 1``. A
# released step is never reordered or removed — new steps are appended.
_MIGRATIONS = (_migration_v0_to_v1,)

SCHEMA_VERSION = len(_MIGRATIONS)


class BridgeStore:
    """SQLite-backed pairing-code and device-token store.

    Use ``":memory:"`` for tests; otherwise pass a filesystem path. A
    ``threading.Lock`` serialises access because aiohttp may serve handlers from
    more than one thread.
    """

    def __init__(
        self,
        path: str,
        *,
        code_ttl: int = _CODE_TTL_SECONDS,
        max_codes: int = _MAX_CODES,
        max_devices: int = _MAX_DEVICES,
    ) -> None:
        self.path = path
        self._code_ttl = code_ttl
        self._max_codes = max_codes
        self._max_devices = max_devices
        self._lock = threading.Lock()
        if path != ":memory:":
            parent = Path(path).expanduser().parent
            # Only a directory the store creates itself is made 0700; an
            # existing (possibly shared) directory keeps its mode.
            if not parent.exists():
                try:
                    parent.mkdir(parents=True, mode=0o700)
                except OSError:
                    pass
            self.path = str(Path(path).expanduser())
        self._conn = self._open()

    # -- connection / migration ------------------------------------------------
    def _open(self) -> sqlite3.Connection:
        conn: sqlite3.Connection | None = None
        try:
            conn = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
            conn.execute("PRAGMA busy_timeout = 5000")
            if self.path != ":memory:":
                conn.execute("PRAGMA journal_mode = WAL")
            conn.execute("PRAGMA foreign_keys = ON")
            self._migrate(conn)
            if self.path != ":memory:":
                _harden_file(self.path)
            return conn
        except (sqlite3.DatabaseError, StoreUnavailable) as exc:
            if conn is not None:
                try:
                    conn.close()
                except sqlite3.Error:
                    pass
            if isinstance(exc, StoreUnavailable):
                raise
            raise StoreUnavailable("device store could not be opened") from exc

    def _migrate(self, conn: sqlite3.Connection) -> None:
        version = int(conn.execute("PRAGMA user_version").fetchone()[0])
        if version > SCHEMA_VERSION:
            raise StoreUnavailable("device store schema is newer than this bridge")
        if version < SCHEMA_VERSION:
            self._backup_before_migration(conn, version)
        while version < SCHEMA_VERSION:
            step = _MIGRATIONS[version]
            try:
                # One explicit transaction per step: either the step and its
                # version bump both land, or neither does.
                conn.execute("BEGIN IMMEDIATE")
                step(conn)
                conn.execute(f"PRAGMA user_version = {version + 1}")
                conn.execute("COMMIT")
            except Exception as exc:
                try:
                    conn.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise StoreUnavailable("device store migration failed") from exc
            version += 1

    def _backup_before_migration(self, conn: sqlite3.Connection, version: int) -> None:
        """Snapshot an existing file DB before it is migrated forward.

        A fresh database (version 0) has nothing to back up. An existing target
        is never overwritten: a second, timestamped file keeps the older backup
        intact. A failed backup aborts the migration — the store refuses to open
        rather than migrate without a way back.
        """
        if self.path == ":memory:" or not 1 <= version < SCHEMA_VERSION:
            return
        target = f"{self.path}.pre-v{version}.bak"
        if os.path.exists(target):
            target = f"{self.path}.pre-v{version}.{int(time.time())}.bak"
        try:
            self._write_backup(conn, target)
        except (OSError, sqlite3.Error) as exc:
            raise StoreUnavailable("pre-migration backup failed") from exc

    def _write_backup(self, conn: sqlite3.Connection, dest: str) -> None:
        # Write to a 0600 temp file in the destination directory and then
        # hard-link it into place: ``dest`` is either absent or a complete
        # backup, and an existing file is never replaced (os.link refuses).
        directory = os.path.dirname(os.path.abspath(dest))
        fd, tmp = tempfile.mkstemp(dir=directory, prefix=".backup-", suffix=".tmp")
        os.close(fd)
        try:
            os.chmod(tmp, 0o600)
            dest_conn = sqlite3.connect(tmp)
            try:
                with dest_conn:
                    conn.backup(dest_conn)
            finally:
                dest_conn.close()
            os.link(tmp, dest)
        finally:
            try:
                os.unlink(tmp)
            except OSError:
                pass

    def close(self) -> None:
        with self._lock:
            try:
                self._conn.close()
            except sqlite3.Error:
                pass

    # -- pairing codes ---------------------------------------------------------
    def issue_code(self, now: float | None = None) -> tuple[str, int] | None:
        now = time.time() if now is None else now
        with self._lock:
            try:
                self._prune_locked(now)
                active = self._conn.execute(
                    "SELECT COUNT(*) FROM pairing_codes WHERE used = 0 AND expires_at >= ?", (now,)
                ).fetchone()[0]
                if active >= self._max_codes:
                    return None
                code = core.generate_code()
                self._conn.execute(
                    "INSERT INTO pairing_codes (code_hash, expires_at, used, created_at) VALUES (?, ?, 0, ?)",
                    (_sha256_hex(code), now + self._code_ttl, now),
                )
                return code, self._code_ttl
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("pairing code issue failed") from exc

    def redeem_code(self, raw_code: object, now: float | None = None) -> bool:
        code = core.normalize_code(raw_code)
        if code is None:
            return False
        now = time.time() if now is None else now
        with self._lock:
            try:
                cur = self._conn.execute(
                    "UPDATE pairing_codes SET used = 1"
                    " WHERE code_hash = ? AND used = 0 AND expires_at >= ?",
                    (_sha256_hex(code), now),
                )
                return cur.rowcount == 1
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("pairing code redeem failed") from exc

    # -- devices ---------------------------------------------------------------
    def mint_device(self, label: str, profile: str = "default", now: float | None = None):
        now = time.time() if now is None else now
        with self._lock:
            try:
                self._prune_locked(now)
                active = self._conn.execute(
                    "SELECT COUNT(*) FROM devices WHERE revoked = 0"
                ).fetchone()[0]
                if active >= self._max_devices:
                    return None
                device_id = str(uuid.uuid4())
                token = "hd_" + secrets.token_urlsafe(32)
                record = core.DeviceRecord(device_id=device_id, label=label, profile=profile)
                self._conn.execute(
                    "INSERT INTO devices"
                    " (token_hash, device_id, label, profile, permissions, revoked, issued_at, created_at)"
                    " VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
                    (
                        _sha256_hex(token),
                        device_id,
                        label,
                        profile,
                        ",".join(record.permissions),
                        now,
                        now,
                    ),
                )
                return token, record
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("device mint failed") from exc

    def lookup_device(self, token: str | None):
        if not token:
            return None
        with self._lock:
            try:
                row = self._conn.execute(
                    "SELECT device_id, label, profile, permissions FROM devices"
                    " WHERE token_hash = ? AND revoked = 0",
                    (_sha256_hex(token),),
                ).fetchone()
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("device lookup failed") from exc
        if row is None:
            return None
        device_id, label, profile, permissions = row
        return core.DeviceRecord(
            device_id=device_id,
            label=label,
            profile=profile,
            permissions=tuple(permissions.split(",")) if permissions else core.DEVICE_PERMISSIONS,
        )

    def revoke_device(self, token: str | None):
        if not token:
            return None
        token_hash = _sha256_hex(token)
        with self._lock:
            try:
                row = self._conn.execute(
                    "SELECT device_id, label, profile, permissions, revoked FROM devices WHERE token_hash = ?",
                    (token_hash,),
                ).fetchone()
                if row is None:
                    return None
                self._conn.execute("UPDATE devices SET revoked = 1 WHERE token_hash = ?", (token_hash,))
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("device revoke failed") from exc
        device_id, label, profile, permissions, was_revoked = row
        return core.DeviceRecord(
            device_id=device_id,
            label=label,
            profile=profile,
            permissions=tuple(permissions.split(",")) if permissions else core.DEVICE_PERMISSIONS,
            revoked=True,
            already_revoked=bool(was_revoked),
        )

    def device_count(self) -> int:
        with self._lock:
            try:
                return int(self._conn.execute("SELECT COUNT(*) FROM devices WHERE revoked = 0").fetchone()[0])
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("device count failed") from exc

    def list_devices(self) -> list[dict]:
        """Non-secret device inventory for the operator CLI (never the token)."""
        with self._lock:
            try:
                rows = self._conn.execute(
                    "SELECT device_id, label, profile, permissions, revoked, issued_at"
                    " FROM devices ORDER BY created_at"
                ).fetchall()
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("device list failed") from exc
        return [
            {
                "device_id": r[0], "label": r[1], "profile": r[2],
                "permissions": r[3].split(",") if r[3] else [],
                "revoked": bool(r[4]), "issued_at": r[5],
            }
            for r in rows
        ]

    def revoke_device_by_id(self, device_id: str) -> bool:
        """Operator-side durable revoke by device_id; True if a device matched."""
        with self._lock:
            try:
                cur = self._conn.execute(
                    "UPDATE devices SET revoked = 1 WHERE device_id = ?", (device_id,)
                )
                return cur.rowcount >= 1
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("device revoke failed") from exc

    # -- maintenance -----------------------------------------------------------
    def backup_to(self, dest: str) -> None:
        """Write an atomic, 0600 snapshot of the live store to ``dest``.

        Refuses to overwrite an existing file so a stale backup is never
        silently replaced. Raises :class:`StoreUnavailable` on any failure.
        """
        with self._lock:
            if os.path.exists(dest):
                raise StoreUnavailable(f"backup target already exists: {dest}")
            try:
                self._write_backup(self._conn, dest)
            except (OSError, sqlite3.Error) as exc:
                raise StoreUnavailable("store backup failed") from exc

    def _prune_locked(self, now: float) -> None:
        self._conn.execute(
            "DELETE FROM pairing_codes WHERE used = 1 OR expires_at < ?", (now,)
        )
        self._conn.execute("DELETE FROM devices WHERE revoked = 1")

    def prune(self, now: float | None = None) -> None:
        now = time.time() if now is None else now
        with self._lock:
            try:
                self._prune_locked(now)
            except sqlite3.DatabaseError as exc:
                raise StoreUnavailable("device store prune failed") from exc

    # -- adapters keeping the server's existing store interface ----------------
    @property
    def codes(self) -> "_CodeApi":
        return _CodeApi(self)

    @property
    def tokens(self) -> "_TokenApi":
        return _TokenApi(self)


class _CodeApi:
    """Pairs the server's ``codes.issue()/redeem()`` calls onto the store."""

    def __init__(self, store: BridgeStore) -> None:
        self._store = store

    def issue(self, now: float | None = None):
        return self._store.issue_code(now)

    def redeem(self, raw_code: object, now: float | None = None) -> bool:
        return self._store.redeem_code(raw_code, now)


class _TokenApi:
    """Pairs the server's ``tokens.mint()/lookup()/revoke()`` calls onto the store."""

    def __init__(self, store: BridgeStore) -> None:
        self._store = store

    def mint(self, label: str, profile: str = "default"):
        return self._store.mint_device(label, profile)

    def lookup(self, token: str | None):
        return self._store.lookup_device(token)

    def revoke(self, token: str | None):
        return self._store.revoke_device(token)

    def count(self) -> int:
        return self._store.device_count()
