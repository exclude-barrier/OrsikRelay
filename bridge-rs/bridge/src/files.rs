//! Read-only file browser and project list under one operator-chosen folder
//! (Python `files.py`). Every request path is relative to the root and must
//! stay inside it after symlinks are resolved; files are opened one component
//! at a time with O_NOFOLLOW. Nothing is written. All functions block and run
//! on a worker thread.

use std::ffi::{CString, OsStr};
use std::io::Read;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::os::unix::process::CommandExt;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use axum::body::Body;
use axum::extract::Request;
use axum::http::header;
use axum::response::Response;
use serde_json::{Value, json};

use crate::server::{BodyError, Handled, State, audit, closed, lookup, read_body};

pub const MAX_ENTRIES: usize = 2000;
pub const MAX_FILE_BYTES: u64 = 8 * 1024 * 1024;
const MAX_PATH_CHARS: usize = 4096;
const MAX_PROJECTS: usize = 50;
const PROJECT_DEPTH: usize = 2;
const GIT_TIMEOUT: Duration = Duration::from_secs(2);
const SCAN_DEADLINE: Duration = Duration::from_secs(10);
const MAX_SCAN_CHILDREN: usize = 1000;
const GIT_OUTPUT_LIMIT: usize = 4096;
pub const PROJECTS_MAX_AGE: Duration = Duration::from_secs(10);

#[derive(Debug)]
pub enum FileError {
    /// The path is malformed or leaves the root.
    Refused,
    NotFound,
    TooLarge,
}

fn inside(root: &Path, path: &Path) -> bool {
    path == root || path.starts_with(root)
}

/// `os.path.realpath` (non-strict): symlinks are followed where they exist,
/// a missing tail is kept lexically.
pub fn realpath(path: &Path) -> PathBuf {
    let mut pending: Vec<PathBuf> = vec![path.to_path_buf()];
    let mut resolved = PathBuf::from("/");
    let mut hops = 0;
    while let Some(next) = pending.pop() {
        let mut components: Vec<_> = next.components().collect();
        components.reverse();
        while let Some(component) = components.pop() {
            match component {
                Component::RootDir => resolved = PathBuf::from("/"),
                Component::CurDir | Component::Prefix(_) => {}
                Component::ParentDir => {
                    resolved.pop();
                }
                Component::Normal(name) => {
                    let candidate = resolved.join(name);
                    match std::fs::read_link(&candidate) {
                        Ok(target) if hops < 40 => {
                            hops += 1;
                            let rest: PathBuf = components.iter().rev().collect();
                            components.clear();
                            if !rest.as_os_str().is_empty() {
                                pending.push(rest);
                            }
                            pending.push(target);
                        }
                        _ => resolved = candidate,
                    }
                }
            }
        }
    }
    resolved
}

/// The real path for a request path ("" is the root itself).
pub fn resolve(root: &Path, relative: &str) -> Result<PathBuf, FileError> {
    if relative.chars().count() > MAX_PATH_CHARS
        || relative.contains('\0')
        || relative.starts_with('/')
    {
        return Err(FileError::Refused);
    }
    let parts: Vec<&str> = relative.split('/').filter(|p| !p.is_empty()).collect();
    if parts.iter().any(|p| *p == "." || *p == "..") {
        return Err(FileError::Refused);
    }
    if parts.is_empty() {
        return Ok(root.to_path_buf());
    }
    let real = realpath(
        &parts
            .iter()
            .fold(root.to_path_buf(), |path, part| path.join(part)),
    );
    if !inside(root, &real) {
        return Err(FileError::Refused);
    }
    Ok(real)
}

fn relative(root: &Path, real: &Path) -> String {
    real.strip_prefix(root)
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn open_at(dir: Option<&OwnedFd>, name: &OsStr, flags: i32) -> std::io::Result<OwnedFd> {
    let name = CString::new(name.as_bytes())
        .map_err(|_| std::io::Error::from_raw_os_error(libc::EINVAL))?;
    let dirfd = dir.map_or(libc::AT_FDCWD, |d| d.as_raw_fd());
    let fd = unsafe { libc::openat(dirfd, name.as_ptr(), flags | libc::O_CLOEXEC) };
    if fd < 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(unsafe { OwnedFd::from_raw_fd(fd) })
    }
}

