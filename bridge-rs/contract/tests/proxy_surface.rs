//! The closed proxy surface: route and query allowlists, redirects, key
//! handling, history slimming and the strictly validated write routes.

use orsik_bridge_contract::*;
use serde_json::{Value, json};

async fn paired() -> (Setup, String) {
    let s = Setup::new().await;
    let (token, _) = s.bridge.pair().await;
    (s, token)
}

#[tokio::test]
async fn every_proxied_route_needs_a_device_key() {
    let s = Setup::new().await;
    for (method, path) in [
        ("GET", "/api/sessions"),
        ("POST", "/api/sessions"),
        ("GET", "/api/sessions/s1/messages"),
        ("GET", "/v1/runs/r1"),
        ("POST", "/v1/runs/r1/stop"),
        ("GET", "/v1/host/metrics"),
        ("GET", "/api/admin/anything"),
    ] {
        let response = s.bridge.request(method, path, None).send().await.unwrap();
        assert_eq!(
            error_code(response).await,
            (401, "invalid_credentials".into()),
            "{method} {path}"
        );
    }
    assert!(s.hermes.recorded().is_empty(), "nothing reached Hermes");
}

#[tokio::test]
async fn the_allowlisted_reads_are_forwarded_with_the_master_key_only_upstream() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/api/sessions",
        Reply::json(200, json!({"data": [], "has_more": false})),
    );
    s.hermes.reply(
        "GET",
        "/api/sessions/s-1",
        Reply::json(200, json!({"id": "s-1"})),
    );
    s.hermes.reply(
        "GET",
        "/v1/runs/run_1",
        Reply::json(200, json!({"status": "running"})),
    );
    s.hermes.reply(
        "GET",
        "/api/model/options",
        Reply::json(200, json!({"providers": []})),
    );
    for path in [
        "/api/sessions",
        "/api/sessions/s-1",
        "/v1/runs/run_1",
        "/api/model/options",
    ] {
        let response = s.bridge.get(path, Some(&token)).send().await.unwrap();
        assert_eq!(response.status(), 200, "{path}");
        let upstream = s.hermes.requests_to(path);
        assert_eq!(upstream.len(), 1, "{path}");
        assert_eq!(
            upstream[0].headers["authorization"],
            format!("Bearer {MASTER_KEY}")
        );
        assert!(
            !upstream[0]
                .headers
                .get("authorization")
                .unwrap()
                .to_str()
                .unwrap()
                .contains(&token)
        );
    }
}

#[tokio::test]
async fn paths_outside_the_allowlist_never_reach_hermes() {
    let (s, token) = paired().await;
    for (method, path) in [
        ("GET", "/api/jobs"),
        ("GET", "/api/admin/config"),
        ("GET", "/v1/skills"),
        ("GET", "/v1/models"),
        ("POST", "/v1/chat/completions"),
        ("DELETE", "/v1/runs/r1"),
        ("PUT", "/api/sessions/s1"),
        ("GET", "/api/sessions/s1/children"),
        ("GET", "/api/sessions/"),
        ("GET", "/api/sessions/s1/"),
        ("POST", "/v1/runs/r1/stop/"),
        ("GET", "/api/sessions/s%2F1"),
        ("GET", "/api/sessions/.."),
        ("GET", "/api/sessions/a%20b"),
    ] {
        let response = s
            .bridge
            .request(method, path, Some(&token))
            .send()
            .await
            .unwrap();
        assert_eq!(
            error_code(response).await,
            (403, "endpoint_out_of_scope".into()),
            "{method} {path}"
        );
    }
    assert!(s.hermes.recorded().is_empty(), "{:?}", s.hermes.recorded());
}

