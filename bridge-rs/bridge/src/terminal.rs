//! Interactive terminal: one PTY per WebSocket, attached to a tmux session
//! (Python `terminal.py` + `_handle_terminal`). Off unless BRIDGE_TERMINAL=1
//! and the operator granted the device. The child gets a minimal login-like
//! environment, never the bridge's own (no Hermes key). Closing the socket
//! ends only the tmux client; the tmux session stays.

use std::ffi::CStr;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::process::Stdio;
use std::sync::atomic::Ordering;
use std::time::Duration;

use axum::extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade};
use axum::extract::{FromRequestParts, Request};
use axum::http::header;
use futures_util::{SinkExt, StreamExt};
use serde_json::Value;
use tokio::io::unix::AsyncFd;
use tokio::process::{Child, Command};

use crate::server::{Handled, State, audit, closed, db, device_token, lookup};

pub const MAX_TERMINALS: usize = 4;
/// Bound of one client message (typing, a paste, a resize).
pub const MAX_INPUT_BYTES: usize = 64 * 1024;
const READ_CHUNK: usize = 64 * 1024;
const MAX_COLS: i64 = 500;
const MAX_ROWS: i64 = 300;
const REVOKE_CHECK_INTERVAL: Duration = Duration::from_secs(15);
const HEARTBEAT: Duration = Duration::from_secs(30);

/// `{"type": "resize", "cols": n, "rows": n}` with sane bounds, else None.
pub fn parse_resize(payload: &Value) -> Option<(u16, u16)> {
    let object = payload.as_object()?;
    if object.len() != 3 || object.get("type")? != "resize" {
        return None;
    }
    // A JSON true or 1.0 is not a size.
    let cols = object.get("cols")?.as_i64()?;
    let rows = object.get("rows")?.as_i64()?;
    if !(1..=MAX_COLS).contains(&cols) || !(1..=MAX_ROWS).contains(&rows) {
        return None;
    }
    Some((cols as u16, rows as u16))
}

fn set_size(fd: i32, cols: u16, rows: u16) {
    let size = libc::winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    unsafe {
        libc::ioctl(fd, libc::TIOCSWINSZ, &size);
    }
}

struct Passwd {
    dir: String,
    name: String,
    shell: String,
}

fn current_user() -> Option<Passwd> {
    let mut entry: libc::passwd = unsafe { std::mem::zeroed() };
    let mut buffer = vec![0 as libc::c_char; 16 * 1024];
    let mut result: *mut libc::passwd = std::ptr::null_mut();
    let rc = unsafe {
        libc::getpwuid_r(
            libc::geteuid(),
            &mut entry,
            buffer.as_mut_ptr(),
            buffer.len(),
            &mut result,
        )
    };
    if rc != 0 || result.is_null() {
        return None;
    }
    let text = |ptr: *const libc::c_char| {
        if ptr.is_null() {
            String::new()
        } else {
            unsafe { CStr::from_ptr(ptr) }
                .to_string_lossy()
                .into_owned()
        }
    };
    Some(Passwd {
        dir: text(entry.pw_dir),
        name: text(entry.pw_name),
        shell: text(entry.pw_shell),
    })
}

struct Pty {
    master: AsyncFd<OwnedFd>,
    child: Child,
}

impl Pty {
    fn spawn(command: &[String]) -> std::io::Result<Pty> {
        let user = current_user().ok_or_else(|| std::io::Error::other("no passwd entry"))?;
        let (mut master, mut slave) = (0, 0);
        if unsafe {
            libc::openpty(
                &mut master,
                &mut slave,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null(),
            )
        } != 0
        {
            return Err(std::io::Error::last_os_error());
        }
        let master = unsafe { OwnedFd::from_raw_fd(master) };
        let slave = unsafe { OwnedFd::from_raw_fd(slave) };
        set_size(master.as_raw_fd(), 80, 24);
        unsafe {
            let flags = libc::fcntl(master.as_raw_fd(), libc::F_GETFL);
            libc::fcntl(master.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK);
            libc::fcntl(master.as_raw_fd(), libc::F_SETFD, libc::FD_CLOEXEC);
        }
        let path = std::env::var("PATH").unwrap_or_else(|_| "/usr/local/bin:/usr/bin:/bin".into());
        let lang = std::env::var("LANG")
            .ok()
            .filter(|l| !l.is_empty())
            .unwrap_or_else(|| "C.UTF-8".into());
        let shell = if user.shell.is_empty() {
            "/bin/sh".to_string()
        } else {
            user.shell.clone()
        };
        // No new session here: "setsid --ctty" creates it itself.
        let child = Command::new(&command[0])
            .args(&command[1..])
            .env_clear()
            .env("HOME", &user.dir)
            .env("USER", &user.name)
            .env("LOGNAME", &user.name)
            .env("SHELL", shell)
            .env("PATH", path)
            .env("TERM", "xterm-256color")
            .env("LANG", lang)
            .current_dir(&user.dir)
            .stdin(Stdio::from(slave.try_clone()?))
            .stdout(Stdio::from(slave.try_clone()?))
            .stderr(Stdio::from(slave))
            .spawn()?;
        Ok(Pty {
            // SAFETY: the OwnedFd is moved in and neither replaced nor closed
            // while the AsyncFd owns it.
            master: unsafe { AsyncFd::register(master)? },
            child,
        })
    }

