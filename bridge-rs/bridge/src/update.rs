//! `omb update`: install the latest release tag through the bootstrap
//! installer built into this binary (Python `update.py`). One explicit,
//! confirmed run; never retried.

use std::io::{IsTerminal, Write};
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use clap::Parser;

use crate::i18n::{self, Language};
use crate::settings::{self, SettingsFile, SetupError};

/// The bootstrap installer of exactly this release.
const BOOTSTRAP: &str = include_str!("../../../bridge/install-bridge.sh");
const REPO_URL: &str = "https://github.com/exclude-barrier/OrsikRelay.git";

#[derive(Parser)]
#[command(name = "omb update")]
struct Args {
    #[arg(long, alias = "lang", value_parser = ["de", "en"])]
    language: Option<String>,
    /// Explicitly confirm the update without prompting
    #[arg(long)]
    yes: bool,
    /// Do not change or start the systemd service
    #[arg(long = "no-service")]
    no_service: bool,
    /// Installation bridge.env file
    #[arg(long = "env-file")]
    env_file: Option<String>,
}

fn is_commit(value: &str) -> bool {
    value.len() == 40
        && value
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

fn git(args: &[&str], timeout_secs: u64) -> Result<String, SetupError> {
    let failed = || SetupError::Key("update_check_failed");
    let mut child = Command::new("git")
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| failed())?;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(timeout_secs);
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) if std::time::Instant::now() < deadline => {
                std::thread::sleep(std::time::Duration::from_millis(50))
            }
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(failed());
            }
        }
    }
    let output = child.wait_with_output().map_err(|_| failed())?;
    if !output.status.success() {
        return Err(failed());
    }
    String::from_utf8(output.stdout)
        .map(|s| s.trim().to_string())
        .map_err(|_| failed())
}

/// The newest tag on main and its commit.
fn latest_release(source: &Path) -> Result<(String, String), SetupError> {
    let source = source.to_string_lossy();
    git(
        &[
            "clone",
            "--quiet",
            "--filter=blob:none",
            "--no-checkout",
            REPO_URL,
            &source,
        ],
        60,
    )?;
    let tag = git(
        &[
            "-C",
            &source,
            "describe",
            "--tags",
            "--abbrev=0",
            "origin/main",
        ],
        15,
    )?;
    let commit = git(
        &["-C", &source, "rev-parse", &format!("{tag}^{{commit}}")],
        15,
    )?;
    if !is_commit(&commit) {
        return Err(SetupError::Key("update_check_failed"));
    }
    Ok((tag, commit))
}

/// The recorded commit of the installed release, if trustworthy.
fn installed_commit(home: &Path) -> Option<String> {
    let mut file = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(home.join("installed-commit"))
        .ok()?;
    let info = file.metadata().ok()?;
    if !info.is_file() || info.uid() != unsafe { libc::geteuid() } || info.mode() & 0o022 != 0 {
        return None;
    }
    let mut buffer = [0u8; 128];
    let n = std::io::Read::read(&mut file, &mut buffer).ok()?;
    let value = std::str::from_utf8(&buffer[..n]).ok()?.trim().to_string();
    is_commit(&value).then_some(value)
}

fn record_commit(home: &Path, commit: &str) -> std::io::Result<()> {
    let temporary = home.join(format!(
        ".installed-commit.{}",
        uuid::Uuid::new_v4().simple()
    ));
    let result = (|| {
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)?;
        file.write_all(format!("{commit}\n").as_bytes())?;
        file.sync_all()?;
        std::fs::rename(&temporary, home.join("installed-commit"))
    })();
    let _ = std::fs::remove_file(&temporary);
    result
}

struct TempDir(PathBuf);

