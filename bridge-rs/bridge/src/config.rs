//! Environment, env files and the start-up refusals. Mirrors the Python
//! `server.py` / `hostmetrics.py` / `files.py` / `terminal.py` checks: any
//! invalid setting stops the bridge with a message on stderr and exit 1, and
//! a value that may carry a secret is never echoed.

use std::collections::HashMap;
use std::net::IpAddr;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};

pub const DEFAULT_SERVICES: [&str; 2] = ["hermes-gateway", "orsik-mobile-bridge"];
pub const MAX_SERVICES: usize = 8;

/// A start-up refusal; `main` prints it and exits 1.
#[derive(Debug)]
pub struct Refusal(pub String);

impl Refusal {
    fn new(message: impl Into<String>) -> Self {
        Refusal(message.into())
    }
}

pub type Env = HashMap<String, String>;

/// The process environment plus `BRIDGE_ENV_FILE` on top.
pub fn load_env() -> Result<Env, Refusal> {
    let mut env: Env = std::env::vars().collect();
    if let Some(file) = env
        .get("BRIDGE_ENV_FILE")
        .filter(|f| !f.is_empty())
        .cloned()
    {
        env.extend(load_env_file(&file)?);
    }
    Ok(env)
}

fn secret_file_error(path: &str) -> Refusal {
    Refusal::new(format!(
        "Bridge refuses secret file {path}: it must be a regular file owned by the current user \
         with permissions 0600 (run: chmod 600 {path})"
    ))
}

/// Fail closed before reading a file that can carry the master key.
pub fn check_secret_file(path: &str) -> Result<(), Refusal> {
    let meta = std::fs::metadata(path).map_err(|_| secret_file_error(path))?;
    let euid = unsafe { libc::geteuid() };
    if !meta.file_type().is_file() || meta.uid() != euid || meta.mode() & 0o077 != 0 {
        return Err(secret_file_error(path));
    }
    Ok(())
}

pub fn load_env_file(path: &str) -> Result<Env, Refusal> {
    check_secret_file(path)?;
    let text = std::fs::read(path)
        .ok()
        .and_then(|bytes| String::from_utf8(bytes).ok())
        .ok_or_else(|| Refusal::new(format!("Could not read env file: {path}")))?;
    Ok(parse_env_text(&text))
}

/// dotenv-style value: matching outer quotes are removed (content verbatim);
/// an unquoted value ends at a " #" comment.
fn env_value(raw: &str) -> String {
    let val = raw.trim();
    let bytes: Vec<char> = val.chars().collect();
    if bytes.len() >= 2
        && bytes[0] == bytes[bytes.len() - 1]
        && (bytes[0] == '"' || bytes[0] == '\'')
    {
        return bytes[1..bytes.len() - 1].iter().collect();
    }
    match val.find(" #") {
        Some(index) => val[..index].trim_end().to_string(),
        None => val.to_string(),
    }
}

pub fn parse_env_text(text: &str) -> Env {
    let mut values = Env::new();
    for line in text.lines() {
        let mut line = line.trim();
        if let Some(rest) = line.strip_prefix("export ") {
            line = rest.trim_start();
        }
        if line.is_empty() || line.starts_with('#') || !line.contains('=') {
            continue;
        }
        let (key, value) = line.split_once('=').unwrap();
        values.insert(key.trim().to_string(), env_value(value));
    }
    values
}

fn home_dir() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("/"))
}

pub fn bridge_home(env: &Env) -> PathBuf {
    match env.get("BRIDGE_HOME").filter(|v| !v.is_empty()) {
        Some(home) => PathBuf::from(home),
        None => home_dir().join(".local").join("orsik-mobile-bridge"),
    }
}

pub fn state_db_path(env: &Env) -> PathBuf {
    match env.get("BRIDGE_STATE_DB").filter(|v| !v.is_empty()) {
        Some(path) => PathBuf::from(path),
        None => bridge_home(env).join("state.db"),
    }
}

const UPSTREAM_ERROR: &str = "BRIDGE_UPSTREAM must be loopback HTTP, e.g. http://127.0.0.1:8642";
const BIND_ERROR: &str = "BRIDGE_BIND must be a literal loopback IP address (127.0.0.1 or ::1)";

pub fn validate_port(raw: &str) -> Result<u16, Refusal> {
    match raw.trim().parse::<u32>() {
        Ok(port) if (1..=65535).contains(&port) => Ok(port as u16),
        _ => Err(Refusal::new(
            "BRIDGE_PORT must be a number between 1 and 65535",
        )),
    }
}

/// Python `ipaddress` loopback: 127.0.0.0/8, ::1 and their IPv4-mapped form.
pub fn is_loopback(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback(),
        IpAddr::V6(v6) => {
            v6.is_loopback() || v6.to_ipv4_mapped().is_some_and(|v4| v4.is_loopback())
        }
    }
}

/// Only literal loopback IPs; a name like `localhost` is refused.
pub fn validate_bind(bind: &str) -> Result<IpAddr, Refusal> {
    match bind.parse::<IpAddr>() {
        Ok(ip) if is_loopback(ip) => Ok(ip),
        _ => Err(Refusal::new(BIND_ERROR)),
    }
}