fn is_link_at(dir: &OwnedFd, name: &OsStr) -> bool {
    let Ok(name) = CString::new(name.as_bytes()) else {
        return false;
    };
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    let rc = unsafe {
        libc::fstatat(
            dir.as_raw_fd(),
            name.as_ptr(),
            &mut st,
            libc::AT_SYMLINK_NOFOLLOW,
        )
    };
    rc == 0 && (st.st_mode & libc::S_IFMT) == libc::S_IFLNK
}

/// Opens `real` (already resolved inside `root`) one component at a time with
/// O_NOFOLLOW, so no component can be swapped for a symlink in between.
fn open_inside(root: &Path, real: &Path, flags: i32) -> Result<OwnedFd, FileError> {
    let to_error = |error: std::io::Error| match error.raw_os_error() {
        Some(libc::ELOOP) | Some(libc::ENAMETOOLONG) => FileError::Refused,
        _ => FileError::NotFound,
    };
    let mut fd =
        open_at(None, root.as_os_str(), libc::O_RDONLY | libc::O_DIRECTORY).map_err(to_error)?;
    let parts: Vec<&OsStr> = match real.strip_prefix(root) {
        Ok(rest) => rest.iter().collect(),
        Err(_) => return Err(FileError::Refused),
    };
    for (index, part) in parts.iter().enumerate() {
        let mode = if index == parts.len() - 1 {
            flags
        } else {
            libc::O_RDONLY | libc::O_DIRECTORY
        };
        match open_at(Some(&fd), part, mode | libc::O_NOFOLLOW) {
            Ok(child) => fd = child,
            Err(error) => {
                if is_link_at(&fd, part) {
                    return Err(FileError::Refused);
                }
                return Err(to_error(error));
            }
        }
    }
    Ok(fd)
}

fn entry(root: &Path, folder: &Path, name: &str) -> Value {
    let path = folder.join(name);
    let mut kind = "other";
    let mut size = Value::Null;
    let mut mtime = Value::Null;
    let is_link = std::fs::symlink_metadata(&path)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false);
    let target = if is_link { realpath(&path) } else { path };
    // A link that leaves the root is listed but never opened.
    if inside(root, &target)
        && let Ok(info) = std::fs::metadata(&target)
    {
        mtime = json!(info.mtime());
        if info.is_dir() {
            kind = "dir";
        } else if info.is_file() {
            kind = "file";
            size = json!(info.size());
        }
    }
    json!({"name": name, "type": kind, "size": size, "modified_at": mtime})
}

pub fn list_dir(root: &Path, relative_path: &str) -> Result<Value, FileError> {
    let real = resolve(root, relative_path)?;
    let fd = open_inside(root, &real, libc::O_RDONLY | libc::O_DIRECTORY)?;
    // Read the opened directory itself, never the path again.
    let proc_path = PathBuf::from(format!("/proc/self/fd/{}", fd.as_raw_fd()));
    let reader = std::fs::read_dir(&proc_path).map_err(|_| FileError::NotFound)?;
    let mut entries = Vec::new();
    let mut truncated = false;
    for item in reader {
        let Ok(item) = item else { continue };
        if entries.len() >= MAX_ENTRIES {
            truncated = true;
            break;
        }
        // Not representable in JSON; it could not be opened by name either.
        let Some(name) = item.file_name().to_str().map(str::to_string) else {
            continue;
        };
        entries.push(entry(root, &real, &name));
    }
    drop(fd);
    entries.sort_by_cached_key(|e| {
        (
            e["type"] != "dir",
            e["name"].as_str().unwrap_or("").to_lowercase(),
        )
    });
    Ok(json!({
        "object": "orsik.files.list",
        "root": root.to_string_lossy(),
        "path": relative(root, &real),
        "entries": entries,
        "truncated": truncated,
    }))
}