impl TempDir {
    fn new() -> std::io::Result<Self> {
        let path =
            std::env::temp_dir().join(format!("omb-update-{}", uuid::Uuid::new_v4().simple()));
        std::fs::DirBuilder::new().mode(0o700).create(&path)?;
        Ok(TempDir(path))
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn run(args: &Args, language: Language) -> Result<i32, SetupError> {
    let t = |key: &str| i18n::text(language, key).to_string();
    if !args.yes && (!std::io::stdin().is_terminal() || !std::io::stdout().is_terminal()) {
        return Err(SetupError::Key("update_tty"));
    }
    let environ = std::env::vars().collect();
    let settings = SettingsFile::open(settings::config_path(args.env_file.as_deref()), environ)?;
    let home_value = settings
        .values
        .get("BRIDGE_HOME")
        .filter(|h| !h.is_empty())
        .cloned()
        .unwrap_or_else(|| "~/.local/orsik-mobile-bridge".into());
    let home = settings::expand_user(&home_value);
    let home = if home.is_absolute() {
        home
    } else {
        std::env::current_dir().unwrap_or_default().join(home)
    };
    if settings.path != home.join("bridge.env") {
        return Err(SetupError::Key("update_external_config"));
    }
    let mut command = Command::new("bash");
    command.env("BRIDGE_HOME", &home);
    if let Some(hermes_env) = settings
        .values
        .get("BRIDGE_HERMES_ENV")
        .filter(|v| !v.is_empty())
    {
        let key_file = settings::expand_user(hermes_env);
        if key_file.file_name().is_none_or(|n| n != ".env") {
            return Err(SetupError::Key("update_hermes_config"));
        }
        command.env("HERMES_HOME", key_file.parent().unwrap_or(Path::new("/")));
    }
    let temporary = TempDir::new().map_err(|_| SetupError::Key("update_execution_failed"))?;
    let source = temporary.0.join("source");
    let (tag, commit) = latest_release(&source)?;
    let installed = installed_commit(&home);
    if installed.as_deref() == Some(commit.as_str()) {
        println!("{} {tag}", t("update_current"));
        return Ok(0);
    }
    if installed.is_none() {
        println!("{}", t("update_unknown"));
    }
    println!("{} {tag}", t("update_confirm_title"));
    println!("{}", t("update_confirm_store"));
    println!("{}", t("update_confirm_service"));
    if !args.yes {
        print!("{}", t("update_prompt"));
        let _ = std::io::stdout().flush();
        let mut answer = String::new();
        if std::io::stdin()
            .read_line(&mut answer)
            .map_err(|_| SetupError::Key("update_cancelled"))?
            == 0
        {
            println!("{}", t("update_cancelled"));
            return Ok(0);
        }
        let yes = if language == Language::De { "j" } else { "y" };
        if answer.trim().to_lowercase() != yes {
            println!("{}", t("update_cancelled"));
            return Ok(0);
        }
    }
    let script = temporary.0.join("install-bridge.sh");
    std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&script)
        .and_then(|mut f| f.write_all(BOOTSTRAP.as_bytes()))
        .map_err(|_| SetupError::Key("update_execution_failed"))?;
    command
        .arg(&script)
        .env("ORSIK_DIR", &source)
        .env("ORSIK_REF", &commit);
    if args.no_service {
        command.arg("--no-service");
    }
    let status = command
        .status()
        .map_err(|_| SetupError::Key("update_execution_failed"))?;
    if !status.success() {
        println!("{}", t("update_failed"));
        return Ok(status.code().unwrap_or(1));
    }
    if record_commit(&home, &commit).is_err() {
        println!("{}", t("update_record_failed"));
    }
    Ok(0)
}

pub fn main(argv: Vec<String>) -> i32 {
    let args = match Args::try_parse_from(std::iter::once("omb update".to_string()).chain(argv)) {
        Ok(args) => args,
        Err(error) => {
            let _ = error.print();
            return error.exit_code();
        }
    };
    let language = settings::command_language(args.language.as_deref(), args.env_file.as_deref());
    match run(&args, language) {
        Ok(code) => code,
        Err(error) => {
            println!("{}", settings::error_text(&error, language));
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_bootstrap_is_built_in() {
        assert!(BOOTSTRAP.starts_with("#!/usr/bin/env bash"));
        assert!(is_commit(&"a".repeat(40)));
        assert!(!is_commit(&"A".repeat(40)));
    }
}