#[tokio::test]
async fn only_the_session_read_query_keys_are_forwarded() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/api/sessions",
        Reply::json(200, json!({"data": []})),
    );
    let ok = s
        .bridge
        .get(
            "/api/sessions?limit=20&offset=0&order=desc&include_children=false",
            Some(&token),
        )
        .send()
        .await
        .unwrap();
    assert_eq!(ok.status(), 200);
    let forwarded = s.hermes.requests_to("/api/sessions");
    assert_eq!(
        forwarded[0].query.as_deref(),
        Some("limit=20&offset=0&order=desc&include_children=false")
    );

    let unknown = s
        .bridge
        .get("/api/sessions?token=x", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(unknown).await, (403, "query_not_allowed".into()));
    s.hermes
        .reply("GET", "/v1/runs/r1", Reply::json(200, json!({})));
    let on_run = s
        .bridge
        .get("/v1/runs/r1?limit=1", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(on_run).await, (403, "query_not_allowed".into()));
    assert_eq!(s.hermes.requests_to("/api/sessions").len(), 1);
    assert!(s.hermes.requests_to("/v1/runs/r1").is_empty());
}

#[tokio::test]
async fn an_upstream_redirect_is_never_followed() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/api/sessions",
        Reply::redirect("http://evil.example/steal"),
    );
    let response = s
        .bridge
        .get("/api/sessions", Some(&token))
        .send()
        .await
        .unwrap();
    assert!(response.headers().get("location").is_none());
    assert_eq!(
        error_code(response).await,
        (502, "upstream_redirect".into())
    );
}

#[tokio::test]
async fn an_unreachable_upstream_fails_closed() {
    let s = Setup::with(|config| config.set("BRIDGE_UPSTREAM", "http://127.0.0.1:9")).await;
    let (token, _) = s.bridge.pair().await;
    let response = s
        .bridge
        .get("/api/sessions", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(response).await,
        (502, "upstream_unavailable".into())
    );
}

#[tokio::test]
async fn a_reflected_master_key_is_redacted_in_buffered_bodies() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/api/sessions/s1",
        Reply::json(200, json!({"echo": format!("Bearer {MASTER_KEY}")})),
    );
    let response = s
        .bridge
        .get("/api/sessions/s1", Some(&token))
        .send()
        .await
        .unwrap();
    let text = response.text().await.unwrap();
    assert!(!text.contains(MASTER_KEY), "{text}");
    assert!(text.contains("[redacted]"), "{text}");
}

#[tokio::test]
async fn upstream_errors_are_relayed_with_their_status() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/api/sessions/missing",
        Reply::json(404, json!({"error": {"code": "not_found"}})),
    );
    let response = s
        .bridge
        .get("/api/sessions/missing", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 404);
    assert_eq!(
        response.json::<Value>().await.unwrap()["error"]["code"],
        "not_found"
    );
}

#[tokio::test]
async fn history_pages_lose_image_data_tool_output_and_reasoning() {
    let (s, token) = paired().await;
    let page = json!({"data": [
        {"role": "user", "content": [
            {"type": "text", "text": "look"},
            {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}}
        ]},
        {"role": "user", "content": "Was ist das?\n\n[Image attached: /home/h/.hermes/img/a.png]\n[screenshot]"},
        {"role": "user", "content": "What do you see in this image?\n\n[Image attached at: https://x/y.png]"},
        {"role": "assistant", "content": "ok", "tool_calls": [{"id": "1"}], "reasoning": "r", "reasoning_content": "rc"},
        {"role": "tool", "content": "secret output", "tool_name": "terminal"},
        {"role": "user", "content": "plain text stays"}
    ]});
    s.hermes
        .reply("GET", "/api/sessions/s1/messages", Reply::json(200, page));
    let response = s
        .bridge
        .get("/api/sessions/s1/messages", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let body: Value = response.json().await.unwrap();
    let data = body["data"].as_array().unwrap();
    assert_eq!(
        data[0]["content"],
        json!([{"type": "text", "text": "look"}, {"type": "image_url"}])
    );
    assert_eq!(
        data[1]["content"],
        json!([{"type": "text", "text": "Was ist das?"}, {"type": "image_url"}])
    );
    assert_eq!(data[2]["content"], json!([{"type": "image_url"}]));
    assert_eq!(data[3], json!({"role": "assistant", "content": "ok"}));
    assert_eq!(data[4]["content"], "");
    assert_eq!(data[4]["tool_name"], "terminal");
    assert_eq!(data[5]["content"], "plain text stays");
}

