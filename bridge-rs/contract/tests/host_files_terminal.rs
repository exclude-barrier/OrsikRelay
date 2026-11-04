//! Host metrics, the read-only files/projects and the terminal WebSocket.

use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use orsik_bridge_contract::*;
use serde_json::{Value, json};
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

async fn paired_with(customize: impl FnOnce(BridgeConfig) -> BridgeConfig) -> (Setup, String) {
    let s = Setup::with(customize).await;
    let (token, _) = s.bridge.pair().await;
    (s, token)
}

#[tokio::test]
async fn host_metrics_have_the_documented_shape() {
    let (s, token) = paired_with(|c| c).await;
    let response = s
        .bridge
        .get("/v1/host/metrics", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["object"], "orsik.host.metrics");
    assert!(body["cpu"]["percent"].is_number() || body["cpu"]["percent"].is_null());
    assert!(body["cpu"]["cores"].is_number());
    let memory = &body["memory"];
    assert!(memory["total_bytes"].as_u64().unwrap() >= memory["used_bytes"].as_u64().unwrap());
    assert!(body["gpu"].is_null() || body["gpu"]["name"].is_string());
    // An unknown unit is "unknown" (null), never a false "stopped".
    assert_eq!(
        body["services"],
        json!([{"name": "contract-test-unit", "active": null}])
    );
    assert!(body["collected_at"].is_number());
    assert!(
        s.hermes
            .recorded()
            .iter()
            .all(|r| r.path == "/health" || r.path.is_empty())
    );
}

#[tokio::test]
async fn host_metrics_refuse_queries_and_bodies() {
    let (s, token) = paired_with(|c| c).await;
    let query = s
        .bridge
        .get("/v1/host/metrics?x=1", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(query).await, (403, "query_not_allowed".into()));
    let body = s
        .bridge
        .get("/v1/host/metrics", Some(&token))
        .body("x")
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(body).await, (400, "body_not_allowed".into()));
}

#[tokio::test]
async fn an_unsafe_service_list_stops_the_bridge() {
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    for services in ["a;b", "../x", "a,b,c,d,e,f,g,h,i"] {
        let config = BridgeConfig::new(&hermes, home.path()).set("BRIDGE_HOST_SERVICES", services);
        let exit = start_expecting_refusal(&config).await;
        assert_ne!(exit.code, Some(0), "{services}");
        assert!(
            exit.stderr.contains("BRIDGE_HOST_SERVICES"),
            "{}",
            exit.stderr
        );
    }
}

#[tokio::test]
async fn files_and_projects_are_off_by_default() {
    let (s, token) = paired_with(|c| c).await;
    for path in ["/v1/files/list", "/v1/files/content?path=a", "/v1/projects"] {
        let response = s.bridge.get(path, Some(&token)).send().await.unwrap();
        assert_eq!(
            error_code(response).await,
            (403, "endpoint_out_of_scope".into()),
            "{path}"
        );
    }
}

fn files_root() -> tempfile::TempDir {
    let root = tempfile::tempdir().unwrap();
    let p = root.path();
    std::fs::create_dir_all(p.join("docs/sub")).unwrap();
    std::fs::write(p.join("docs/readme.md"), "# Hallo\n").unwrap();
    std::fs::write(p.join("docs/bild.png"), [0x89, b'P', b'N', b'G', 0, 1]).unwrap();
    std::fs::write(p.join("docs/blob.bin"), [0u8, 159, 146, 150]).unwrap();
    std::fs::write(p.join("Alpha.txt"), "a").unwrap();
    std::os::unix::fs::symlink("/etc/hostname", p.join("outside-link")).unwrap();
    std::os::unix::fs::symlink(p.join("docs"), p.join("inside-link")).unwrap();
    root
}

fn with_files(root: &Path) -> impl FnOnce(BridgeConfig) -> BridgeConfig + '_ {
    move |c| {
        c.set("BRIDGE_FILES", "1")
            .set("BRIDGE_FILES_ROOT", &root.display().to_string())
    }
}

