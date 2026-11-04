//! The server-side chat stream takeover: relay, frame rewriting, re-attach
//! with after_seq and the fail-closed answers when a run cannot be replayed.

use std::time::Duration;

use bytes::Bytes;
use orsik_bridge_contract::*;
use serde_json::{Value, json};

const STREAM: &str = "/api/sessions/s1/chat/stream";

async fn paired() -> (Setup, String) {
    let s = Setup::new().await;
    let (token, _) = s.bridge.pair().await;
    (s, token)
}

fn data_of(text: &str, event: &str) -> Vec<Value> {
    // The JSON payloads of every frame with this event, in order.
    text.split("\n\n")
        .filter(|block| block.lines().any(|l| l == format!("event: {event}")))
        .filter_map(|block| block.lines().find_map(|l| l.strip_prefix("data: ")))
        .map(|data| serde_json::from_str(data).unwrap())
        .collect()
}

#[tokio::test]
async fn a_chat_stream_is_relayed_as_sse_with_the_master_key_upstream() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        STREAM,
        Reply::sse(&[
            &frame(
                "run.started",
                json!({"session_id": "s1", "run_id": "run_a", "seq": 0}),
            ),
            &frame(
                "assistant.delta",
                json!({"run_id": "run_a", "seq": 1, "delta": "Hallo"}),
            ),
            &frame("done", json!({"run_id": "run_a", "seq": 2})),
        ]),
    );
    let response = s
        .bridge
        .post(STREAM, Some(&token))
        .json(&json!({"message": "hi"}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(
        header(&response, "content-type").unwrap(),
        "text/event-stream"
    );
    assert_eq!(header(&response, "cache-control").unwrap(), "no-cache");
    let text = read_all(response).await;
    assert_eq!(data_of(&text, "assistant.delta")[0]["delta"], "Hallo");
    assert_eq!(data_of(&text, "done").len(), 1);

    let upstream = s.hermes.requests_to(STREAM);
    assert_eq!(upstream.len(), 1);
    assert_eq!(
        upstream[0].headers["authorization"],
        format!("Bearer {MASTER_KEY}")
    );
    assert_eq!(upstream[0].json(), json!({"message": "hi"}));
}

#[tokio::test]
async fn the_run_started_echo_and_unused_fields_are_removed() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        STREAM,
        Reply::sse(&[
            &frame("run.started", json!({"session_id": "s1", "run_id": "run_b", "seq": 0, "user_message": {"content": "big image"}})),
            &frame("tool.started", json!({"run_id": "run_b", "seq": 1, "tool_name": "terminal", "preview": "rm -rf", "args": {"cmd": "x"}})),
            &frame("tool.progress", json!({"run_id": "run_b", "seq": 2, "tool_name": "_thinking", "delta": "hmm"})),
            &frame("assistant.commentary", json!({"run_id": "run_b", "seq": 3, "text": "note"})),
            &frame("run.completed", json!({"run_id": "run_b", "seq": 4, "messages": [1], "usage": {}, "runtime": {}, "status": "completed"})),
            &frame("done", json!({"run_id": "run_b", "seq": 5})),
        ]),
    );
    let text = read_all(
        s.bridge
            .post(STREAM, Some(&token))
            .json(&json!({}))
            .send()
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(
        data_of(&text, "run.started")[0],
        json!({"session_id": "s1", "run_id": "run_b", "seq": 0})
    );
    assert_eq!(
        data_of(&text, "tool.started")[0],
        json!({"run_id": "run_b", "seq": 1, "tool_name": "terminal"})
    );
    assert_eq!(
        data_of(&text, "tool.progress")[0],
        json!({"run_id": "run_b", "seq": 2, "tool_name": "_thinking"})
    );
    assert_eq!(
        data_of(&text, "assistant.commentary")[0],
        json!({"run_id": "run_b", "seq": 3})
    );
    assert_eq!(
        data_of(&text, "run.completed")[0],
        json!({"run_id": "run_b", "seq": 4, "status": "completed"})
    );
    assert!(!text.contains("rm -rf") && !text.contains("big image"));
}

