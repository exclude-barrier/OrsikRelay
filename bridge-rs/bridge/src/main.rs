//! Orsik Mobile Bridge — loopback HTTP surface over an unmodified official
//! Hermes. It never exposes the Hermes API key, never forwards an arbitrary
//! path upstream and binds loopback plaintext only; TLS is provided
//! out-of-band (Tailscale Serve, a reverse proxy). Contract:
//! docs/bridge-contract.md.

mod admin;
mod config;
mod core;
mod files;
mod history;
mod host;
mod i18n;
mod pairing;
mod routes;
mod runhub;
mod server;
mod settings;
mod sse;
mod store;
mod terminal;
mod tui;
mod update;

use std::net::SocketAddr;
use std::sync::{Arc, Mutex};

use config::Refusal;

fn refuse(Refusal(message): Refusal) -> ! {
    eprintln!("{message}");
    std::process::exit(1);
}

fn main() {
    // Files the bridge creates (store, sidecars, backups) are never
    // group/world accessible.
    unsafe {
        libc::umask(0o077);
    }
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("admin") => {
            args.remove(0);
            std::process::exit(admin::main(args));
        }
        Some("setup") => {
            args.remove(0);
            std::process::exit(tui::setup_main(args));
        }
        Some("update") => {
            args.remove(0);
            std::process::exit(update::main(args));
        }
        Some("check-config") => std::process::exit(check_config()),
        _ => {}
    }
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_target(false)
        .init();
    let env = config::load_env().unwrap_or_else(|r| refuse(r));
    let upstream = config::validate_upstream(
        env.get("BRIDGE_UPSTREAM")
            .map(String::as_str)
            .unwrap_or("http://127.0.0.1:8642"),
    )
    .unwrap_or_else(|r| refuse(r));
    let master_key = config::master_key(&env).unwrap_or_else(|r| refuse(r));
    let host_services =
        config::parse_services(env.get("BRIDGE_HOST_SERVICES")).unwrap_or_else(|r| refuse(r));
    let files_root = config::files_root(&env).unwrap_or_else(|r| refuse(r));
    let store =
        store::Store::open(&config::state_db_path(&env)).unwrap_or_else(|e| refuse(Refusal(e.0)));
    let terminal_command = config::terminal_command(&env).unwrap_or_else(|r| refuse(r));
    let bind = config::validate_bind(
        env.get("BRIDGE_BIND")
            .filter(|v| !v.is_empty())
            .map(String::as_str)
            .unwrap_or("127.0.0.1"),
    )
    .unwrap_or_else(|r| refuse(r));
    let port = config::validate_port(
        env.get("BRIDGE_PORT")
            .filter(|v| !v.is_empty())
            .map(String::as_str)
            .unwrap_or("9842"),
    )
    .unwrap_or_else(|r| refuse(r));

    let state = Arc::new(server::AppState {
        upstream: upstream.clone(),
        master_key,
        store: Arc::new(store),
        client: server::upstream_client(),
        pair_limiter: Mutex::new(pairing::RateLimiter::new(60, 60.0)),
        trust_tailscale_identity: env
            .get("BRIDGE_TRUST_TAILSCALE_IDENTITY")
            .map(String::as_str)
            == Some("1"),
        host_services,
        terminal_command,
        files_root,
        runhub: runhub::RunHub::default(),
        active_terminals: Default::default(),
        host_metrics: Default::default(),
        projects: Default::default(),
    });
    let runtime = tokio::runtime::Runtime::new().expect("tokio runtime");
    runtime.block_on(async move {
        let address = SocketAddr::new(bind, port);
        let listener = tokio::net::TcpListener::bind(address)
            .await
            .unwrap_or_else(|e| {
                eprintln!("could not listen on {address}: {e}");
                std::process::exit(1);
            });
        tracing::info!(
            "Orsik Mobile Bridge on http://{address} -> {}",
            upstream_label(&upstream)
        );
        let app = axum::Router::new()
            .fallback(server::dispatch)
            .with_state(state);
        axum::serve(
            listener,
            app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(shutdown_signal())
        .await
        .ok();
    });
}

/// The start-up checks without the Hermes key and without listening, plus
/// a writable store directory (used by `install.sh --check`).
fn check_config() -> i32 {
    let checks = || -> Result<(), Refusal> {
        let env = config::load_env()?;
        let value = |key: &str, default: &'static str| {
            env.get(key)
                .filter(|v| !v.is_empty())
                .cloned()
                .unwrap_or(default.into())
        };
        config::validate_bind(&value("BRIDGE_BIND", "127.0.0.1"))?;
        config::validate_port(&value("BRIDGE_PORT", "9842"))?;
        config::validate_upstream(
            env.get("BRIDGE_UPSTREAM")
                .map(String::as_str)
                .unwrap_or("http://127.0.0.1:8642"),
        )?;
        config::parse_services(env.get("BRIDGE_HOST_SERVICES"))?;
        config::terminal_command(&env)?;
        config::files_root(&env)?;
        let db = config::state_db_path(&env);
        let db = if db.is_absolute() {
            db
        } else {
            settings::expand_user("~").join(db)
        };
        let parent = db
            .parent()
            .map(std::path::Path::to_path_buf)
            .unwrap_or_default();
        let mut probe = parent.clone();
        while !probe.exists() {
            if !probe.pop() {
                break;
            }
        }
        let c = std::ffi::CString::new(probe.as_os_str().as_encoded_bytes()).unwrap_or_default();
        if unsafe { libc::access(c.as_ptr(), libc::W_OK | libc::X_OK) } != 0 {
            return Err(Refusal(format!(
                "BRIDGE_STATE_DB is not writable: {}",
                parent.display()
            )));
        }
        Ok(())
    };
    match checks() {
        Ok(()) => 0,
        Err(Refusal(message)) => {
            eprintln!("{message}");
            1
        }
    }
}

/// `http://host:port`, never user info (the upstream is validated without it anyway).
fn upstream_label(upstream: &str) -> String {
    upstream.to_string()
}

async fn shutdown_signal() {
    use tokio::signal::unix::{SignalKind, signal};
    let mut term = signal(SignalKind::terminate()).expect("SIGTERM handler");
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
}