    /// The next output chunk; empty once the child closed its side.
    async fn read(&self) -> Vec<u8> {
        loop {
            let Ok(mut guard) = self.master.readable().await else {
                return Vec::new();
            };
            let mut buffer = vec![0u8; READ_CHUNK];
            let result = guard.try_io(|fd| {
                let n =
                    unsafe { libc::read(fd.as_raw_fd(), buffer.as_mut_ptr().cast(), buffer.len()) };
                if n < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            });
            match result {
                Ok(Ok(n)) => {
                    buffer.truncate(n);
                    return buffer;
                }
                // EIO: every slave fd is closed (the child exited).
                Ok(Err(_)) => return Vec::new(),
                Err(_would_block) => continue,
            }
        }
    }

    async fn write(&self, mut data: &[u8]) -> std::io::Result<()> {
        while !data.is_empty() {
            let mut guard = self.master.writable().await?;
            match guard.try_io(|fd| {
                let n = unsafe { libc::write(fd.as_raw_fd(), data.as_ptr().cast(), data.len()) };
                if n < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            }) {
                Ok(Ok(n)) => data = &data[n..],
                Ok(Err(error)) => return Err(error),
                Err(_would_block) => continue,
            }
        }
        Ok(())
    }

    fn resize(&self, cols: u16, rows: u16) {
        set_size(self.master.as_raw_fd(), cols, rows);
    }

    fn signal(&self, signal: i32) {
        let Some(pid) = self.child.id() else { return };
        // The child's own group once setsid ran; before that only the child.
        if unsafe { libc::killpg(pid as i32, signal) } != 0 {
            unsafe {
                libc::kill(pid as i32, signal);
            }
        }
    }

    /// Ends the tmux client (HUP, then KILL); the session stays.
    async fn close(mut self) {
        if matches!(self.child.try_wait(), Ok(None)) {
            self.signal(libc::SIGHUP);
            if tokio::time::timeout(Duration::from_secs(2), self.child.wait())
                .await
                .is_err()
            {
                self.signal(libc::SIGKILL);
                let _ = self.child.wait().await;
            }
        }
    }
}

pub async fn handle(state: &State, request: Request) -> Handled {
    let token = device_token(request.headers());
    let Some(record) = lookup(state, request.headers()).await? else {
        return Ok(closed(401, "invalid_credentials"));
    };
    let Some(command) = state.terminal_command.clone() else {
        return Ok(closed(403, "endpoint_out_of_scope"));
    };
    if !record.terminal_allowed {
        audit("terminal", &record.device_id, &[("status", "403".into())]);
        return Ok(closed(403, "terminal_not_allowed"));
    }
    if request.uri().query().is_some_and(|q| !q.is_empty()) {
        return Ok(closed(403, "query_not_allowed"));
    }
    // A browser always sends Origin on a WebSocket; the app never does.
    if request.headers().contains_key(header::ORIGIN) {
        audit("terminal", &record.device_id, &[("status", "403".into())]);
        return Ok(closed(403, "origin_not_allowed"));
    }
    let (mut parts, _) = request.into_parts();
    let Ok(upgrade) = WebSocketUpgrade::from_request_parts(&mut parts, &()).await else {
        return Ok(closed(400, "websocket_required"));
    };
    if state
        .active_terminals
        .try_update(Ordering::SeqCst, Ordering::SeqCst, |n| {
            (n < MAX_TERMINALS).then_some(n + 1)
        })
        .is_err()
    {
        audit("terminal", &record.device_id, &[("status", "429".into())]);
        return Ok(closed(429, "terminal_limit"));
    }
    let release = |state: &State| {
        state.active_terminals.fetch_sub(1, Ordering::SeqCst);
    };
    let pty = match Pty::spawn(&command) {
        Ok(pty) => pty,
        Err(_) => {
            release(state);
            tracing::error!("Terminal could not be started");
            audit("terminal", &record.device_id, &[("status", "503".into())]);
            return Ok(closed(503, "terminal_unavailable"));
        }
    };
    // Spawning may race an operator revoke: recheck before exposing the PTY.
    let recheck_token = token.clone();
    let current = match db(state, move |s| s.lookup_device(recheck_token.as_deref())).await {
        Ok(current) => current,
        Err(failure) => {
            pty.close().await;
            release(state);
            return Err(failure);
        }
    };
    let refusal = match &current {
        None => Some(("revoked", closed(401, "invalid_credentials"))),
        Some(r) if !r.terminal_allowed => {
            Some(("terminal_revoked", closed(403, "terminal_not_allowed")))
        }
        _ => None,
    };
    if let Some((reason, response)) = refusal {
        pty.close().await;
        release(state);
        audit(
            "terminal_close",
            &record.device_id,
            &[("reason", reason.into())],
        );
        return Ok(response);
    }
    let state = state.clone();
    let device_id = record.device_id.clone();
    let response = upgrade
        .max_message_size(MAX_INPUT_BYTES)
        .max_frame_size(MAX_INPUT_BYTES)
        .on_upgrade(move |socket| async move {
            audit("terminal_open", &device_id, &[]);
            let (reason, pty, socket) = pump(&state, token, socket, pty).await;
            pty.close().await;
            state.active_terminals.fetch_sub(1, Ordering::SeqCst);
            audit("terminal_close", &device_id, &[("reason", reason.into())]);
            if let Some(mut socket) = socket {
                let code = match reason {
                    "exited" => 1000,
                    "protocol" => 1008,
                    "revoked" => 4401,
                    "terminal_revoked" => 4403,
                    _ => 1011,
                };
                let _ = socket
                    .send(Message::Close(Some(CloseFrame {
                        code,
                        reason: reason.into(),
                    })))
                    .await;
            }
        });
    Ok(response)
}

