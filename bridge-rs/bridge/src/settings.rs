//! The private bridge.env behind `omb setup` and the draft session on top of
//! it (Python `setup.py`). Only four keys can be changed; every other line,
//! secrets included, is written back byte for byte. Device grants are
//! immediate decisions on the store, never drafts.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::time::Duration;

use crate::config::{self, Env, Refusal};
use crate::i18n::{self, Language};
use crate::store::{DeviceListing, Store, StoreUnavailable};

pub const SETTINGS: [&str; 4] = [
    "BRIDGE_TERMINAL",
    "BRIDGE_FILES",
    "BRIDGE_FILES_ROOT",
    "OMB_LANGUAGE",
];
const MAX_CONFIG: u64 = 1024 * 1024;

/// A failure shown to the operator; `key` names an i18n text.
#[derive(Debug)]
pub enum SetupError {
    Key(&'static str),
    Refused(Refusal),
    Store,
}

impl From<StoreUnavailable> for SetupError {
    fn from(_: StoreUnavailable) -> Self {
        SetupError::Store
    }
}

impl From<Refusal> for SetupError {
    fn from(refusal: Refusal) -> Self {
        SetupError::Refused(refusal)
    }
}

/// The localized message (Python `error_text`).
pub fn error_text(error: &SetupError, language: Language) -> String {
    let key = match error {
        SetupError::Key(key) if i18n::has(key) => key,
        SetupError::Key(_) => "validation_error",
        SetupError::Store => "store_error",
        SetupError::Refused(Refusal(message)) => match message.as_str() {
            "BRIDGE_TERMINAL must be 1 (on), 0 or empty (off)" => "invalid_terminal",
            "BRIDGE_FILES must be 1 (on), 0 or empty (off)" => "invalid_files",
            "BRIDGE_FILES=1 needs BRIDGE_FILES_ROOT, an existing absolute directory" => {
                "invalid_folder"
            }
            "BRIDGE_TERMINAL=1 needs tmux and setsid (util-linux) on the PATH" => "terminal_tools",
            "BRIDGE_FILES_ROOT must not be /"
            | "BRIDGE_FILES_ROOT must not contain the home folder, the bridge's own data or the Hermes home"
            | "BRIDGE_FILES_ROOT must not lie inside the bridge or the Hermes home" => {
                "protected_folder"
            }
            _ => "validation_error",
        },
    };
    i18n::text(language, key).to_string()
}

fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"))
}

pub fn expand_user(value: &str) -> PathBuf {
    match value.strip_prefix('~') {
        Some(rest) if rest.is_empty() || rest.starts_with('/') => {
            home().join(rest.trim_start_matches('/'))
        }
        _ => PathBuf::from(value),
    }
}

fn absolute(path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        path
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("/"))
            .join(path)
    }
}

/// `bridge.env` next to `bin/` of this installed binary.
fn adjacent_config() -> Option<PathBuf> {
    let exe = std::fs::canonicalize(std::env::current_exe().ok()?).ok()?;
    Some(exe.parent()?.parent()?.join("bridge.env"))
}

/// Explicit path, else BRIDGE_ENV_FILE, else this install's or the default home.
pub fn config_path(explicit: Option<&str>) -> PathBuf {
    if let Some(path) = explicit.filter(|p| !p.is_empty()) {
        return absolute(expand_user(path));
    }
    if let Ok(path) = std::env::var("BRIDGE_ENV_FILE")
        && !path.is_empty()
    {
        return absolute(expand_user(&path));
    }
    let bridge_home = std::env::var("BRIDGE_HOME").ok().filter(|h| !h.is_empty());
    if bridge_home.is_none()
        && let Some(adjacent) = adjacent_config().filter(|p| p.is_file())
    {
        return adjacent;
    }
    absolute(
        expand_user(&bridge_home.unwrap_or_else(|| "~/.local/orsik-mobile-bridge".into()))
            .join("bridge.env"),
    )
}

fn line_key(line: &str) -> String {
    let mut line = line.trim();
    if let Some(rest) = line.strip_prefix("export ") {
        line = rest.trim_start();
    }
    if line.is_empty() || line.starts_with('#') || !line.contains('=') {
        return String::new();
    }
    line.split_once('=')
        .map(|(k, _)| k.trim().to_string())
        .unwrap_or_default()
}

