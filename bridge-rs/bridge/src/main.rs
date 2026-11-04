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
mod pairing;
mod routes;
mod runhub;
mod server;
mod sse;
mod store;
mod terminal;

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
        Some("setup") | Some("update") => {
            eprintln!("the installer front ends are not part of this build yet");
            std::process::exit(2);
        }
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