/// Relays until the shell exits, the client leaves, sends garbage or is
/// revoked; returns which one (never input or output content).
async fn pump(
    state: &State,
    token: Option<String>,
    socket: WebSocket,
    pty: Pty,
) -> (&'static str, Pty, Option<WebSocket>) {
    let (mut sink, mut stream) = socket.split();
    let reason = {
        let output = async {
            let mut heartbeat =
                tokio::time::interval_at(tokio::time::Instant::now() + HEARTBEAT, HEARTBEAT);
            loop {
                let message = tokio::select! {
                    data = pty.read() => {
                        if data.is_empty() {
                            return "exited";
                        }
                        Message::Binary(data.into())
                    }
                    _ = heartbeat.tick() => Message::Ping(Default::default()),
                };
                if sink.send(message).await.is_err() {
                    return "client";
                }
            }
        };
        let input = async {
            while let Some(message) = stream.next().await {
                match message {
                    Ok(Message::Binary(data)) => {
                        if pty.write(&data).await.is_err() {
                            return "error";
                        }
                    }
                    Ok(Message::Text(text)) => {
                        let size = serde_json::from_str::<Value>(text.as_str())
                            .ok()
                            .and_then(|v| parse_resize(&v));
                        let Some((cols, rows)) = size else {
                            return "protocol";
                        };
                        pty.resize(cols, rows);
                    }
                    Ok(Message::Ping(_)) | Ok(Message::Pong(_)) => {}
                    Ok(Message::Close(_)) => return "client",
                    Err(_) => return "error",
                }
            }
            "client"
        };
        let revocation = async {
            loop {
                tokio::time::sleep(REVOKE_CHECK_INTERVAL).await;
                let token = token.clone();
                match db(state, move |s| s.lookup_device(token.as_deref())).await {
                    Ok(Some(record)) if record.terminal_allowed => {}
                    Ok(Some(_)) => return "terminal_revoked",
                    _ => return "revoked",
                }
            }
        };
        tokio::select! {
            reason = output => reason,
            reason = input => reason,
            reason = revocation => reason,
        }
    };
    match sink.reunite(stream) {
        Ok(socket) => (reason, pty, Some(socket)),
        Err(_) => (reason, pty, None),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn a_resize_is_strict() {
        assert_eq!(
            parse_resize(&json!({"type": "resize", "cols": 100, "rows": 30})),
            Some((100, 30))
        );
        assert_eq!(
            parse_resize(&json!({"type": "resize", "cols": 0, "rows": 30})),
            None
        );
        assert_eq!(
            parse_resize(&json!({"type": "resize", "cols": true, "rows": 30})),
            None
        );
        assert_eq!(
            parse_resize(&json!({"type": "resize", "cols": 1.0, "rows": 30})),
            None
        );
        assert_eq!(
            parse_resize(&json!({"type": "resize", "cols": 1, "rows": 1, "x": 1})),
            None
        );
    }
}