/// The bytes of a regular file (at most MAX_FILE_BYTES) and its name.
pub fn read_file(root: &Path, relative_path: &str) -> Result<(Vec<u8>, String), FileError> {
    let real = resolve(root, relative_path)?;
    if real == root {
        return Err(FileError::NotFound);
    }
    // O_NONBLOCK: a FIFO must not hang the worker; it is refused below.
    let fd = open_inside(root, &real, libc::O_RDONLY | libc::O_NONBLOCK)?;
    let mut file = std::fs::File::from(fd);
    let info = file.metadata().map_err(|_| FileError::NotFound)?;
    if !info.is_file() {
        return Err(FileError::NotFound);
    }
    if info.len() > MAX_FILE_BYTES {
        return Err(FileError::TooLarge);
    }
    let mut data = Vec::new();
    (&mut file)
        .take(MAX_FILE_BYTES + 1)
        .read_to_end(&mut data)
        .map_err(|_| FileError::NotFound)?;
    if data.len() as u64 > MAX_FILE_BYTES {
        return Err(FileError::TooLarge);
    }
    let name = real
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    Ok((data, name))
}

/// A small, closed set: images by extension, UTF-8 text, else binary.
pub fn content_type(name: &str, data: &[u8]) -> &'static str {
    let extension = Path::new(name)
        .extension()
        .and_then(OsStr::to_str)
        .map(str::to_lowercase);
    match extension.as_deref() {
        Some("png") => return "image/png",
        Some("jpg") | Some("jpeg") => return "image/jpeg",
        Some("webp") => return "image/webp",
        Some("gif") => return "image/gif",
        _ => {}
    }
    if !data[..data.len().min(8192)].contains(&0) && std::str::from_utf8(data).is_ok() {
        return "text/plain; charset=utf-8";
    }
    "application/octet-stream"
}

// -- projects ------------------------------------------------------------------

const EMPTY_TREE_SHA1: &str = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
const EMPTY_TREE_SHA256: &str = "6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321";

/// git's first GIT_OUTPUT_LIMIT bytes, or None (error, timeout). Programs a
/// repository could name (fsmonitor, hooks, attribute drivers) never run, and
/// the whole process group dies afterwards.
fn git(repo: &Path, args: &[&str], object_format: &str, deadline: Instant) -> Option<String> {
    let budget = GIT_TIMEOUT.min(deadline.checked_duration_since(Instant::now())?);
    if budget.is_zero() {
        return None;
    }
    let empty_tree = if object_format == "sha256" {
        EMPTY_TREE_SHA256
    } else {
        EMPTY_TREE_SHA1
    };
    let mut command = Command::new("git");
    command
        .arg("--no-optional-locks")
        .arg(format!("--attr-source={empty_tree}"))
        .arg(format!("--git-dir={}", repo.join(".git").display()))
        .arg(format!("--work-tree={}", repo.display()))
        .args([
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.hooksPath=/dev/null",
        ])
        .args([
            "-c",
            "core.untrackedCache=false",
            "-c",
            "submodule.recurse=false",
        ])
        .args(args)
        .env_clear()
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .process_group(0);
    for key in ["PATH", "HOME", "LANG"] {
        if let Some(value) = std::env::var_os(key) {
            command.env(key, value);
        }
    }
    command
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_OPTIONAL_LOCKS", "0");
    let mut child = command.spawn().ok()?;
    let pid = child.id() as i32;
    let mut stdout = child.stdout.take()?;
    let end = Instant::now() + budget;
    let mut output = Vec::new();
    let mut complete = false;
    let mut ok = false;
    'read: while output.len() < GIT_OUTPUT_LIMIT {
        let remaining = end.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        let mut pfd = libc::pollfd {
            fd: stdout.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        let ready = unsafe { libc::poll(&mut pfd, 1, remaining.as_millis().max(1) as i32) };
        if ready <= 0 {
            break;
        }
        let mut chunk = [0u8; GIT_OUTPUT_LIMIT];
        match stdout.read(&mut chunk) {
            Ok(0) => {
                complete = true;
                break 'read;
            }
            Ok(n) => output.extend_from_slice(&chunk[..n]),
            Err(_) => break,
        }
    }
    if output.len() >= GIT_OUTPUT_LIMIT {
        ok = true;
    }
    if complete {
        let wait_end = Instant::now()
            + end
                .saturating_duration_since(Instant::now())
                .max(Duration::from_millis(50));
        loop {
            match child.try_wait() {
                Ok(Some(status)) => {
                    ok = status.success();
                    break;
                }
                Ok(None) if Instant::now() < wait_end => {
                    std::thread::sleep(Duration::from_millis(5))
                }
                _ => break,
            }
        }
    }
    unsafe {
        libc::killpg(pid, libc::SIGKILL);
    }
    drop(stdout);
    let _ = child.wait();
    if !ok {
        return None;
    }
    output.truncate(GIT_OUTPUT_LIMIT);
    Some(String::from_utf8_lossy(&output).into_owned())
}

