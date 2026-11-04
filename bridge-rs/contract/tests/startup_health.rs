//! Start-up refusals, /health and /v1/capabilities.

use std::os::unix::fs::PermissionsExt;

use orsik_bridge_contract::*;
use serde_json::json;

#[tokio::test]
async fn health_is_proxied_without_a_device_key_and_without_the_master_key_leaking() {
    let s = Setup::new().await;
    s.hermes.reply(
        "GET",
        "/health",
        Reply::json(200, json!({"status": "ok", "platform": "hermes-agent"})),
    );

    let response = s.bridge.get("/health", None).send().await.unwrap();
    assert_eq!(response.status(), 200);
    let body: serde_json::Value = response.json().await.unwrap();
    assert_eq!(body["status"], "ok");

    let upstream = s.hermes.requests_to("/health");
    assert_eq!(upstream.len(), 1);
    assert_eq!(
        upstream[0].headers["authorization"],
        format!("Bearer {MASTER_KEY}")
    );
}

#[tokio::test]
async fn health_refuses_a_request_body() {
    let s = Setup::new().await;
    let response = s
        .bridge
        .get("/health", None)
        .body("x")
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(response).await, (400, "body_not_allowed".into()));
    assert!(s.hermes.requests_to("/health").is_empty());
}

#[tokio::test]
async fn capabilities_need_a_device_key() {
    let s = Setup::new().await;
    let anonymous = s.bridge.get("/v1/capabilities", None).send().await.unwrap();
    assert_eq!(
        error_code(anonymous).await,
        (401, "invalid_credentials".into())
    );
    let wrong = s
        .bridge
        .get("/v1/capabilities", Some("hd_not-a-real-key-000000000000"))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(wrong).await, (401, "invalid_credentials".into()));
}

#[tokio::test]
async fn capabilities_have_the_exact_mobile_v1_shape() {
    let s = Setup::new().await;
    let (token, _) = s.bridge.pair().await;
    let response = s
        .bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let caps: serde_json::Value = response.json().await.unwrap();

    assert_eq!(caps["object"], "hermes.api_server.capabilities");
    assert_eq!(caps["platform"], "hermes-agent");
    assert_eq!(caps["auth"], json!({"type": "bearer", "required": true}));
    assert_eq!(
        caps["runtime"],
        json!({"mode": "server_agent", "tool_execution": "server", "bridge": "orsik-mobile-bridge", "core": "official-hermes"})
    );
    let features = &caps["features"];
    for flag in [
        "session_resources",
        "session_chat_streaming",
        "run_approval_response",
        "run_status",
        "run_stop",
        "run_steer",
        "run_unknown_resolution",
        "host_metrics",
        "session_archive",
        "session_title",
        "session_fork",
        "session_model_lock",
        "session_delete",
    ] {
        assert_eq!(features[flag], true, "feature {flag}");
    }
    // Off by default: the terminal and the read-only files/projects.
    assert_eq!(features["terminal"], false);
    assert_eq!(features["files"], false);
    assert_eq!(features["projects"], false);
    assert_eq!(
        features["session_chat_interactive_approvals"],
        json!({"enabled": true, "protocol_version": 1, "event": "approval.request",
               "choices": ["once", "deny"], "response_endpoint": "run_approval"})
    );
    assert_eq!(
        features["device_credentials"],
        json!({"enabled": true, "protocol_version": 1, "pair_endpoint": "device_pair",
               "self_revoke_endpoint": "device_self_revoke"})
    );
    let endpoints = caps["endpoints"].as_object().unwrap();
    let expected = [
        ("health", "GET", "/health"),
        ("sessions", "GET", "/api/sessions"),
        ("session_create", "POST", "/api/sessions"),
        ("session_detail", "GET", "/api/sessions/{session_id}"),
        (
            "session_messages",
            "GET",
            "/api/sessions/{session_id}/messages",
        ),
        (
            "session_chat_stream",
            "POST",
            "/api/sessions/{session_id}/chat/stream",
        ),
        (
            "run_unknown_resolution",
            "POST",
            "/v1/runs/{run_id}/resolve-unknown",
        ),
        ("host_metrics", "GET", "/v1/host/metrics"),
        ("session_update", "PATCH", "/api/sessions/{session_id}"),
        ("session_fork", "POST", "/api/sessions/{session_id}/fork"),
        (
            "session_model_lock",
            "POST",
            "/api/sessions/{session_id}/model",
        ),
        ("model_options", "GET", "/api/model/options"),
        ("session_delete", "DELETE", "/api/sessions/{session_id}"),
        ("run_status", "GET", "/v1/runs/{run_id}"),
        ("run_approval", "POST", "/v1/runs/{run_id}/approval"),
        ("run_stop", "POST", "/v1/runs/{run_id}/stop"),
        ("run_steer", "POST", "/v1/runs/{run_id}/steer"),
        ("device_pair", "POST", "/v1/devices/pair"),
        ("device_self_revoke", "POST", "/v1/devices/self/revoke"),
    ];
    assert_eq!(
        endpoints.len(),
        expected.len(),
        "no extra endpoints without terminal/files"
    );
    for (name, method, path) in expected {
        assert_eq!(
            endpoints[name],
            json!({"method": method, "path": path}),
            "endpoint {name}"
        );
    }
    // Capabilities are generated by the bridge, never asked from Hermes.
    assert!(s.hermes.requests_to("/v1/capabilities").is_empty());
}