#[tokio::test]
async fn approvals_accept_only_once_or_deny_and_forward_a_canonical_body() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/v1/runs/run_1",
        Reply::json(200, json!({"status": "running", "execution_generation": 3})),
    );
    s.hermes.reply(
        "POST",
        "/v1/runs/run_1/approval",
        Reply::json(200, json!({"ok": true})),
    );
    for (body, code) in [
        (
            json!({"choice": "always", "request_id": "q1"}),
            "invalid_choice",
        ),
        (
            json!({"choice": "session", "request_id": "q1"}),
            "invalid_choice",
        ),
        (json!({"choice": "once"}), "invalid_fields"),
        (
            json!({"choice": "once", "request_id": "q1", "extra": 1}),
            "invalid_fields",
        ),
        (
            json!({"choice": "once", "request_id": " "}),
            "invalid_request_id",
        ),
        (
            json!({"choice": "once", "request_id": "a\u{0007}b"}),
            "invalid_request_id",
        ),
        (
            json!({"choice": "once", "request_id": 7}),
            "invalid_request_id",
        ),
        (json!(["once"]), "invalid_json"),
    ] {
        let response = s
            .bridge
            .post("/v1/runs/run_1/approval", Some(&token))
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(error_code(response).await, (400, code.into()), "{body}");
    }
    let broken = s
        .bridge
        .post("/v1/runs/run_1/approval", Some(&token))
        .header("Content-Type", "application/json")
        .body("{")
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(broken).await, (400, "invalid_json".into()));
    let bad_run = s
        .bridge
        .post("/v1/runs/bad%20id/approval", Some(&token))
        .json(&json!({"choice": "once", "request_id": "q1"}))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(bad_run).await, (400, "invalid_run_id".into()));
    assert!(
        s.hermes.requests_to("/v1/runs/run_1/approval").is_empty(),
        "no rejected choice reached Hermes"
    );

    let ok = s
        .bridge
        .post("/v1/runs/run_1/approval", Some(&token))
        .json(&json!({"request_id": "q1", "choice": "deny"}))
        .send()
        .await
        .unwrap();
    assert_eq!(ok.status(), 200);
    let forwarded = s.hermes.requests_to("/v1/runs/run_1/approval");
    assert_eq!(forwarded.len(), 1);
    assert_eq!(
        forwarded[0].json(),
        json!({"choice": "deny", "request_id": "q1", "execution_generation": 3})
    );
}

#[tokio::test]
async fn an_approval_without_a_readable_generation_is_forwarded_plain() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/v1/runs/run_2",
        Reply::json(
            200,
            json!({"status": "running", "execution_generation": true}),
        ),
    );
    s.hermes.reply(
        "POST",
        "/v1/runs/run_2/approval",
        Reply::json(200, json!({})),
    );
    let ok = s
        .bridge
        .post("/v1/runs/run_2/approval", Some(&token))
        .json(&json!({"choice": "once", "request_id": "q2"}))
        .send()
        .await
        .unwrap();
    assert_eq!(ok.status(), 200);
    assert_eq!(
        s.hermes.requests_to("/v1/runs/run_2/approval")[0].json(),
        json!({"choice": "once", "request_id": "q2"})
    );
}