fn project(root: &Path, repo: &Path, deadline: Instant) -> Value {
    let format = git(
        repo,
        &["rev-parse", "--show-object-format"],
        "sha1",
        deadline,
    )
    .map(|f| f.trim().to_string())
    .filter(|f| f == "sha1" || f == "sha256")
    .unwrap_or_else(|| "sha1".into());
    let branch = git(
        repo,
        &["rev-parse", "--abbrev-ref", "HEAD"],
        &format,
        deadline,
    );
    let head = git(
        repo,
        &["log", "-1", "--format=%h%x00%ct%x00%s"],
        &format,
        deadline,
    );
    let status = git(
        repo,
        &[
            "status",
            "--porcelain",
            "--untracked-files=no",
            "--ignore-submodules=all",
        ],
        &format,
        deadline,
    );
    let mut commit = Value::Null;
    if let Some(head) = head.filter(|h| h.matches('\0').count() == 2) {
        let line = head.trim_end_matches('\n');
        let fields: Vec<&str> = line.split('\0').collect();
        if !fields[1].is_empty() && fields[1].chars().all(|c| c.is_ascii_digit()) {
            commit = json!({
                "short": fields[0],
                "committed_at": fields[1].parse::<i64>().unwrap_or(0),
                "subject": fields[2].chars().take(200).collect::<String>(),
            });
        }
    }
    json!({
        "name": repo.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
        "path": relative(root, repo),
        "branch": branch.map(|b| b.trim().to_string()).filter(|b| !b.is_empty()),
        "dirty": status.map(|s| !s.trim().is_empty()),
        "last_commit": commit,
    })
}

/// Git working trees in the root and up to PROJECT_DEPTH levels below.
pub fn list_projects(root: &Path) -> Value {
    fn walk(
        root: &Path,
        directory: &Path,
        depth: usize,
        repos: &mut Vec<PathBuf>,
        truncated: &mut bool,
    ) {
        let git_dir = directory.join(".git");
        let is_link = std::fs::symlink_metadata(&git_dir)
            .map(|m| m.file_type().is_symlink())
            .unwrap_or(false);
        // A real .git folder inside the root only.
        if !is_link && git_dir.is_dir() && inside(root, &realpath(&git_dir)) {
            if repos.len() >= MAX_PROJECTS {
                *truncated = true;
                return;
            }
            repos.push(directory.to_path_buf());
            return;
        }
        if depth >= PROJECT_DEPTH {
            return;
        }
        let Ok(reader) = std::fs::read_dir(directory) else {
            return;
        };
        let mut children: Vec<PathBuf> = reader
            .take(MAX_SCAN_CHILDREN)
            .filter_map(Result::ok)
            .filter(|e| {
                e.file_type().is_ok_and(|t| t.is_dir())
                    && !e.file_name().as_bytes().starts_with(b".")
            })
            .map(|e| e.path())
            .collect();
        children.sort();
        for child in children {
            walk(root, &child, depth + 1, repos, truncated);
        }
    }
    let mut repos = Vec::new();
    let mut truncated = false;
    walk(root, root, 0, &mut repos, &mut truncated);
    let deadline = Instant::now() + SCAN_DEADLINE;
    let projects: Vec<Value> = repos
        .iter()
        .map(|repo| project(root, repo, deadline))
        .collect();
    json!({"object": "orsik.projects.list", "projects": projects, "truncated": truncated})
}

// -- routes ----------------------------------------------------------------------

fn files_error(error: FileError) -> Response {
    match error {
        FileError::Refused => closed(403, "path_not_allowed"),
        FileError::TooLarge => closed(413, "file_too_large"),
        FileError::NotFound => closed(404, "not_found"),
    }
}

fn no_store(mut response: Response) -> Response {
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, "no-store".parse().unwrap());
    response
}

