//! The optional public ingress listener (Rust bridge only): the mTLS reverse
//! proxy forwards to it, and the bridge itself allows only chat, sessions and
//! runs there. Also the key-free server identity a phone pins.

use orsik_bridge_contract::*;
use serde_json::{Value, json};

fn rust_bridge_under_test() -> bool {
    std::env::var("BRIDGE_CMD").is_ok_and(|c| !c.trim().is_empty())
}

#[tokio::test]
async fn the_public_listener_reaches_only_chat_sessions_and_runs() {
    if !rust_bridge_under_test() {
        return;
    }
    let public_port = free_port();
    let s = Setup::with(|c| c.set("BRIDGE_PUBLIC_PORT", &public_port.to_string())).await;
    let (token, _) = s.bridge.pair().await;
    s.hermes.reply(
        "GET",
        "/api/sessions",
        Reply::json(200, json!({"data": []})),
    );
    let client = reqwest::Client::new();
    let public = |path: &str| format!("http://127.0.0.1:{public_port}{path}");

    let sessions = client
        .get(public("/api/sessions"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(sessions.status(), 200);
    let caps: Value = client
        .get(public("/v1/capabilities"))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(caps["features"]["terminal"], false);
    assert_eq!(caps["features"]["files"], false);

    for (method, path) in [
        ("POST", "/v1/devices/pair"),
        ("POST", "/v1/runs/r1/approval"),
        ("GET", "/v1/terminal"),
        ("GET", "/v1/files/list"),
        ("GET", "/v1/projects"),
        ("GET", "/v1/host/metrics"),
        ("GET", "/v1/admin"),
    ] {
        let response = client
            .request(method.parse().unwrap(), public(path))
            .bearer_auth(&token)
            .send()
            .await
            .unwrap();
        assert_eq!(
            error_code(response).await,
            (403, "public_ingress_denied".into()),
            "{method} {path}"
        );
    }
    // The private listener is unchanged.
    let pair = s
        .bridge
        .post("/v1/devices/pair", None)
        .json(&json!({"code": "x"}))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(pair).await, (400, "pairing_code_invalid".into()));
    assert!(s.hermes.requests_to("/v1/runs/r1/approval").is_empty());
}

#[tokio::test]
async fn the_server_identity_is_stable_and_needs_no_key() {
    if !rust_bridge_under_test() {
        return;
    }
    let s = Setup::new().await;
    let first: Value = s
        .bridge
        .get("/v1/server", None)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(first["object"], "orsik.server.identity");
    let id = first["server_id"].as_str().unwrap().to_string();
    assert_eq!(id.len(), 32);
    assert!(id.chars().all(|c| c.is_ascii_hexdigit()));
    let again: Value = s
        .bridge
        .get("/v1/server", None)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(again["server_id"], id.as_str());
    let query = s.bridge.get("/v1/server?x=1", None).send().await.unwrap();
    assert_eq!(error_code(query).await, (403, "query_not_allowed".into()));
}

#[tokio::test]
async fn an_invalid_public_port_is_refused() {
    if !rust_bridge_under_test() {
        return;
    }
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    let config = BridgeConfig::new(&hermes, home.path()).set("BRIDGE_PUBLIC_PORT", "70000");
    let exit = start_expecting_refusal(&config).await;
    assert_eq!(exit.code, Some(1));
    assert!(
        exit.stderr.contains("BRIDGE_PUBLIC_PORT must be a number"),
        "{}",
        exit.stderr
    );
}

#[tokio::test]
async fn a_public_bind_outside_loopback_and_docker_bridges_is_refused() {
    if !rust_bridge_under_test() {
        return;
    }
    for bind in ["0.0.0.0", "8.8.8.8", "localhost"] {
        let hermes = FakeHermes::start().await;
        let home = tempfile::tempdir().unwrap();
        let config = BridgeConfig::new(&hermes, home.path())
            .set("BRIDGE_PUBLIC_PORT", &free_port().to_string())
            .set("BRIDGE_PUBLIC_BIND", bind);
        let exit = start_expecting_refusal(&config).await;
        assert_eq!(exit.code, Some(1), "{bind}");
        assert!(
            exit.stderr.contains("BRIDGE_PUBLIC_BIND must be"),
            "{bind}: {}",
            exit.stderr
        );
    }
}