/// Python `str.isprintable` for the characters a setting may hold.
fn printable(c: char) -> bool {
    c == ' ' || !(c.is_control() || c.is_whitespace())
}

pub struct SettingsFile {
    pub path: PathBuf,
    original: Vec<u8>,
    pub values: Env,
}

impl SettingsFile {
    pub fn open(path: PathBuf, environ: Env) -> Result<Self, SetupError> {
        let original = Self::read(&path)?;
        let mut values = environ;
        values.extend(config::parse_env_text(
            std::str::from_utf8(&original).unwrap_or(""),
        ));
        values.insert("BRIDGE_ENV_FILE".into(), path.display().to_string());
        if Some(&path) == adjacent_config().as_ref()
            && values.get("BRIDGE_HOME").is_none_or(|h| h.is_empty())
        {
            values.insert(
                "BRIDGE_HOME".into(),
                path.parent()
                    .unwrap_or(Path::new("/"))
                    .display()
                    .to_string(),
            );
        }
        // The user unit has no WorkingDirectory; relative paths use HOME.
        for key in ["BRIDGE_STATE_DB", "BRIDGE_HERMES_ENV", "HERMES_HOME"] {
            if let Some(value) = values.get(key).filter(|v| !v.is_empty()) {
                let candidate = expand_user(value);
                let resolved = if candidate.is_absolute() {
                    candidate
                } else {
                    home().join(candidate)
                };
                values.insert(key.into(), resolved.display().to_string());
            }
        }
        Ok(SettingsFile {
            path,
            original,
            values,
        })
    }

    fn read(path: &Path) -> Result<Vec<u8>, SetupError> {
        let mut file = std::fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
            .open(path)
            .map_err(|_| SetupError::Key("config_read"))?;
        let info = file
            .metadata()
            .map_err(|_| SetupError::Key("config_read"))?;
        if !info.is_file() || info.uid() != unsafe { libc::geteuid() } || info.mode() & 0o077 != 0 {
            return Err(SetupError::Key("config_private"));
        }
        let mut data = Vec::new();
        (&mut file)
            .take(MAX_CONFIG + 1)
            .read_to_end(&mut data)
            .map_err(|_| SetupError::Key("config_read"))?;
        if data.len() as u64 > MAX_CONFIG {
            return Err(SetupError::Key("config_large"));
        }
        std::str::from_utf8(&data).map_err(|_| SetupError::Key("config_read"))?;
        Ok(data)
    }

    /// Rewrites only the changed keys (quoted) through a temp file and a
    /// rename; refuses when the file changed since it was read.
    pub fn save(&mut self, changes: &[(String, String)]) -> Result<(), SetupError> {
        if changes.is_empty() {
            return Ok(());
        }
        for (key, value) in changes {
            if !SETTINGS.contains(&key.as_str())
                || (key == "OMB_LANGUAGE" && Language::parse(value).is_none())
                || value.chars().any(|c| !printable(c) || "\"'\\".contains(c))
            {
                return Err(SetupError::Key("config_value"));
            }
        }
        let text = String::from_utf8_lossy(&self.original).into_owned();
        let mut remaining: Vec<&(String, String)> = changes.iter().collect();
        let mut out = String::new();
        for line in text.split_inclusive('\n') {
            let key = line_key(line);
            if let Some((k, v)) = changes.iter().find(|(k, _)| *k == key) {
                out.push_str(&format!("{k}=\"{v}\"\n"));
                remaining.retain(|(rk, _)| rk != k);
            } else {
                out.push_str(line);
            }
        }
        if !remaining.is_empty() && !out.is_empty() && !out.ends_with(['\n', '\r']) {
            out.push('\n');
        }
        remaining.sort_by(|a, b| a.0.cmp(&b.0));
        for (k, v) in remaining {
            out.push_str(&format!("{k}=\"{v}\"\n"));
        }
        let dir = self.path.parent().unwrap_or(Path::new("."));
        let temporary = dir.join(format!(".omb-{}", uuid::Uuid::new_v4().simple()));
        let result = (|| {
            let mut file = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&temporary)
                .map_err(|_| SetupError::Key("config_write"))?;
            file.write_all(out.as_bytes())
                .and_then(|_| file.sync_all())
                .map_err(|_| SetupError::Key("config_write"))?;
            if Self::read(&self.path)? != self.original {
                return Err(SetupError::Key("config_changed"));
            }
            std::fs::rename(&temporary, &self.path).map_err(|_| SetupError::Key("config_write"))
        })();
        let _ = std::fs::remove_file(&temporary);
        result?;
        self.original = out.into_bytes();
        Ok(())
    }
}