/// Shared checks of the read-only file routes; Ok(path) or the refusal.
async fn files_request(
    state: &State,
    request: Request,
    with_path: bool,
) -> Result<Result<String, Response>, crate::server::Failure> {
    if lookup(state, request.headers()).await?.is_none() {
        return Ok(Err(closed(401, "invalid_credentials")));
    }
    if state.files_root.is_none() {
        return Ok(Err(closed(403, "endpoint_out_of_scope")));
    }
    let pairs: Vec<(String, String)> = request
        .uri()
        .query()
        .map(|q| {
            url::form_urlencoded::parse(q.as_bytes())
                .into_owned()
                .collect()
        })
        .unwrap_or_default();
    let mut seen = std::collections::HashSet::new();
    for (key, _) in &pairs {
        if !(with_path && key == "path") || !seen.insert(key.clone()) {
            return Ok(Err(closed(403, "query_not_allowed")));
        }
    }
    match read_body(request.into_body()).await {
        Err(BodyError::TooLarge) => return Ok(Err(closed(413, "body_too_large"))),
        Ok(body) if !body.is_empty() => return Ok(Err(closed(400, "body_not_allowed"))),
        _ => {}
    }
    Ok(Ok(pairs
        .into_iter()
        .next()
        .map(|(_, v)| v)
        .unwrap_or_default()))
}

pub async fn handle_list(state: &State, request: Request) -> Handled {
    let path = match files_request(state, request, true).await? {
        Ok(path) => path,
        Err(response) => return Ok(response),
    };
    let root = state.files_root.clone().expect("checked");
    let listing = tokio::task::spawn_blocking(move || list_dir(&root, &path))
        .await
        .map_err(|_| crate::server::Failure::Store)?;
    Ok(match listing {
        Ok(listing) => no_store(crate::server::json_response(200, &listing)),
        Err(error) => files_error(error),
    })
}

pub async fn handle_content(state: &State, request: Request) -> Handled {
    let headers = request.headers().clone();
    let path = match files_request(state, request, true).await? {
        Ok(path) => path,
        Err(response) => return Ok(response),
    };
    let root = state.files_root.clone().expect("checked");
    let requested = path.clone();
    let read = tokio::task::spawn_blocking(move || read_file(&root, &path))
        .await
        .map_err(|_| crate::server::Failure::Store)?;
    let (data, name) = match read {
        Ok(read) => read,
        Err(error) => return Ok(files_error(error)),
    };
    if let Some(record) = lookup(state, &headers).await? {
        audit(
            "file_read",
            &record.device_id,
            &[("path", requested), ("bytes", data.len().to_string())],
        );
    }
    Ok(Response::builder()
        .status(200)
        .header(header::CONTENT_TYPE, content_type(&name, &data))
        .header(header::CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(Body::from(data))
        .unwrap())
}

pub async fn handle_projects(state: &State, request: Request) -> Handled {
    if let Err(response) = files_request(state, request, false).await? {
        return Ok(response);
    }
    let root = state.files_root.clone().expect("checked");
    let mut cache = state.projects.lock().await;
    if !cache
        .as_ref()
        .is_some_and(|(at, _)| at.elapsed() <= PROJECTS_MAX_AGE)
    {
        let snapshot = tokio::task::spawn_blocking(move || list_projects(&root))
            .await
            .map_err(|_| crate::server::Failure::Store)?;
        *cache = Some((Instant::now(), snapshot));
    }
    Ok(no_store(crate::server::json_response(
        200,
        &cache.as_ref().unwrap().1,
    )))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn content_types_are_a_closed_set() {
        assert_eq!(content_type("a.PNG", b""), "image/png");
        assert_eq!(
            content_type("a.txt", "grüß".as_bytes()),
            "text/plain; charset=utf-8"
        );
        assert_eq!(content_type("a.bin", b"a\0b"), "application/octet-stream");
        assert_eq!(content_type(".png", b"\xff"), "application/octet-stream");
    }

    #[test]
    fn request_paths_stay_inside_the_root() {
        let root = realpath(&std::env::temp_dir());
        assert!(matches!(resolve(&root, "../x"), Err(FileError::Refused)));
        assert!(matches!(resolve(&root, "/etc"), Err(FileError::Refused)));
        assert!(matches!(resolve(&root, "a\0b"), Err(FileError::Refused)));
        assert_eq!(resolve(&root, "").unwrap(), root);
        assert_eq!(resolve(&root, "a//b/").unwrap(), root.join("a/b"));
    }
}