#[tokio::test]
async fn the_file_list_is_sorted_folders_first_and_links_never_leave_the_root() {
    let root = files_root();
    let (s, token) = paired_with(with_files(root.path())).await;
    let caps: Value = s
        .bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(caps["features"]["files"], true);
    assert_eq!(caps["features"]["projects"], true);
    assert_eq!(
        caps["endpoints"]["files_list"],
        json!({"method": "GET", "path": "/v1/files/list"})
    );

    let response = s
        .bridge
        .get("/v1/files/list", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(header(&response, "cache-control").unwrap(), "no-store");
    let listing: Value = response.json().await.unwrap();
    assert_eq!(listing["object"], "orsik.files.list");
    assert_eq!(
        listing["root"],
        std::fs::canonicalize(root.path())
            .unwrap()
            .display()
            .to_string()
    );
    assert_eq!(listing["path"], "");
    assert_eq!(listing["truncated"], false);
    let entries = listing["entries"].as_array().unwrap();
    let names: Vec<(&str, &str)> = entries
        .iter()
        .map(|e| (e["name"].as_str().unwrap(), e["type"].as_str().unwrap()))
        .collect();
    assert_eq!(
        names,
        [
            ("docs", "dir"),
            ("inside-link", "dir"),
            ("Alpha.txt", "file"),
            ("outside-link", "other")
        ]
    );
    let alpha = entries.iter().find(|e| e["name"] == "Alpha.txt").unwrap();
    assert_eq!(alpha["size"], 1);
    assert!(alpha["modified_at"].is_number());
    let outside = entries
        .iter()
        .find(|e| e["name"] == "outside-link")
        .unwrap();
    assert_eq!(outside["size"], Value::Null);
    assert_eq!(outside["modified_at"], Value::Null);

    let sub: Value = s
        .bridge
        .get("/v1/files/list?path=docs", Some(&token))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(sub["path"], "docs");
}

#[tokio::test]
async fn file_content_has_a_closed_type_set_and_bounds() {
    let root = files_root();
    std::fs::write(root.path().join("big.txt"), vec![b'a'; 8 * 1024 * 1024 + 1]).unwrap();
    let (s, token) = paired_with(with_files(root.path())).await;
    for (path, content_type) in [
        ("docs/readme.md", "text/plain; charset=utf-8"),
        ("docs/bild.png", "image/png"),
        ("docs/blob.bin", "application/octet-stream"),
        ("inside-link/readme.md", "text/plain; charset=utf-8"),
    ] {
        let response = s
            .bridge
            .get(&format!("/v1/files/content?path={path}"), Some(&token))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 200, "{path}");
        assert_eq!(
            header(&response, "content-type").unwrap(),
            content_type,
            "{path}"
        );
        assert_eq!(
            header(&response, "x-content-type-options").unwrap(),
            "nosniff"
        );
        assert_eq!(header(&response, "cache-control").unwrap(), "no-store");
    }
    let text = s
        .bridge
        .get("/v1/files/content?path=docs/readme.md", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(text.text().await.unwrap(), "# Hallo\n");

    for (path, expected) in [
        ("../etc/passwd", (403, "path_not_allowed")),
        ("docs/../../x", (403, "path_not_allowed")),
        ("/etc/passwd", (403, "path_not_allowed")),
        ("outside-link", (403, "path_not_allowed")),
        ("missing.txt", (404, "not_found")),
        ("docs", (404, "not_found")),
        ("", (404, "not_found")),
        ("big.txt", (413, "file_too_large")),
    ] {
        let response = s
            .bridge
            .get("/v1/files/content", Some(&token))
            .query(&[("path", path)])
            .send()
            .await
            .unwrap();
        assert_eq!(
            error_code(response).await,
            (expected.0, expected.1.into()),
            "{path}"
        );
    }
    let extra = s
        .bridge
        .get("/v1/files/content?path=a&x=1", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(extra).await, (403, "query_not_allowed".into()));
    let twice = s
        .bridge
        .get("/v1/files/content?path=a&path=b", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(twice).await, (403, "query_not_allowed".into()));
}

#[tokio::test]
async fn a_root_holding_the_bridge_data_or_inside_it_is_refused() {
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    // The bridge home itself and a folder inside it.
    std::fs::create_dir_all(home.path().join("inner")).unwrap();
    for root in [home.path().to_path_buf(), home.path().join("inner")] {
        let config = BridgeConfig::new(&hermes, home.path())
            .set("BRIDGE_FILES", "1")
            .set("BRIDGE_FILES_ROOT", &root.display().to_string());
        let exit = start_expecting_refusal(&config).await;
        assert_ne!(exit.code, Some(0), "{}", root.display());
        assert!(exit.stderr.contains("BRIDGE_FILES_ROOT"), "{}", exit.stderr);
    }
    for (files, root) in [("yes", "/tmp"), ("1", "relative/dir"), ("1", "/")] {
        let config = BridgeConfig::new(&hermes, home.path())
            .set("BRIDGE_FILES", files)
            .set("BRIDGE_FILES_ROOT", root);
        let exit = start_expecting_refusal(&config).await;
        assert_ne!(exit.code, Some(0), "BRIDGE_FILES={files} root={root}");
    }
}

fn git(dir: &Path, args: &[&str]) {
    let status = std::process::Command::new("git")
        .args(args)
        .current_dir(dir)
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_AUTHOR_NAME", "t")
        .env("GIT_AUTHOR_EMAIL", "t@example.org")
        .env("GIT_COMMITTER_NAME", "t")
        .env("GIT_COMMITTER_EMAIL", "t@example.org")
        .output()
        .unwrap();
    assert!(
        status.status.success(),
        "git {args:?}: {}",
        String::from_utf8_lossy(&status.stderr)
    );
}

#[tokio::test]
async fn projects_list_git_trees_with_branch_state_and_last_commit() {
    let root = tempfile::tempdir().unwrap();
    let clean = root.path().join("clean");
    let dirty = root.path().join("group/dirty");
    for repo in [&clean, &dirty] {
        std::fs::create_dir_all(repo).unwrap();
        git(repo, &["init", "-q", "-b", "main"]);
        std::fs::write(repo.join("f.txt"), "1").unwrap();
        git(repo, &["add", "f.txt"]);
        git(repo, &["commit", "-q", "-m", "Erster Stand"]);
    }
    std::fs::write(dirty.join("f.txt"), "2").unwrap();
    std::fs::create_dir_all(root.path().join("empty")).unwrap();
    let (s, token) = paired_with(with_files(root.path())).await;
    let response = s
        .bridge
        .get("/v1/projects", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let body: Value = response.json().await.unwrap();
    assert_eq!(body["object"], "orsik.projects.list");
    assert_eq!(body["truncated"], false);
    let projects = body["projects"].as_array().unwrap();
    assert_eq!(projects.len(), 2);
    let by_name = |name: &str| projects.iter().find(|p| p["name"] == name).unwrap().clone();
    let c = by_name("clean");
    assert_eq!(c["path"], "clean");
    assert_eq!(c["branch"], "main");
    assert_eq!(c["dirty"], false);
    assert_eq!(c["last_commit"]["subject"], "Erster Stand");
    assert!(c["last_commit"]["short"].as_str().unwrap().len() >= 7);
    assert!(c["last_commit"]["committed_at"].is_number());
    let d = by_name("dirty");
    assert_eq!(d["path"], "group/dirty");
    assert_eq!(d["dirty"], true);
    let query = s
        .bridge
        .get("/v1/projects?path=x", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(query).await, (403, "query_not_allowed".into()));
}

/// A fake `tmux` that echoes its input, so the terminal route can be checked
/// without a real tmux session on the machine running the suite.
fn fake_tmux_path() -> (tempfile::TempDir, String) {
    let dir = tempfile::tempdir().unwrap();
    let tmux = dir.path().join("tmux");
    std::fs::write(&tmux, "#!/bin/sh\nexec cat\n").unwrap();
    std::fs::set_permissions(&tmux, std::fs::Permissions::from_mode(0o755)).unwrap();
    let path = format!(
        "{}:{}",
        dir.path().display(),
        std::env::var("PATH").unwrap()
    );
    (dir, path)
}

fn ws_request(
    s: &Setup,
    token: Option<&str>,
    origin: bool,
) -> tokio_tungstenite::tungstenite::handshake::client::Request {
    let mut request = format!("ws://127.0.0.1:{}/v1/terminal", s.bridge.port)
        .into_client_request()
        .unwrap();
    if let Some(token) = token {
        request
            .headers_mut()
            .insert("Authorization", format!("Bearer {token}").parse().unwrap());
    }
    if origin {
        request
            .headers_mut()
            .insert("Origin", "https://evil.example".parse().unwrap());
    }
    request
}

#[tokio::test]
async fn the_terminal_is_off_by_default() {
    let (s, token) = paired_with(|c| c).await;
    let response = s
        .bridge
        .get("/v1/terminal", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(response).await,
        (403, "endpoint_out_of_scope".into())
    );
    let caps: Value = s
        .bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(caps["features"]["terminal"], false);
    assert!(caps["endpoints"].get("terminal").is_none());
}

#[tokio::test]
async fn the_terminal_needs_a_grant_and_refuses_browsers() {
    let (_dir, path) = fake_tmux_path();
    let (s, token) = paired_with(|c| c.set("BRIDGE_TERMINAL", "1").set("PATH", &path)).await;
    // Paired devices have no shell authority until the operator grants it.
    let caps: Value = s
        .bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(caps["features"]["terminal"], false);
    let denied = s
        .bridge
        .get("/v1/terminal", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(denied).await,
        (403, "terminal_not_allowed".into())
    );

    let device_id = {
        let (_, out, _) = s.bridge.admin(&["list-devices"]).await;
        out.split_whitespace().next().unwrap().to_string()
    };
    let (code, _, _) = s.bridge.admin(&["grant-terminal", &device_id]).await;
    assert_eq!(code, 0);
    let caps: Value = s
        .bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(caps["features"]["terminal"], true);
    assert_eq!(
        caps["endpoints"]["terminal"],
        json!({"method": "GET", "path": "/v1/terminal"})
    );

    let plain = s
        .bridge
        .get("/v1/terminal", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(plain).await, (400, "websocket_required".into()));
    let query = s
        .bridge
        .get("/v1/terminal?x=1", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(query).await, (403, "query_not_allowed".into()));
    let browser = tokio_tungstenite::connect_async(ws_request(&s, Some(&token), true)).await;
    match browser {
        Err(tokio_tungstenite::tungstenite::Error::Http(response)) => {
            assert_eq!(response.status(), 403)
        }
        other => panic!("a browser origin must be refused, got {other:?}"),
    }
    let anonymous = tokio_tungstenite::connect_async(ws_request(&s, None, false)).await;
    match anonymous {
        Err(tokio_tungstenite::tungstenite::Error::Http(response)) => {
            assert_eq!(response.status(), 401)
        }
        other => panic!("an anonymous socket must be refused, got {other:?}"),
    }
}

#[tokio::test]
async fn a_granted_terminal_relays_bytes_and_closes_on_a_bad_control_message() {
    let (_dir, path) = fake_tmux_path();
    let (s, token) = paired_with(|c| c.set("BRIDGE_TERMINAL", "1").set("PATH", &path)).await;
    let device_id = {
        let (_, out, _) = s.bridge.admin(&["list-devices"]).await;
        out.split_whitespace().next().unwrap().to_string()
    };
    s.bridge.admin(&["grant-terminal", &device_id]).await;
    let (mut ws, _) = tokio_tungstenite::connect_async(ws_request(&s, Some(&token), false))
        .await
        .unwrap();
    ws.send(Message::Text(
        json!({"type": "resize", "cols": 100, "rows": 30})
            .to_string()
            .into(),
    ))
    .await
    .unwrap();
    ws.send(Message::Binary(b"echo-me\n".to_vec().into()))
        .await
        .unwrap();
    let mut seen = Vec::new();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    while !String::from_utf8_lossy(&seen).contains("echo-me") {
        let message = tokio::time::timeout_at(deadline, ws.next())
            .await
            .expect("echo arrives")
            .unwrap()
            .unwrap();
        if let Message::Binary(bytes) = message {
            seen.extend_from_slice(&bytes);
        }
    }
    ws.send(Message::Text(
        "{\"type\":\"resize\",\"cols\":0,\"rows\":1}".into(),
    ))
    .await
    .unwrap();
    let close = loop {
        match tokio::time::timeout(Duration::from_secs(10), ws.next())
            .await
            .expect("socket closes")
        {
            Some(Ok(Message::Close(frame))) => break frame,
            Some(Ok(_)) => continue,
            other => panic!("expected a close frame, got {other:?}"),
        }
    };
    let close = close.expect("a close frame with a code");
    assert_eq!(u16::from(close.code), 1008);
    assert_eq!(close.reason.as_str(), "protocol");
}