fn switch_key(key: &str) -> &'static str {
    if key == "BRIDGE_TERMINAL" {
        "BRIDGE_TERMINAL must be 1 (on), 0 or empty (off)"
    } else {
        "BRIDGE_FILES must be 1 (on), 0 or empty (off)"
    }
}

/// Draft global settings; device decisions stay immediate.
pub struct SetupSession {
    pub settings: SettingsFile,
    pub language: Language,
    /// Pending changes in the order they were made.
    pub pending: Vec<(String, String)>,
    pub restart_required: bool,
}

impl SetupSession {
    pub fn new(settings: SettingsFile, language: Language) -> Self {
        SetupSession {
            settings,
            language,
            pending: Vec::new(),
            restart_required: false,
        }
    }

    pub fn t(&self, key: &str) -> String {
        i18n::text(self.language, key).to_string()
    }

    pub fn values(&self) -> Env {
        let mut values = self.settings.values.clone();
        values.extend(self.pending.iter().cloned());
        values
    }

    pub fn value(&self, key: &str) -> String {
        self.values().get(key).cloned().unwrap_or_default()
    }

    pub fn set_value(&mut self, key: &str, value: &str) -> Result<(), SetupError> {
        if !SETTINGS.contains(&key) {
            return Err(SetupError::Key("config_value"));
        }
        let mut original = self.settings.values.get(key).cloned().unwrap_or_default();
        if key == "BRIDGE_TERMINAL" || key == "BRIDGE_FILES" {
            original = if original.trim() == "1" {
                "1".into()
            } else {
                "0".into()
            };
        }
        self.pending.retain(|(k, _)| k != key);
        if value != original {
            self.pending.push((key.into(), value.into()));
        }
        Ok(())
    }

    pub fn set_language(&mut self, language: Language) -> Result<(), SetupError> {
        self.language = language;
        self.set_value("OMB_LANGUAGE", language.code())
    }

    pub fn enabled(&self, key: &str) -> Result<bool, SetupError> {
        let values = self.values();
        Ok(config::parse_switch(values.get(key), switch_key(key))?)
    }

    pub fn toggle(&mut self, key: &str) -> Result<(), SetupError> {
        let next = if self.enabled(key)? { "0" } else { "1" };
        self.set_value(key, next)
    }

    pub fn save(&mut self) -> Result<(), SetupError> {
        if self.pending.is_empty() {
            return Ok(());
        }
        let service_change = self.pending.iter().any(|(k, _)| k != "OMB_LANGUAGE");
        if service_change {
            let env = self.values();
            config::terminal_command(&env)?;
            config::files_root(&env)?;
        }
        let pending = std::mem::take(&mut self.pending);
        if let Err(error) = self.settings.save(&pending) {
            self.pending = pending;
            return Err(error);
        }
        self.settings.values.extend(pending);
        self.restart_required |= service_change;
        Ok(())
    }