#[tokio::test]
async fn session_patch_takes_only_one_archived_flag_or_one_bounded_title() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "PATCH",
        "/api/sessions/s1",
        Reply::json(200, json!({"ok": true})),
    );
    for (body, code) in [
        (json!({"archived": true, "title": "x"}), "invalid_fields"),
        (json!({"pinned": true}), "invalid_fields"),
        (json!({"archived": "yes"}), "invalid_archived"),
        (json!({"title": "  "}), "invalid_title"),
        (json!({"title": "a".repeat(513)}), "invalid_title"),
        (json!({"title": "a\nb"}), "invalid_title"),
        (json!({"title": "a\u{0085}b"}), "invalid_title"),
        (json!("x"), "invalid_json"),
    ] {
        let response = s
            .bridge
            .request("PATCH", "/api/sessions/s1", Some(&token))
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(error_code(response).await, (400, code.into()), "{body}");
    }
    assert!(s.hermes.requests_to("/api/sessions/s1").is_empty());
    for body in [json!({"archived": true}), json!({"title": "Neuer Titel"})] {
        let response = s
            .bridge
            .request("PATCH", "/api/sessions/s1", Some(&token))
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 200);
    }
    let forwarded = s.hermes.requests_to("/api/sessions/s1");
    assert_eq!(forwarded[0].json(), json!({"archived": true}));
    assert_eq!(forwarded[1].json(), json!({"title": "Neuer Titel"}));
}

#[tokio::test]
async fn session_delete_carries_no_body() {
    let (s, token) = paired().await;
    s.hermes
        .reply("DELETE", "/api/sessions/s1", Reply::status(204));
    let with_body = s
        .bridge
        .request("DELETE", "/api/sessions/s1", Some(&token))
        .body("{}")
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(with_body).await,
        (400, "body_not_allowed".into())
    );
    let ok = s
        .bridge
        .request("DELETE", "/api/sessions/s1", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(ok.status(), 204);
    let forwarded = s.hermes.requests_to("/api/sessions/s1");
    assert_eq!(forwarded.len(), 1);
    assert!(forwarded[0].body.is_empty());
}

#[tokio::test]
async fn fork_takes_an_empty_object_and_model_a_bounded_provider_and_model() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        "/api/sessions/s1/fork",
        Reply::json(201, json!({"object": "hermes.session"})),
    );
    s.hermes.reply(
        "POST",
        "/api/sessions/s1/model",
        Reply::json(200, json!({"ok": true})),
    );
    let fork_extra = s
        .bridge
        .post("/api/sessions/s1/fork", Some(&token))
        .json(&json!({"x": 1}))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(fork_extra).await, (400, "invalid_fields".into()));
    let fork_bad = s
        .bridge
        .post("/api/sessions/s1/fork", Some(&token))
        .body("{")
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(fork_bad).await, (400, "invalid_json".into()));
    let fork = s
        .bridge
        .post("/api/sessions/s1/fork", Some(&token))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(fork.status(), 201);
    assert_eq!(
        s.hermes.requests_to("/api/sessions/s1/fork")[0].json(),
        json!({})
    );

    for (body, code) in [
        (json!({"provider": "anthropic"}), "invalid_fields"),
        (
            json!({"provider": "anth ropic", "model": "m"}),
            "invalid_provider",
        ),
        (
            json!({"provider": "a".repeat(81), "model": "m"}),
            "invalid_provider",
        ),
        (
            json!({"provider": "anthropic", "model": ""}),
            "invalid_model",
        ),
        (
            json!({"provider": "anthropic", "model": "a b"}),
            "invalid_model",
        ),
        (
            json!({"provider": "anthropic", "model": "a::b"}),
            "invalid_model",
        ),
        (
            json!({"provider": "anthropic", "model": "m".repeat(201)}),
            "invalid_model",
        ),
    ] {
        let response = s
            .bridge
            .post("/api/sessions/s1/model", Some(&token))
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(error_code(response).await, (400, code.into()), "{body}");
    }
    let too_big = s
        .bridge
        .post("/api/sessions/s1/model", Some(&token))
        .header("Content-Type", "application/json")
        .body(format!(
            "{{\"provider\":\"a\",\"model\":\"{}\"}}",
            "m".repeat(5000)
        ))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(too_big).await, (413, "body_too_large".into()));
    let ok = s
        .bridge
        .post("/api/sessions/s1/model", Some(&token))
        .json(&json!({"provider": "anthropic", "model": "claude-opus-5-5"}))
        .send()
        .await
        .unwrap();
    assert_eq!(ok.status(), 200);
    let forwarded = s.hermes.requests_to("/api/sessions/s1/model");
    assert_eq!(forwarded.len(), 1);
    assert_eq!(
        forwarded[0].json(),
        json!({"provider": "anthropic", "model": "claude-opus-5-5"})
    );
}

