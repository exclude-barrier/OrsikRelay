//! Local operator commands (`orsik-mobile-bridge admin …`). The operator
//! already has filesystem access to the store, which is the authorization
//! boundary; there is no HTTP admin surface.

use std::path::Path;

use clap::{Parser, Subcommand};

use crate::config::{self, Refusal};
use crate::core;
use crate::pairing;
use crate::store::Store;

#[derive(Parser)]
#[command(name = "orsik-mobile-bridge admin")]
struct Admin {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// issue a one-time pairing code
    PairingCode {
        /// HTTPS base URL for the orsik://pair deep link/QR
        #[arg(long = "base-url")]
        base_url: Option<String>,
    },
    /// list paired devices (no secrets)
    ListDevices,
    /// revoke a device by device_id
    RevokeDevice { device_id: String },
    /// allow shell access for one active device
    GrantTerminal { device_id: String },
    /// remove shell access for one active device
    RevokeTerminal { device_id: String },
    /// write an atomic snapshot of the store
    Backup { dest: String },
}

/// The same start-up checks as the serving bridge (minus bind/port/terminal),
/// then the store.
fn open_store(env: &config::Env) -> Result<Store, Refusal> {
    config::validate_upstream(
        env.get("BRIDGE_UPSTREAM")
            .map(String::as_str)
            .unwrap_or("http://127.0.0.1:8642"),
    )?;
    config::master_key(env)?;
    config::parse_services(env.get("BRIDGE_HOST_SERVICES"))?;
    config::files_root(env)?;
    Store::open(&config::state_db_path(env)).map_err(|e| Refusal(e.0))
}

pub fn main(args: Vec<String>) -> i32 {
    let admin = match Admin::try_parse_from(std::iter::once("admin".to_string()).chain(args)) {
        Ok(admin) => admin,
        Err(error) => {
            let _ = error.print();
            return error.exit_code();
        }
    };
    let env = match config::load_env() {
        Ok(env) => env,
        Err(Refusal(message)) => {
            eprintln!("{message}");
            return 1;
        }
    };
    let store = match open_store(&env) {
        Ok(store) => store,
        Err(Refusal(message)) => {
            eprintln!("{message}");
            return 1;
        }
    };
    match admin.command {
        Command::PairingCode { base_url } => {
            let base_url = base_url.unwrap_or_else(|| {
                env.get("BRIDGE_PUBLIC_BASE_URL")
                    .cloned()
                    .unwrap_or_default()
            });
            if !base_url.is_empty()
                && pairing::pairing_uri(&base_url, &"A".repeat(core::CODE_LENGTH)).is_none()
            {
                eprintln!(
                    "error: BRIDGE_PUBLIC_BASE_URL / --base-url must be https://<host> without user info"
                );
                return 1;
            }
            let (code, ttl) = match store.issue_code() {
                Ok(Some(issued)) => issued,
                Ok(None) => {
                    eprintln!("error: pairing-code capacity reached");
                    return 1;
                }
                Err(error) => {
                    eprintln!("error: {error}");
                    return 1;
                }
            };
            println!(
                "Pairing code: {}  (valid {ttl}s, single-use)",
                pairing::format_code(&code)
            );
            if base_url.is_empty() {
                println!(
                    "(set BRIDGE_PUBLIC_BASE_URL or --base-url to print the orsik://pair deep link/QR)"
                );
            } else {
                let uri = pairing::pairing_uri(&base_url, &code).expect("checked above");
                println!("Deep link:    {uri}");
                match pairing::render_ascii_qr(&uri) {
                    Some(qr) => println!("{qr}"),
                    None => println!(
                        "(install 'qrencode' for an ASCII QR; deep link above is equivalent)"
                    ),
                }
            }
            0
        }
        Command::ListDevices => match store.list_devices() {
            Ok(devices) => {
                for d in devices {
                    let flag = if d.revoked { "revoked" } else { "active" };
                    let terminal = if d.terminal_allowed {
                        "allowed"
                    } else {
                        "denied"
                    };
                    println!(
                        "{}  {flag:7}  {}  [{}]  terminal={terminal}",
                        d.device_id, d.label, d.profile
                    );
                }
                0
            }
            Err(error) => {
                eprintln!("error: {error}");
                1
            }
        },
        Command::GrantTerminal { device_id } => terminal_access(&store, &device_id, true),
        Command::RevokeTerminal { device_id } => terminal_access(&store, &device_id, false),
        Command::RevokeDevice { device_id } => match store.revoke_device_by_id(&device_id) {
            Ok(true) => {
                println!("revoked {device_id}");
                0
            }
            Ok(false) => {
                eprintln!("error: no such device_id");
                1
            }
            Err(error) => {
                eprintln!("error: {error}");
                1
            }
        },
        Command::Backup { dest } => match store.backup_to(Path::new(&dest)) {
            Ok(()) => {
                println!("backup written: {dest}");
                0
            }
            Err(error) => {
                eprintln!("error: {error}");
                1
            }
        },
    }
}

fn terminal_access(store: &Store, device_id: &str, allowed: bool) -> i32 {
    match store.set_terminal_access(device_id, allowed) {
        Ok(true) => {
            println!(
                "terminal {} for {device_id}",
                if allowed { "allowed" } else { "denied" }
            );
            0
        }
        Ok(false) => {
            eprintln!("error: no active device with this device_id");
            1
        }
        Err(_) => {
            eprintln!("error: device store unavailable");
            1
        }
    }
}
