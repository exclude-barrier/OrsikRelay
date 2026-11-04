//! Persistent, fail-closed device and pairing store.
//!
//! The same SQLite file and schema as the Python bridge (`store.py`), so a
//! device paired before the Rust cutover keeps working. Codes and keys are
//! stored only as SHA-256 hex; redeem is one atomic `UPDATE`; any database
//! error is `StoreUnavailable` and callers answer closed.

use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{Connection, OptionalExtension, params};
use serde_json::Value;

use crate::core::{self, DeviceRecord};

pub const MAX_CODES: i64 = 1024;
pub const MAX_DEVICES: i64 = 4096;
pub const SCHEMA_VERSION: i64 = 2;

#[derive(Debug)]
pub struct StoreUnavailable(pub String);

impl std::fmt::Display for StoreUnavailable {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

type Result<T> = std::result::Result<T, StoreUnavailable>;

fn unavailable(message: &str) -> impl Fn(rusqlite::Error) -> StoreUnavailable + '_ {
    move |_| StoreUnavailable(message.to_string())
}

pub fn now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

/// One device in the operator listing (never the key).
pub struct DeviceListing {
    pub device_id: String,
    pub label: String,
    pub profile: String,
    pub revoked: bool,
    pub terminal_allowed: bool,
}

pub struct Store {
    path: PathBuf,
    conn: Mutex<Connection>,
}

fn harden(path: &Path) -> Result<()> {
    for suffix in ["", "-wal", "-shm", "-journal"] {
        let candidate = PathBuf::from(format!("{}{}", path.display(), suffix));
        match std::fs::set_permissions(&candidate, std::fs::Permissions::from_mode(0o600)) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => {
                return Err(StoreUnavailable(
                    "could not restrict permissions on the device store".into(),
                ));
            }
        }
    }
    Ok(())
}

fn migrate_v0_to_v1(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS pairing_codes ( code_hash TEXT PRIMARY KEY, expires_at REAL NOT NULL, \
         used INTEGER NOT NULL DEFAULT 0, created_at REAL NOT NULL);\
         CREATE TABLE IF NOT EXISTS devices ( token_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL, \
         label TEXT NOT NULL, profile TEXT NOT NULL, permissions TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, \
         issued_at REAL NOT NULL, created_at REAL NOT NULL);\
         CREATE INDEX IF NOT EXISTS devices_revoked ON devices(revoked);",
    )
}

fn migrate_v1_to_v2(conn: &Connection) -> rusqlite::Result<()> {
    conn.execute_batch(
        "ALTER TABLE devices ADD COLUMN terminal_allowed INTEGER NOT NULL DEFAULT 0 CHECK (terminal_allowed IN (0, 1))",
    )
}

/// Write a 0600 snapshot to `dest` via a temp file and a hard link, so
/// `dest` is either absent or complete and an existing file is never replaced.
fn write_backup(conn: &Connection, dest: &Path) -> std::result::Result<(), String> {
    let dir = dest
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let tmp = tempfile_in(dir)?;
    let result = (|| {
        std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600))
            .map_err(|e| e.to_string())?;
        let mut target = Connection::open(&tmp).map_err(|e| e.to_string())?;
        {
            let backup =
                rusqlite::backup::Backup::new(conn, &mut target).map_err(|e| e.to_string())?;
            backup
                .run_to_completion(256, std::time::Duration::ZERO, None)
                .map_err(|e| e.to_string())?;
        }
        drop(target);
        std::fs::hard_link(&tmp, dest).map_err(|e| e.to_string())
    })();
    let _ = std::fs::remove_file(&tmp);
    result
}