/// A plaintext-HTTP loopback IP literal with an explicit port and nothing
/// else, so the master key can never leave the host. Returns the base URL
/// without a trailing slash.
pub fn validate_upstream(upstream: &str) -> Result<String, Refusal> {
    let refuse = || Refusal::new(UPSTREAM_ERROR);
    let rest = upstream.strip_prefix("http://").ok_or_else(refuse)?;
    // Authority only: no path, query, fragment or user info.
    if rest.contains(['/', '?', '#', '@']) {
        return Err(refuse());
    }
    let (host, port) = if let Some(v6) = rest.strip_prefix('[') {
        let (host, after) = v6.split_once(']').ok_or_else(refuse)?;
        (host, after.strip_prefix(':').ok_or_else(refuse)?)
    } else {
        rest.rsplit_once(':').ok_or_else(refuse)?
    };
    let ip: IpAddr = host.parse().map_err(|_| refuse())?;
    if !is_loopback(ip) || port.is_empty() || !port.chars().all(|c| c.is_ascii_digit()) {
        return Err(refuse());
    }
    match port.parse::<u32>() {
        Ok(p) if p <= 65535 => Ok(upstream.trim_end_matches('/').to_string()),
        _ => Err(refuse()),
    }
}

/// The Hermes API key: `API_SERVER_KEY` from `BRIDGE_HERMES_ENV`, else `HERMES_API_KEY`.
pub fn master_key(env: &Env) -> Result<String, Refusal> {
    let mut key = String::new();
    if let Some(file) = env.get("BRIDGE_HERMES_ENV").filter(|f| !f.is_empty()) {
        key = load_env_file(file)?
            .get("API_SERVER_KEY")
            .cloned()
            .unwrap_or_default();
    }
    if key.is_empty() {
        key = env.get("HERMES_API_KEY").cloned().unwrap_or_default();
    }
    if key.is_empty() {
        return Err(Refusal::new(
            "Bridge needs the Hermes API key (BRIDGE_HERMES_ENV or HERMES_API_KEY)",
        ));
    }
    Ok(key)
}

fn service_name_ok(name: &str) -> bool {
    let mut chars = name.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    first.is_ascii_alphanumeric()
        && name.len() <= 64
        && chars.all(|c| c.is_ascii_alphanumeric() || "@_.-".contains(c))
}

/// The operator's service list for the Home card.
pub fn parse_services(raw: Option<&String>) -> Result<Vec<String>, Refusal> {
    let Some(raw) = raw.filter(|r| !r.trim().is_empty()) else {
        return Ok(DEFAULT_SERVICES.iter().map(|s| s.to_string()).collect());
    };
    let names: Vec<String> = raw
        .split(',')
        .map(str::trim)
        .filter(|p| !p.is_empty())
        .map(|p| p.strip_suffix(".service").unwrap_or(p).to_string())
        .collect();
    if names.is_empty() || names.len() > MAX_SERVICES || !names.iter().all(|n| service_name_ok(n)) {
        return Err(Refusal::new(
            "BRIDGE_HOST_SERVICES: at most 8 plain unit names (letters, digits, @ _ . -)",
        ));
    }
    Ok(names)
}

/// `1` on, `0`/empty off, anything else refused.
pub fn parse_switch(raw: Option<&String>, error: &str) -> Result<bool, Refusal> {
    match raw.map(|v| v.trim()).unwrap_or("") {
        "" | "0" => Ok(false),
        "1" => Ok(true),
        _ => Err(Refusal::new(error)),
    }
}

/// `realpath` that also works for a path whose tail does not exist yet.
fn real_or_lexical(path: &Path) -> PathBuf {
    std::fs::canonicalize(path).unwrap_or_else(|_| absolute(path))
}

fn absolute(path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("/"))
            .join(path)
    }
}

fn inside(root: &Path, path: &Path) -> bool {
    path == root || path.starts_with(root)
}