#[tokio::test]
async fn model_options_take_no_body() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/api/model/options",
        Reply::json(200, json!({"providers": []})),
    );
    let with_body = s
        .bridge
        .get("/api/model/options", Some(&token))
        .body("x")
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(with_body).await,
        (400, "body_not_allowed".into())
    );
    assert!(s.hermes.requests_to("/api/model/options").is_empty());
}

#[tokio::test]
async fn resolve_unknown_takes_exactly_the_admission_and_generation() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        "/v1/runs/r1/resolve-unknown",
        Reply::json(200, json!({"status": "cancelled"})),
    );
    for (body, code) in [
        (json!({"admission_id": "a1"}), "invalid_fields"),
        (
            json!({"admission_id": "a1", "execution_generation": 1, "x": 1}),
            "invalid_fields",
        ),
        (
            json!({"admission_id": " ", "execution_generation": 1}),
            "invalid_admission_id",
        ),
        (
            json!({"admission_id": "a1", "execution_generation": -1}),
            "invalid_generation",
        ),
        (
            json!({"admission_id": "a1", "execution_generation": true}),
            "invalid_generation",
        ),
        (
            json!({"admission_id": "a1", "execution_generation": 1.5}),
            "invalid_generation",
        ),
    ] {
        let response = s
            .bridge
            .post("/v1/runs/r1/resolve-unknown", Some(&token))
            .json(&body)
            .send()
            .await
            .unwrap();
        assert_eq!(error_code(response).await, (400, code.into()), "{body}");
    }
    let ok = s
        .bridge
        .post("/v1/runs/r1/resolve-unknown", Some(&token))
        .json(&json!({"execution_generation": 2, "admission_id": "a1"}))
        .send()
        .await
        .unwrap();
    assert_eq!(ok.status(), 200);
    let forwarded = s.hermes.requests_to("/v1/runs/r1/resolve-unknown");
    assert_eq!(forwarded.len(), 1);
    assert_eq!(
        forwarded[0].json(),
        json!({"admission_id": "a1", "execution_generation": 2})
    );
}

#[tokio::test]
async fn stop_and_steer_are_forwarded() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        "/v1/runs/r1/stop",
        Reply::json(200, json!({"status": "stopping"})),
    );
    s.hermes.reply(
        "POST",
        "/v1/runs/r1/steer",
        Reply::json(202, json!({"status": "queued"})),
    );
    let stop = s
        .bridge
        .post("/v1/runs/r1/stop", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(stop.status(), 200);
    let steer = s
        .bridge
        .post("/v1/runs/r1/steer", Some(&token))
        .json(&json!({"text": "kürzer"}))
        .send()
        .await
        .unwrap();
    assert_eq!(steer.status(), 202);
    assert_eq!(
        s.hermes.requests_to("/v1/runs/r1/steer")[0].json(),
        json!({"text": "kürzer"})
    );
}

#[tokio::test]
async fn session_create_is_forwarded() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        "/api/sessions",
        Reply::json(201, json!({"id": "new"})),
    );
    let response = s
        .bridge
        .post("/api/sessions", Some(&token))
        .json(&json!({"title": "x"}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 201);
    assert_eq!(
        s.hermes.requests_to("/api/sessions")[0].json(),
        json!({"title": "x"})
    );
}

#[tokio::test]
async fn a_body_over_ten_megabytes_is_refused() {
    let (s, token) = paired().await;
    s.hermes
        .reply("POST", "/api/sessions", Reply::json(201, json!({})));
    let response = s
        .bridge
        .post("/api/sessions", Some(&token))
        .header("Content-Type", "application/json")
        .body(vec![b' '; 10_000_001])
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 413);
    assert!(s.hermes.requests_to("/api/sessions").is_empty());
}
