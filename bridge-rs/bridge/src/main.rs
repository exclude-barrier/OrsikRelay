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
    // Optional second loopback listener for the public mTLS reverse proxy.
    let public_port = match env
        .get("BRIDGE_PUBLIC_PORT")
        .filter(|v| !v.trim().is_empty())
    {
        Some(raw) => {
            let public = config::validate_port(raw).unwrap_or_else(|_| {
                refuse(Refusal(
                    "BRIDGE_PUBLIC_PORT must be a number between 1 and 65535".into(),
                ))
            });
            if public == port {
                refuse(Refusal(
                    "BRIDGE_PUBLIC_PORT must differ from BRIDGE_PORT".into(),
                ));
            }
            Some(public)
        }
        None => None,
    };
    // The public listener may sit on a Docker bridge address so a proxy
    // container (Nginx Proxy Manager) can reach it; default: BRIDGE_BIND.
    let public_bind = match env
        .get("BRIDGE_PUBLIC_BIND")
        .filter(|v| !v.trim().is_empty())
    {
        Some(raw) => config::validate_public_bind(raw, &config::local_interfaces())
            .unwrap_or_else(|r| refuse(r)),
        None => bind,
    };

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
            .with_state(state.clone());
        let private = axum::serve(
            listener,
            app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(shutdown_signal());
        let Some(public_port) = public_port else {
            private.await.ok();
            return;
        };
        let public_address = SocketAddr::new(public_bind, public_port);
        let public_listener = tokio::net::TcpListener::bind(public_address)
            .await
            .unwrap_or_else(|e| {
                eprintln!("could not listen on {public_address}: {e}");
                std::process::exit(1);
            });
        tracing::info!("Public ingress (chat, sessions, runs only) on http://{public_address}");
        let public_app = axum::Router::new()
            .fallback(server::dispatch)
            .layer(axum::Extension(server::Ingress::Public))
            .with_state(state);
        let public = axum::serve(
            public_listener,
            public_app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(shutdown_signal());
        // Either listener ending (a signal) ends the bridge.
        tokio::select! {
            _ = private => {}
            _ = public => {}
        }
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
        let port = config::validate_port(&value("BRIDGE_PORT", "9842"))?;
        if let Some(raw) = env
            .get("BRIDGE_PUBLIC_PORT")
            .filter(|v| !v.trim().is_empty())
        {
            let public = config::validate_port(raw).map_err(|_| {
                Refusal("BRIDGE_PUBLIC_PORT must be a number between 1 and 65535".into())
            })?;
            if public == port {
                return Err(Refusal(
                    "BRIDGE_PUBLIC_PORT must differ from BRIDGE_PORT".into(),
                ));
            }
        }
        if let Some(raw) = env
            .get("BRIDGE_PUBLIC_BIND")
            .filter(|v| !v.trim().is_empty())
        {
            config::validate_public_bind(raw, &config::local_interfaces())?;
        }
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