fn tempfile_in(dir: &Path) -> std::result::Result<PathBuf, String> {
    for _ in 0..16 {
        let candidate = dir.join(format!(".backup-{}.tmp", uuid::Uuid::new_v4().simple()));
        match std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&candidate)
        {
            Ok(_) => return Ok(candidate),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e.to_string()),
        }
    }
    Err("no temp file".into())
}

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        // Only a directory the store creates itself is made 0700.
        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty())
            && !parent.exists()
        {
            let _ = std::fs::DirBuilder::new()
                .recursive(true)
                .mode(0o700)
                .create(parent);
        }
        let conn =
            Connection::open(path).map_err(unavailable("device store could not be opened"))?;
        let store = Store {
            path: path.to_path_buf(),
            conn: Mutex::new(conn),
        };
        {
            let conn = store.conn.lock().unwrap();
            conn.busy_timeout(std::time::Duration::from_millis(5000))
                .map_err(unavailable("device store could not be opened"))?;
            conn.pragma_update_and_check(None, "journal_mode", "WAL", |_| Ok(()))
                .map_err(unavailable("device store could not be opened"))?;
            conn.pragma_update(None, "foreign_keys", "ON")
                .map_err(unavailable("device store could not be opened"))?;
            store.migrate(&conn)?;
        }
        harden(path)?;
        Ok(store)
    }

    fn migrate(&self, conn: &Connection) -> Result<()> {
        let mut version: i64 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .map_err(unavailable("device store could not be opened"))?;
        if version > SCHEMA_VERSION {
            return Err(StoreUnavailable(
                "device store schema is newer than this bridge".into(),
            ));
        }
        if (1..SCHEMA_VERSION).contains(&version) {
            let mut target = PathBuf::from(format!("{}.pre-v{version}.bak", self.path.display()));
            if target.exists() {
                target = PathBuf::from(format!(
                    "{}.pre-v{version}.{}.bak",
                    self.path.display(),
                    now() as i64
                ));
            }
            write_backup(conn, &target)
                .map_err(|_| StoreUnavailable("pre-migration backup failed".into()))?;
        }
        while version < SCHEMA_VERSION {
            let step = || -> rusqlite::Result<()> {
                conn.execute_batch("BEGIN IMMEDIATE")?;
                if version == 0 {
                    migrate_v0_to_v1(conn)?
                } else {
                    migrate_v1_to_v2(conn)?
                }
                conn.execute_batch(&format!("PRAGMA user_version = {}; COMMIT", version + 1))
            };
            if step().is_err() {
                let _ = conn.execute_batch("ROLLBACK");
                return Err(StoreUnavailable("device store migration failed".into()));
            }
            version += 1;
        }
        Ok(())
    }

    fn prune(conn: &Connection, now: f64) -> rusqlite::Result<()> {
        conn.execute(
            "DELETE FROM pairing_codes WHERE used = 1 OR expires_at < ?",
            params![now],
        )?;
        conn.execute("DELETE FROM devices WHERE revoked = 1", [])?;
        Ok(())
    }

    /// A new one-time code and its lifetime, or None at the cap.
    pub fn issue_code(&self) -> Result<Option<(String, u64)>> {
        let now = now();
        let conn = self.conn.lock().unwrap();
        let run = || -> rusqlite::Result<Option<(String, u64)>> {
            Self::prune(&conn, now)?;
            let active: i64 = conn.query_row(
                "SELECT COUNT(*) FROM pairing_codes WHERE used = 0 AND expires_at >= ?",
                params![now],
                |r| r.get(0),
            )?;
            if active >= MAX_CODES {
                return Ok(None);
            }
            let code = core::generate_code();
            conn.execute(
                "INSERT INTO pairing_codes (code_hash, expires_at, used, created_at) VALUES (?, ?, 0, ?)",
                params![core::sha256_hex(&code), now + core::CODE_TTL_SECONDS as f64, now],
            )?;
            Ok(Some((code, core::CODE_TTL_SECONDS)))
        };
        run().map_err(unavailable("pairing code issue failed"))
    }

    pub fn redeem_code(&self, raw: &Value) -> Result<bool> {
        let Some(code) = core::normalize_code(raw) else {
            return Ok(false);
        };
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE pairing_codes SET used = 1 WHERE code_hash = ? AND used = 0 AND expires_at >= ?",
            params![core::sha256_hex(&code), now()],
        )
        .map(|changed| changed == 1)
        .map_err(unavailable("pairing code redeem failed"))
    }

    /// A new device key and its record, or None at the cap.
    pub fn mint_device(
        &self,
        label: &str,
        profile: &str,
    ) -> Result<Option<(String, DeviceRecord, f64)>> {
        let now = now();
        let conn = self.conn.lock().unwrap();
        let run = || -> rusqlite::Result<Option<(String, DeviceRecord, f64)>> {
            Self::prune(&conn, now)?;
            let active: i64 =
                conn.query_row("SELECT COUNT(*) FROM devices WHERE revoked = 0", [], |r| {
                    r.get(0)
                })?;
            if active >= MAX_DEVICES {
                return Ok(None);
            }
            let device_id = uuid::Uuid::new_v4().to_string();
            let token = core::generate_device_key();
            let record = DeviceRecord {
                device_id: device_id.clone(),
                label: label.into(),
                profile: profile.into(),
                permissions: core::DEVICE_PERMISSIONS
                    .iter()
                    .map(|p| p.to_string())
                    .collect(),
                already_revoked: false,
                terminal_allowed: false,
            };
            conn.execute(
                "INSERT INTO devices (token_hash, device_id, label, profile, permissions, revoked, issued_at, created_at) \
                 VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
                params![core::sha256_hex(&token), device_id, label, profile, record.permissions.join(","), now, now],
            )?;
            Ok(Some((token, record, now)))
        };
        run().map_err(unavailable("device mint failed"))
    }

    fn permissions(raw: String) -> Vec<String> {
        if raw.is_empty() {
            core::DEVICE_PERMISSIONS
                .iter()
                .map(|p| p.to_string())
                .collect()
        } else {
            raw.split(',').map(str::to_string).collect()
        }
    }

    pub fn lookup_device(&self, token: Option<&str>) -> Result<Option<DeviceRecord>> {
        let Some(token) = token.filter(|t| !t.is_empty()) else {
            return Ok(None);
        };
        let conn = self.conn.lock().unwrap();
        conn.query_row(
            "SELECT device_id, label, profile, permissions, terminal_allowed FROM devices \
             WHERE token_hash = ? AND revoked = 0",
            params![core::sha256_hex(token)],
            |r| {
                Ok(DeviceRecord {
                    device_id: r.get(0)?,
                    label: r.get(1)?,
                    profile: r.get(2)?,
                    permissions: Self::permissions(r.get(3)?),
                    already_revoked: false,
                    terminal_allowed: r.get::<_, i64>(4)? == 1,
                })
            },
        )
        .optional()
        .map_err(unavailable("device lookup failed"))
    }

    pub fn revoke_device(&self, token: Option<&str>) -> Result<Option<DeviceRecord>> {
        let Some(token) = token.filter(|t| !t.is_empty()) else {
            return Ok(None);
        };
        let hash = core::sha256_hex(token);
        let conn = self.conn.lock().unwrap();
        let run = || -> rusqlite::Result<Option<DeviceRecord>> {
            let row = conn
                .query_row(
                    "SELECT device_id, label, profile, permissions, revoked FROM devices WHERE token_hash = ?",
                    params![hash],
                    |r| {
                        Ok(DeviceRecord {
                            device_id: r.get(0)?,
                            label: r.get(1)?,
                            profile: r.get(2)?,
                            permissions: Self::permissions(r.get(3)?),
                            already_revoked: r.get::<_, i64>(4)? != 0,
                            terminal_allowed: false,
                        })
                    },
                )
                .optional()?;
            if row.is_some() {
                conn.execute(
                    "UPDATE devices SET revoked = 1 WHERE token_hash = ?",
                    params![hash],
                )?;
            }
            Ok(row)
        };
        run().map_err(unavailable("device revoke failed"))
    }

    pub fn set_terminal_access(&self, device_id: &str, allowed: bool) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE devices SET terminal_allowed = ? WHERE device_id = ? AND revoked = 0",
            params![allowed as i64, device_id],
        )
        .map(|changed| changed >= 1)
        .map_err(unavailable("terminal access update failed"))
    }

    pub fn revoke_device_by_id(&self, device_id: &str) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE devices SET revoked = 1 WHERE device_id = ?",
            params![device_id],
        )
        .map(|changed| changed >= 1)
        .map_err(unavailable("device revoke failed"))
    }

    pub fn list_devices(&self) -> Result<Vec<DeviceListing>> {
        let conn = self.conn.lock().unwrap();
        let run = || -> rusqlite::Result<Vec<DeviceListing>> {
            let mut statement = conn.prepare(
                "SELECT device_id, label, profile, revoked, terminal_allowed FROM devices ORDER BY created_at",
            )?;
            let rows = statement.query_map([], |r| {
                let revoked = r.get::<_, i64>(3)? != 0;
                Ok(DeviceListing {
                    device_id: r.get(0)?,
                    label: r.get(1)?,
                    profile: r.get(2)?,
                    revoked,
                    terminal_allowed: r.get::<_, i64>(4)? == 1 && !revoked,
                })
            })?;
            rows.collect()
        };
        run().map_err(unavailable("device list failed"))
    }

    /// Atomic 0600 snapshot; an existing destination is never overwritten.
    pub fn backup_to(&self, dest: &Path) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        if dest.exists() {
            return Err(StoreUnavailable(format!(
                "backup target already exists: {}",
                dest.display()
            )));
        }
        write_backup(&conn, dest).map_err(|_| StoreUnavailable("store backup failed".into()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn store() -> (tempfile_dir::Dir, Store) {
        let dir = tempfile_dir::Dir::new();
        let store = Store::open(&dir.path().join("state.db")).unwrap();
        (dir, store)
    }

    mod tempfile_dir {
        pub struct Dir(std::path::PathBuf);
        impl Dir {
            pub fn new() -> Self {
                let path = std::env::temp_dir()
                    .join(format!("omb-store-{}", uuid::Uuid::new_v4().simple()));
                std::fs::create_dir_all(&path).unwrap();
                Dir(path)
            }
            pub fn path(&self) -> &std::path::Path {
                &self.0
            }
        }
        impl Drop for Dir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
    }

    #[test]
    fn a_code_redeems_once() {
        let (_dir, store) = store();
        let (code, ttl) = store.issue_code().unwrap().unwrap();
        assert_eq!(ttl, 300);
        assert!(store.redeem_code(&json!(code)).unwrap());
        assert!(!store.redeem_code(&json!(code)).unwrap());
    }

    #[test]
    fn a_device_key_authenticates_until_revoked() {
        let (_dir, store) = store();
        let (token, record, _) = store
            .mint_device("paired-device", "default")
            .unwrap()
            .unwrap();
        assert_eq!(
            store
                .lookup_device(Some(&token))
                .unwrap()
                .unwrap()
                .device_id,
            record.device_id
        );
        let revoked = store.revoke_device(Some(&token)).unwrap().unwrap();
        assert!(!revoked.already_revoked);
        assert!(store.lookup_device(Some(&token)).unwrap().is_none());
        assert!(
            store
                .revoke_device(Some(&token))
                .unwrap()
                .unwrap()
                .already_revoked
        );
    }

    #[test]
    fn a_v1_store_is_backed_up_and_migrated() {
        let dir = tempfile_dir::Dir::new();
        let path = dir.path().join("state.db");
        {
            let conn = Connection::open(&path).unwrap();
            migrate_v0_to_v1(&conn).unwrap();
            conn.execute_batch("PRAGMA user_version = 1").unwrap();
            conn.execute(
                "INSERT INTO devices VALUES (?, 'd1', 'l', 'default', 'approvals,chat,runs', 0, 1, 1)",
                params![core::sha256_hex("hd_old")],
            )
            .unwrap();
        }
        let store = Store::open(&path).unwrap();
        assert!(dir.path().join("state.db.pre-v1.bak").exists());
        let record = store.lookup_device(Some("hd_old")).unwrap().unwrap();
        assert!(!record.terminal_allowed);
    }

    #[test]
    fn a_newer_schema_is_refused() {
        let dir = tempfile_dir::Dir::new();
        let path = dir.path().join("state.db");
        Connection::open(&path)
            .unwrap()
            .execute_batch("PRAGMA user_version = 99")
            .unwrap();
        assert!(Store::open(&path).is_err());
    }
}