#[tokio::test]
async fn approval_requests_are_narrowed_to_mobile_v1() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        STREAM,
        Reply::sse(&[
            &frame(
                "run.started",
                json!({"session_id": "s1", "run_id": "run_c", "seq": 0}),
            ),
            &frame(
                "approval.request",
                json!({
                    "session_id": "s1", "run_id": "run_c", "seq": 1, "ts": 1.5, "request_id": "q1",
                    "command": "docker restart", "pattern_key": "docker", "permanent": true,
                    "description": "Core text", "choices": ["once", "session", "always", "deny"]
                }),
            ),
            &frame(
                "approval.request",
                json!({"run_id": "run_c", "seq": 2, "choices": ["always"]}),
            ),
            &frame("done", json!({"run_id": "run_c", "seq": 3})),
        ]),
    );
    let text = read_all(
        s.bridge
            .post(STREAM, Some(&token))
            .json(&json!({}))
            .send()
            .await
            .unwrap(),
    )
    .await;
    let approvals = data_of(&text, "approval.request");
    assert_eq!(
        approvals[0],
        json!({"session_id": "s1", "run_id": "run_c", "seq": 1, "ts": 1.5, "request_id": "q1",
               "description": "A dangerous action requires your approval.", "choices": ["once", "deny"]})
    );
    // A frame missing a v1 field is relayed unchanged (the client rejects it).
    assert_eq!(
        approvals[1],
        json!({"run_id": "run_c", "seq": 2, "choices": ["always"]})
    );
    assert!(!text.contains("docker restart"));
}

#[tokio::test]
async fn frames_without_rewrites_are_relayed_byte_for_byte() {
    let (s, token) = paired().await;
    let odd = "id: 7\nevent: assistant.delta\ndata: {\"run_id\":\"run_d\",\"seq\":1,  \"delta\":\"x\"}\n\n";
    s.hermes.reply(
        "POST",
        STREAM,
        Reply::sse(&[
            &frame(
                "run.started",
                json!({"session_id": "s1", "run_id": "run_d", "seq": 0}),
            ),
            odd,
            ": comment\n\n",
            "data: not json\n\n",
            &frame("done", json!({"run_id": "run_d", "seq": 3})),
        ]),
    );
    let text = read_all(
        s.bridge
            .post(STREAM, Some(&token))
            .json(&json!({}))
            .send()
            .await
            .unwrap(),
    )
    .await;
    assert!(text.contains(odd), "{text:?}");
    assert!(text.contains("data: not json\n\n"), "{text:?}");
}

#[tokio::test]
async fn an_upstream_refusal_is_relayed_with_its_status() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        STREAM,
        Reply::json(409, json!({"error": {"code": "session_busy"}})),
    );
    let response = s
        .bridge
        .post(STREAM, Some(&token))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 409);
    assert_eq!(
        response.json::<Value>().await.unwrap()["error"]["code"],
        "session_busy"
    );
}

#[tokio::test]
async fn an_upstream_redirect_on_the_chat_stream_fails_closed() {
    let (s, token) = paired().await;
    s.hermes
        .reply("POST", STREAM, Reply::redirect("http://evil.example/"));
    let response = s
        .bridge
        .post(STREAM, Some(&token))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(response).await,
        (502, "upstream_redirect".into())
    );
}

#[tokio::test]
async fn the_chat_stream_validates_key_session_and_query() {
    let (s, token) = paired().await;
    let anonymous = s
        .bridge
        .post(STREAM, None)
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(anonymous).await,
        (401, "invalid_credentials".into())
    );
    let query = s
        .bridge
        .post(&format!("{STREAM}?x=1"), Some(&token))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(query).await, (403, "query_not_allowed".into()));
    assert!(s.hermes.requests_to(STREAM).is_empty());
}