    pub fn restart(&mut self) -> Result<(), SetupError> {
        if !self.pending.is_empty() || !self.restart_required {
            return Err(SetupError::Key("restart_save_first"));
        }
        let child = std::process::Command::new("systemctl")
            .args(["--user", "restart", "orsik-mobile-bridge.service"])
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn();
        let Ok(mut child) = child else {
            return Err(SetupError::Key("restart_failed"));
        };
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        let status = loop {
            match child.try_wait() {
                Ok(Some(status)) => break Some(status),
                Ok(None) if std::time::Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(50))
                }
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                    break None;
                }
            }
        };
        if !status.is_some_and(|s| s.success()) {
            return Err(SetupError::Key("restart_failed"));
        }
        self.restart_required = false;
        Ok(())
    }

    fn store_path(&self) -> PathBuf {
        let path = config::state_db_path(&self.settings.values);
        if path.is_absolute() {
            path
        } else {
            home().join(path)
        }
    }

    pub fn devices(&self) -> Result<Vec<DeviceListing>, SetupError> {
        let path = self.store_path();
        if !path.is_file() {
            return Ok(Vec::new());
        }
        Ok(Store::open(&path)?
            .list_devices()?
            .into_iter()
            .filter(|d| !d.revoked)
            .collect())
    }

    pub fn change_terminal(&self, device_id: &str, allowed: bool) -> Result<bool, SetupError> {
        let path = self.store_path();
        if !path.is_file() {
            return Err(SetupError::Key("no_devices"));
        }
        Ok(Store::open(&path)?.set_terminal_access(device_id, allowed)?)
    }
}

/// `--language` wins, then the saved OMB_LANGUAGE, else English.
pub fn command_language(explicit: Option<&str>, env_file: Option<&str>) -> Language {
    if let Some(language) = explicit.and_then(Language::parse) {
        return language;
    }
    let environ: HashMap<String, String> = std::env::vars().collect();
    match SettingsFile::open(config_path(env_file), environ) {
        Ok(settings) => i18n::detect(settings.values.get("OMB_LANGUAGE").map(String::as_str)),
        Err(_) => Language::En,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn private_file(text: &str) -> (tempfile_dir::Dir, PathBuf) {
        let dir = tempfile_dir::Dir::new();
        let path = dir.0.join("bridge.env");
        std::fs::write(&path, text).unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        (dir, path)
    }

    mod tempfile_dir {
        pub struct Dir(pub std::path::PathBuf);
        impl Dir {
            pub fn new() -> Self {
                let path = std::env::temp_dir()
                    .join(format!("omb-settings-{}", uuid::Uuid::new_v4().simple()));
                std::fs::create_dir_all(&path).unwrap();
                Dir(path)
            }
        }
        impl Drop for Dir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
    }

    #[test]
    fn only_changed_keys_are_rewritten() {
        let (_dir, path) = private_file("# keep\nexport BRIDGE_TERMINAL=0\nSECRET='x y'\n");
        let mut settings = SettingsFile::open(path.clone(), Env::new()).unwrap();
        settings
            .save(&[
                ("BRIDGE_TERMINAL".into(), "1".into()),
                ("OMB_LANGUAGE".into(), "de".into()),
            ])
            .unwrap();
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            "# keep\nBRIDGE_TERMINAL=\"1\"\nSECRET='x y'\nOMB_LANGUAGE=\"de\"\n"
        );
        assert!(matches!(
            settings.save(&[("SECRET".into(), "1".into())]),
            Err(SetupError::Key("config_value"))
        ));
        assert!(matches!(
            settings.save(&[("BRIDGE_FILES_ROOT".into(), "/a\"b".into())]),
            Err(SetupError::Key("config_value"))
        ));
    }

    #[test]
    fn a_shared_or_changed_file_is_refused() {
        let (_dir, path) = private_file("A=1\n");
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        assert!(matches!(
            SettingsFile::open(path.clone(), Env::new()),
            Err(SetupError::Key("config_private"))
        ));
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        let mut settings = SettingsFile::open(path.clone(), Env::new()).unwrap();
        std::fs::write(&path, "A=2\n").unwrap();
        assert!(matches!(
            settings.save(&[("BRIDGE_FILES".into(), "1".into())]),
            Err(SetupError::Key("config_changed"))
        ));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "A=2\n");
    }

    #[test]
    fn a_draft_back_to_the_original_is_no_change() {
        let (_dir, path) = private_file("BRIDGE_TERMINAL=\" 1 \"\n");
        let mut session =
            SetupSession::new(SettingsFile::open(path, Env::new()).unwrap(), Language::En);
        session.toggle("BRIDGE_TERMINAL").unwrap();
        assert_eq!(
            session.pending,
            [("BRIDGE_TERMINAL".to_string(), "0".to_string())]
        );
        session.toggle("BRIDGE_TERMINAL").unwrap();
        assert!(session.pending.is_empty(), "back to the saved \" 1 \"");
    }
}