#[tokio::test]
async fn a_non_loopback_bind_is_refused() {
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    for bind in ["0.0.0.0", "localhost", "192.168.1.10"] {
        let config = BridgeConfig::new(&hermes, home.path()).set("BRIDGE_BIND", bind);
        let exit = start_expecting_refusal(&config).await;
        assert_ne!(exit.code, Some(0), "bind {bind} must be refused");
        assert!(
            exit.stderr.contains("BRIDGE_BIND"),
            "bind {bind}: {}",
            exit.stderr
        );
    }
}

#[tokio::test]
async fn an_upstream_off_loopback_or_with_extras_is_refused() {
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    for upstream in [
        "http://example.com:8642",
        "https://127.0.0.1:8642",
        "http://127.0.0.1",
        "http://user:pw@127.0.0.1:8642",
        "http://127.0.0.1:8642/api",
        "http://10.0.0.1:8642",
    ] {
        let config = BridgeConfig::new(&hermes, home.path()).set("BRIDGE_UPSTREAM", upstream);
        let exit = start_expecting_refusal(&config).await;
        assert_ne!(exit.code, Some(0), "upstream {upstream} must be refused");
        assert!(
            exit.stderr.contains("BRIDGE_UPSTREAM"),
            "upstream {upstream}: {}",
            exit.stderr
        );
        assert!(
            !exit.stderr.contains("user:pw"),
            "the upstream value is never echoed"
        );
    }
}

#[tokio::test]
async fn the_bridge_refuses_to_start_without_the_hermes_key() {
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    let config = BridgeConfig::new(&hermes, home.path()).unset("HERMES_API_KEY");
    let exit = start_expecting_refusal(&config).await;
    assert_ne!(exit.code, Some(0));
}

#[tokio::test]
async fn the_hermes_env_file_must_be_private() {
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    let env_file = home.path().join("hermes.env");
    std::fs::write(&env_file, format!("API_SERVER_KEY={MASTER_KEY}\n")).unwrap();
    std::fs::set_permissions(&env_file, std::fs::Permissions::from_mode(0o644)).unwrap();
    let config = BridgeConfig::new(&hermes, home.path())
        .unset("HERMES_API_KEY")
        .set("BRIDGE_HERMES_ENV", &env_file.display().to_string());
    let exit = start_expecting_refusal(&config).await;
    assert_ne!(exit.code, Some(0));
    assert!(exit.stderr.contains("0600"), "{}", exit.stderr);
}

#[tokio::test]
async fn the_key_from_a_private_hermes_env_file_is_used() {
    let s = Setup::with(|config| {
        let env_file = config.env["BRIDGE_HOME"].clone() + "/hermes.env";
        std::fs::write(
            &env_file,
            format!("# comment\nexport API_SERVER_KEY=\"{MASTER_KEY}\"\n"),
        )
        .unwrap();
        std::fs::set_permissions(&env_file, std::fs::Permissions::from_mode(0o600)).unwrap();
        config
            .unset("HERMES_API_KEY")
            .set("BRIDGE_HERMES_ENV", &env_file)
    })
    .await;
    let response = s.bridge.get("/health", None).send().await.unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(
        s.hermes.requests_to("/health")[0].headers["authorization"],
        format!("Bearer {MASTER_KEY}")
    );
}