#[tokio::test]
async fn a_run_can_be_reattached_with_after_seq_and_follows_live() {
    let (s, token) = paired().await;
    let (reply, tx) = Reply::live();
    s.hermes.reply("POST", STREAM, reply);
    tx.send(Bytes::from(frame(
        "run.started",
        json!({"session_id": "s1", "run_id": "run_e", "seq": 0}),
    )))
    .unwrap();
    tx.send(Bytes::from(frame(
        "assistant.delta",
        json!({"run_id": "run_e", "seq": 1, "delta": "A"}),
    )))
    .unwrap();

    // The first client leaves early; the run keeps going on the bridge.
    let first = s
        .bridge
        .post(STREAM, Some(&token))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(first.status(), 200);
    drop(first);
    tokio::time::sleep(Duration::from_millis(300)).await;
    tx.send(Bytes::from(frame(
        "assistant.delta",
        json!({"run_id": "run_e", "seq": 2, "delta": "B"}),
    )))
    .unwrap();
    tokio::time::sleep(Duration::from_millis(200)).await;

    let reattach = s
        .bridge
        .get(
            "/api/sessions/s1/runs/run_e/stream?after_seq=0",
            Some(&token),
        )
        .send()
        .await
        .unwrap();
    assert_eq!(reattach.status(), 200);
    tx.send(Bytes::from(frame(
        "assistant.delta",
        json!({"run_id": "run_e", "seq": 3, "delta": "C"}),
    )))
    .unwrap();
    tx.send(Bytes::from(frame(
        "done",
        json!({"run_id": "run_e", "seq": 4}),
    )))
    .unwrap();
    drop(tx);
    let text = read_all(reattach).await;
    let deltas: Vec<String> = data_of(&text, "assistant.delta")
        .iter()
        .map(|d| d["delta"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(
        deltas,
        ["A", "B", "C"],
        "after_seq=0 replays seq 1.. then follows live"
    );
    assert!(
        data_of(&text, "run.started").is_empty(),
        "seq 0 is not replayed"
    );
    // The chat POST went upstream exactly once.
    assert_eq!(s.hermes.requests_to(STREAM).len(), 1);
}

#[tokio::test]
async fn reattach_after_the_end_replays_the_retained_run() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "POST",
        STREAM,
        Reply::sse(&[
            &frame(
                "run.started",
                json!({"session_id": "s1", "run_id": "run_f", "seq": 0}),
            ),
            &frame(
                "assistant.delta",
                json!({"run_id": "run_f", "seq": 1, "delta": "x"}),
            ),
            &frame("done", json!({"run_id": "run_f", "seq": 2})),
        ]),
    );
    let _ = read_all(
        s.bridge
            .post(STREAM, Some(&token))
            .json(&json!({}))
            .send()
            .await
            .unwrap(),
    )
    .await;
    let replay = s
        .bridge
        .get("/api/sessions/s1/runs/run_f/stream", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(replay.status(), 200);
    let text = read_all(replay).await;
    assert_eq!(
        data_of(&text, "run.started").len(),
        1,
        "no after_seq replays everything"
    );
    assert_eq!(data_of(&text, "done").len(), 1);
}

#[tokio::test]
async fn reattach_validates_its_input() {
    let (s, token) = paired().await;
    let anonymous = s
        .bridge
        .get("/api/sessions/s1/runs/r1/stream", None)
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(anonymous).await,
        (401, "invalid_credentials".into())
    );
    for after in ["x", "-2", "1.5"] {
        let bad = s
            .bridge
            .get(
                &format!("/api/sessions/s1/runs/r1/stream?after_seq={after}"),
                Some(&token),
            )
            .send()
            .await
            .unwrap();
        assert_eq!(
            error_code(bad).await,
            (400, "invalid_after_seq".into()),
            "after_seq={after}"
        );
    }
    let bad_id = s
        .bridge
        .get("/api/sessions/s1/runs/a%20b/stream", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(bad_id).await, (404, "run_not_found".into()));
}

#[tokio::test]
async fn an_unknown_run_is_answered_from_the_authoritative_upstream_status() {
    let (s, token) = paired().await;
    s.hermes.reply(
        "GET",
        "/v1/runs/known",
        Reply::json(200, json!({"status": "running"})),
    );
    s.hermes
        .reply("GET", "/v1/runs/gone", Reply::json(404, json!({})));
    s.hermes.reply(
        "GET",
        "/v1/runs/hop",
        Reply::redirect("http://evil.example/"),
    );
    s.hermes
        .reply("GET", "/v1/runs/broken", Reply::json(500, json!({})));
    for (run, expected) in [
        ("known", (409, "resume_gap")),
        ("gone", (404, "run_not_found")),
        ("hop", (502, "upstream_redirect")),
        ("broken", (502, "run_state_indeterminate")),
    ] {
        let response = s
            .bridge
            .get(&format!("/api/sessions/s1/runs/{run}/stream"), Some(&token))
            .send()
            .await
            .unwrap();
        assert_eq!(
            error_code(response).await,
            (expected.0, expected.1.into()),
            "run {run}"
        );
    }
    // A run of another session is not found for this one.
    s.hermes.reply(
        "POST",
        STREAM,
        Reply::sse(&[
            &frame(
                "run.started",
                json!({"session_id": "s1", "run_id": "run_g", "seq": 0}),
            ),
            &frame("done", json!({"run_id": "run_g", "seq": 1})),
        ]),
    );
    let _ = read_all(
        s.bridge
            .post(STREAM, Some(&token))
            .json(&json!({}))
            .send()
            .await
            .unwrap(),
    )
    .await;
    let other = s
        .bridge
        .get("/api/sessions/s2/runs/run_g/stream", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(other).await, (404, "run_not_found".into()));
}

