//! Random input against the parsers that read upstream, phone or operator
//! data. None of them may panic; the public route check must never allow a
//! path the dispatcher would hand to a private-only handler. Fixed seeds.

use crate::{config, core, files, history, routes, server, sse};
use bytes::Bytes;
use rand::{RngExt, SeedableRng, rngs::StdRng};
use serde_json::{Value, json};

const PIECES: &[&str] = &[
    "/",
    "//",
    "..",
    ".",
    "%2F",
    "%2e",
    "%00",
    "%",
    "%zz",
    "api",
    "sessions",
    "v1",
    "runs",
    "s1",
    "approval",
    "terminal",
    "files",
    "list",
    "content",
    "projects",
    "host",
    "metrics",
    "chat",
    "stream",
    "messages",
    "fork",
    "model",
    "devices",
    "self",
    "revoke",
    "pair",
    "server",
    "capabilities",
    "health",
    "{x}",
    "ä",
    "\u{0}",
    " ",
    "?",
    "#",
    "stop",
    "steer",
    "resolve-unknown",
];

const SSE_PIECES: &[&str] = &[
    "data: ",
    "event: ",
    "id: ",
    "retry: ",
    ": c",
    "\n",
    "\r",
    "\r\n",
    "\n\n",
    "{",
    "}",
    "\"",
    "run.started",
    "approval.request",
    "tool.progress",
    "assistant.commentary",
    "user_message",
    "{\"run_id\":\"r\",\"seq\":1}",
    "{\"user_message\":1}",
    "\u{feff}",
    "\u{0}",
    "ü",
];

fn pick<'a>(rng: &mut StdRng, pieces: &'a [&'a str]) -> &'a str {
    pieces[rng.random_range(0..pieces.len())]
}

fn text(rng: &mut StdRng, pieces: &[&str], max: usize) -> String {
    (0..rng.random_range(0..=max))
        .map(|_| {
            if rng.random_range(0..6) == 0 {
                char::from_u32(rng.random_range(0..0x11000)).map_or_else(String::new, String::from)
            } else {
                pick(rng, pieces).to_string()
            }
        })
        .collect()
}

fn value(rng: &mut StdRng, depth: u32) -> Value {
    match rng.random_range(0..if depth > 2 { 5 } else { 7 }) {
        0 => Value::Null,
        1 => json!(rng.random::<i64>()),
        2 => json!(rng.random::<bool>()),
        3 => json!(text(rng, SSE_PIECES, 4)),
        4 => json!(rng.random::<f64>()),
        5 => Value::Array(
            (0..rng.random_range(0..4))
                .map(|_| value(rng, depth + 1))
                .collect(),
        ),
        _ => {
            let keys = [
                "data",
                "role",
                "content",
                "type",
                "image_url",
                "url",
                "tool_calls",
                "reasoning",
                "x",
            ];
            Value::Object(
                (0..rng.random_range(0..5))
                    .map(|_| {
                        (
                            keys[rng.random_range(0..keys.len())].to_string(),
                            value(rng, depth + 1),
                        )
                    })
                    .collect(),
            )
        }
    }
}

#[test]
fn sse_decoding_and_rewrites_never_panic() {
    let mut rng = StdRng::seed_from_u64(1);
    for _ in 0..200_000 {
        let mut decoder = sse::Decoder::new(4096);
        for _ in 0..rng.random_range(1..5) {
            let chunk = text(&mut rng, SSE_PIECES, 30);
            let Ok(frames) = decoder.feed(chunk.as_bytes()) else {
                break;
            };
            for frame in frames {
                let frame = sse::strip_unused(sse::normalize_approval(sse::redact_echo(frame)));
                let _ = server::redact_master_key("contract-key", Bytes::from(frame.raw));
            }
        }
    }
}

#[test]
fn history_and_codes_never_panic() {
    let mut rng = StdRng::seed_from_u64(2);
    for _ in 0..200_000 {
        let page = value(&mut rng, 0);
        let _ = history::strip_history_images(Bytes::from(page.to_string()));
        let mut raw = page.to_string().into_bytes();
        if !raw.is_empty() {
            let cut = rng.random_range(0..raw.len());
            raw.truncate(cut);
        }
        let _ = history::strip_history_images(Bytes::from(raw));
        let _ = core::normalize_code(&value(&mut rng, 0));
        let _ = core::normalize_code(&json!(text(&mut rng, PIECES, 10)));
        let _ = config::parse_env_text(&text(
            &mut rng,
            &["A", "=", "\"", "'", " #", "\n", "export ", "#"],
            20,
        ));
    }
}

#[test]
fn routing_never_panics_and_public_paths_stay_public() {
    let mut rng = StdRng::seed_from_u64(3);
    let private_only = [
        core::TERMINAL_PATH,
        core::FILES_LIST_PATH,
        core::FILES_CONTENT_PATH,
        core::PROJECTS_PATH,
        "/v1/host/metrics",
        "/v1/devices/pair",
    ];
    let root = std::env::temp_dir();
    for _ in 0..300_000 {
        let raw = format!("/{}", text(&mut rng, PIECES, 8));
        let method = *["GET", "HEAD", "POST", "PATCH", "DELETE", "PUT"]
            .get(rng.random_range(0..6))
            .unwrap();
        let path = routes::decoded_path(&raw);
        let allowed = server::public_route_allowed(method, &path);
        if allowed {
            assert!(!private_only.contains(&path.as_str()), "{method} {path}");
            assert!(
                !(method == "POST" && path.ends_with("/approval")),
                "{method} {path}"
            );
        }
        let _ = routes::match_proxy_route(method, &path);
        let _ = files::resolve(&root, &text(&mut rng, PIECES, 6));
        let _ = files::realpath(std::path::Path::new(&raw));
    }
}