/// The real path of `BRIDGE_FILES_ROOT` when files are on, else None.
pub fn files_root(env: &Env) -> Result<Option<PathBuf>, Refusal> {
    if !parse_switch(
        env.get("BRIDGE_FILES"),
        "BRIDGE_FILES must be 1 (on), 0 or empty (off)",
    )? {
        return Ok(None);
    }
    let root_error =
        || Refusal::new("BRIDGE_FILES=1 needs BRIDGE_FILES_ROOT, an existing absolute directory");
    let raw = env.get("BRIDGE_FILES_ROOT").map(|v| v.trim()).unwrap_or("");
    let raw_path = Path::new(raw);
    if raw.is_empty() || !raw_path.is_absolute() || !raw_path.is_dir() {
        return Err(root_error());
    }
    let root = std::fs::canonicalize(raw_path).map_err(|_| root_error())?;
    if root == Path::new("/") {
        return Err(Refusal::new("BRIDGE_FILES_ROOT must not be /"));
    }
    let home = home_dir();
    let state_db = state_db_path(env);
    let bridge_home = bridge_home(env);
    let hermes_home = match env.get("HERMES_HOME").filter(|v| !v.is_empty()) {
        Some(h) => PathBuf::from(h),
        None => home.join(".hermes"),
    };
    let state_dir = absolute(&state_db)
        .parent()
        .map(Path::to_path_buf)
        .unwrap_or_default();
    let homes = [bridge_home, state_dir, hermes_home];
    let mut protected: Vec<PathBuf> = vec![home];
    protected.extend(homes.iter().cloned());
    protected.push(state_db);
    if let Some(env_file) = env.get("BRIDGE_ENV_FILE").filter(|v| !v.is_empty()) {
        protected.push(PathBuf::from(env_file));
    }
    if let Some(hermes_env) = env.get("BRIDGE_HERMES_ENV").filter(|v| !v.is_empty()) {
        let hermes_env = PathBuf::from(hermes_env);
        if let Some(parent) = absolute(&hermes_env).parent() {
            protected.push(parent.to_path_buf());
        }
        if let Some(parent) = real_or_lexical(&hermes_env).parent() {
            protected.push(parent.to_path_buf());
        }
        protected.push(hermes_env);
    }
    for path in &protected {
        if inside(&root, &real_or_lexical(path)) {
            return Err(Refusal::new(
                "BRIDGE_FILES_ROOT must not contain the home folder, the bridge's own data or the Hermes home",
            ));
        }
    }
    for path in &homes {
        if inside(&real_or_lexical(path), &root) {
            return Err(Refusal::new(
                "BRIDGE_FILES_ROOT must not lie inside the bridge or the Hermes home",
            ));
        }
    }
    Ok(Some(root))
}

/// The fixed tmux attach argv when the terminal is on, else None.
pub fn terminal_command(env: &Env) -> Result<Option<Vec<String>>, Refusal> {
    if !parse_switch(
        env.get("BRIDGE_TERMINAL"),
        "BRIDGE_TERMINAL must be 1 (on), 0 or empty (off)",
    )? {
        return Ok(None);
    }
    let path = env.get("PATH").cloned().unwrap_or_default();
    let tmux = which("tmux", &path);
    let setsid = which("setsid", &path);
    match (setsid, tmux) {
        (Some(setsid), Some(tmux)) => Ok(Some(vec![
            setsid.display().to_string(),
            "--ctty".into(),
            tmux.display().to_string(),
            "new-session".into(),
            "-A".into(),
            "-s".into(),
            "orsik".into(),
        ])),
        _ => Err(Refusal::new(
            "BRIDGE_TERMINAL=1 needs tmux and setsid (util-linux) on the PATH",
        )),
    }
}

/// `shutil.which`: the first executable regular file named `name` on `path`.
pub fn which(name: &str, path: &str) -> Option<PathBuf> {
    use std::os::unix::fs::PermissionsExt;
    path.split(':')
        .filter(|d| !d.is_empty())
        .map(|dir| Path::new(dir).join(name))
        .find(|candidate| {
            std::fs::metadata(candidate)
                .map(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
                .unwrap_or(false)
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_text_follows_the_dotenv_rules() {
        let env =
            parse_env_text("# c\nexport A=\"x y\"\nB='z' \nC=plain # comment\nD = 1\nnoequals\n");
        assert_eq!(env["A"], "x y");
        assert_eq!(env["B"], "z");
        assert_eq!(env["C"], "plain");
        assert_eq!(env["D"], "1");
        assert!(!env.contains_key("noequals"));
    }

    #[test]
    fn the_upstream_must_be_loopback_http_with_a_port() {
        assert!(validate_upstream("http://127.0.0.1:8642").is_ok());
        assert!(validate_upstream("http://[::1]:8642").is_ok());
        assert!(validate_upstream("http://127.0.0.1:80").is_ok());
        for bad in [
            "http://127.0.0.1",
            "https://127.0.0.1:8642",
            "http://localhost:8642",
            "http://a:b@127.0.0.1:8642",
            "http://127.0.0.1:8642/x",
            "http://127.0.0.1:8642?x",
            "http://10.0.0.1:8642",
            "http://127.0.0.1:99999",
        ] {
            assert!(validate_upstream(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn the_bind_must_be_a_loopback_literal() {
        assert!(validate_bind("127.0.0.1").is_ok());
        assert!(validate_bind("::1").is_ok());
        assert!(validate_bind("0000::1").is_ok());
        assert!(validate_bind("::ffff:127.0.0.1").is_ok());
        assert!(validate_bind("::ffff:8.8.8.8").is_err());
        assert!(validate_bind("localhost").is_err());
        assert!(validate_bind("0.0.0.0").is_err());
    }

    #[test]
    fn services_are_plain_unit_names() {
        assert_eq!(parse_services(None).unwrap(), DEFAULT_SERVICES);
        assert_eq!(
            parse_services(Some(&"a.service, b@1".to_string())).unwrap(),
            ["a", "b@1"]
        );
        assert!(parse_services(Some(&"a;b".to_string())).is_err());
        assert!(parse_services(Some(&"-a".to_string())).is_err());
        assert!(parse_services(Some(&"a,b,c,d,e,f,g,h,i".to_string())).is_err());
    }
}