#[tokio::test]
async fn an_oversized_frame_aborts_the_stream_instead_of_leaving_a_hole() {
    let (s, token) = paired().await;
    let huge = format!(
        "event: assistant.delta\ndata: {{\"run_id\":\"run_h\",\"seq\":1,\"delta\":\"{}\"}}\n\n",
        "x".repeat(1024 * 1024 + 10)
    );
    s.hermes.reply(
        "POST",
        STREAM,
        Reply::sse(&[
            &frame(
                "run.started",
                json!({"session_id": "s1", "run_id": "run_h", "seq": 0}),
            ),
            &huge,
            &frame("done", json!({"run_id": "run_h", "seq": 2})),
        ]),
    );
    let text = read_all(
        s.bridge
            .post(STREAM, Some(&token))
            .json(&json!({}))
            .send()
            .await
            .unwrap(),
    )
    .await;
    assert_eq!(data_of(&text, "run.started").len(), 1);
    assert!(data_of(&text, "assistant.delta").is_empty());
    assert!(
        data_of(&text, "done").is_empty(),
        "nothing after the oversized frame"
    );
    assert!(
        text.contains(": bridge_upstream_frame_too_large"),
        "{}",
        &text[..text.len().min(300)]
    );
}

#[tokio::test]
async fn a_revoked_device_loses_an_open_stream() {
    let (s, token) = paired().await;
    let (reply, tx) = Reply::live();
    s.hermes.reply("POST", STREAM, reply);
    tx.send(Bytes::from(frame(
        "run.started",
        json!({"session_id": "s1", "run_id": "run_i", "seq": 0}),
    )))
    .unwrap();
    let stream = s
        .bridge
        .post(STREAM, Some(&token))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(stream.status(), 200);
    let revoke = s
        .bridge
        .post("/v1/devices/self/revoke", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(revoke.status(), 200);
    // The bridge re-checks the key at least every 15 s; keep the run alive
    // meanwhile so only the revocation can end the client stream.
    let feeder = tokio::spawn(async move {
        for seq in 1..40 {
            let _ = tx.send(Bytes::from(frame(
                "assistant.delta",
                json!({"run_id": "run_i", "seq": seq, "delta": "."}),
            )));
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
    });
    let text = tokio::time::timeout(Duration::from_secs(19), read_all(stream))
        .await
        .expect("stream ends after revoke");
    feeder.abort();
    assert!(
        text.contains(": bridge_revoked"),
        "{}",
        &text[text.len().saturating_sub(200)..]
    );
}

#[tokio::test]
async fn a_cut_off_request_body_never_reaches_hermes() {
    use tokio::io::AsyncWriteExt;
    let (s, token) = paired().await;
    s.hermes.reply("POST", STREAM, Reply::sse(&[]));
    s.hermes
        .reply("DELETE", "/api/sessions/s1", Reply::json(200, json!({})));
    for (method, path) in [("POST", STREAM), ("DELETE", "/api/sessions/s1")] {
        let mut socket = tokio::net::TcpStream::connect(("127.0.0.1", s.bridge.port))
            .await
            .unwrap();
        let head = format!(
            "{method} {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {token}\r\n\
             Content-Type: application/json\r\nContent-Length: 100\r\n\r\n{{\"a\":"
        );
        socket.write_all(head.as_bytes()).await.unwrap();
        // The client goes away before the announced 100 bytes arrived.
        drop(socket);
    }
    tokio::time::sleep(Duration::from_millis(800)).await;
    assert!(
        s.hermes.requests_to(STREAM).is_empty(),
        "a cut-off chat body started a run"
    );
    assert!(
        s.hermes.requests_to("/api/sessions/s1").is_empty(),
        "a cut-off DELETE was forwarded"
    );
}
