//! Pairing, self-revoke, the operator CLI and the persistent device store.

use orsik_bridge_contract::*;
use serde_json::json;

#[tokio::test]
async fn a_pairing_code_has_the_contract_shape() {
    let s = Setup::new().await;
    let code = s.bridge.pairing_code().await;
    // Display form: 6 groups of 4 from the 31-character alphabet.
    let groups: Vec<&str> = code.split('-').collect();
    assert_eq!(groups.len(), 6, "{code}");
    for group in groups {
        assert_eq!(group.len(), 4);
        assert!(
            group
                .chars()
                .all(|c| "ABCDEFGHJKMNPQRSTVWXYZ123456789".contains(c)),
            "{code}"
        );
    }
}

#[tokio::test]
async fn pairing_returns_exactly_the_seven_credential_fields() {
    let s = Setup::new().await;
    let (token, credential) = s.bridge.pair().await;
    let object = credential.as_object().unwrap();
    let mut keys: Vec<&str> = object.keys().map(String::as_str).collect();
    keys.sort();
    assert_eq!(
        keys,
        [
            "device_id",
            "device_key",
            "issued_at",
            "label",
            "object",
            "permissions",
            "profile"
        ]
    );
    assert_eq!(credential["object"], "hermes.device.credential");
    assert_eq!(credential["profile"], "default");
    assert_eq!(credential["label"], "paired-device");
    assert_eq!(
        credential["permissions"],
        json!(["approvals", "chat", "runs"])
    );
    assert!(credential["issued_at"].is_number());
    // Mobile-v1 device key shape: "hd_" + 43 base64url characters.
    assert_eq!(token.len(), 46);
    assert!(token.starts_with("hd_"));
    assert!(
        token[3..]
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    );
    let device_id = credential["device_id"].as_str().unwrap();
    assert_eq!(device_id.len(), 36, "a UUID");
}

#[tokio::test]
async fn a_code_works_once_and_in_any_spelling() {
    let s = Setup::new().await;
    let code = s.bridge.pairing_code().await;
    // Lower case, spaces and underscores are normalised away.
    let sloppy = format!("  {} ", code.to_lowercase().replace('-', " _"));
    let first = s
        .bridge
        .post("/v1/devices/pair", None)
        .json(&json!({"code": sloppy}))
        .send()
        .await
        .unwrap();
    assert_eq!(first.status(), 201);
    let second = s
        .bridge
        .post("/v1/devices/pair", None)
        .json(&json!({"code": code}))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(second).await,
        (400, "pairing_code_invalid".into())
    );
}

#[tokio::test]
async fn pairing_refuses_malformed_requests() {
    let s = Setup::new().await;
    let not_json = s
        .bridge
        .post("/v1/devices/pair", None)
        .header("Content-Type", "text/plain")
        .body("{\"code\":\"x\"}")
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(not_json).await, (400, "invalid_json".into()));
    let broken = s
        .bridge
        .post("/v1/devices/pair", None)
        .header("Content-Type", "application/json")
        .body("{")
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(broken).await, (400, "invalid_json".into()));
    let extra = s
        .bridge
        .post("/v1/devices/pair", None)
        .json(&json!({"code": "A", "label": "x"}))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(extra).await, (400, "invalid_fields".into()));
    let unknown = s
        .bridge
        .post("/v1/devices/pair", None)
        .json(&json!({"code": "AAAA-BBBB-CCCC-DDDD-EEEE-FFFF"}))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(unknown).await,
        (400, "pairing_code_invalid".into())
    );
    let wrong_type = s
        .bridge
        .post("/v1/devices/pair", None)
        .json(&json!({"code": 42}))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(wrong_type).await,
        (400, "pairing_code_invalid".into())
    );
    let not_object = s
        .bridge
        .post("/v1/devices/pair", None)
        .json(&json!(["code"]))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(not_object).await,
        (400, "pairing_code_invalid".into())
    );
}

#[tokio::test]
async fn pairing_is_rate_limited_per_peer() {
    let s = Setup::new().await;
    let mut limited = false;
    for _ in 0..61 {
        let response = s
            .bridge
            .post("/v1/devices/pair", None)
            .json(&json!({"code": "x"}))
            .send()
            .await
            .unwrap();
        if response.status() == 429 {
            assert_eq!(error_code(response).await, (429, "rate_limited".into()));
            limited = true;
            break;
        }
    }
    assert!(limited, "61 attempts within a minute must hit the limit");
}

#[tokio::test]
async fn self_revoke_ends_the_device_key_for_good() {
    let s = Setup::new().await;
    let (token, credential) = s.bridge.pair().await;
    let response = s
        .bridge
        .post("/v1/devices/self/revoke", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    let body: serde_json::Value = response.json().await.unwrap();
    assert_eq!(
        body,
        json!({"revoked": true, "device_id": credential["device_id"]})
    );
    let after = s
        .bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(after).await, (401, "invalid_credentials".into()));
}

#[tokio::test]
async fn self_revoke_needs_a_key_and_no_body() {
    let s = Setup::new().await;
    let anonymous = s
        .bridge
        .post("/v1/devices/self/revoke", None)
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(anonymous).await,
        (401, "invalid_credentials".into())
    );
    let anonymous_body = s
        .bridge
        .post("/v1/devices/self/revoke", None)
        .body("x")
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(anonymous_body).await,
        (401, "invalid_credentials".into())
    );
    let (token, _) = s.bridge.pair().await;
    let with_body = s
        .bridge
        .post("/v1/devices/self/revoke", Some(&token))
        .body("x")
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(with_body).await,
        (400, "body_not_allowed".into())
    );
    // The body rule did not revoke anything.
    let still = s
        .bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(still.status(), 200);
}

#[tokio::test]
async fn a_paired_device_survives_a_bridge_restart() {
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    let config = BridgeConfig::new(&hermes, home.path());
    let first = Bridge::start(config.clone()).await;
    let (token, _) = first.pair().await;
    drop(first);
    tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    let second = Bridge::start(config).await;
    let response = second
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
}

#[tokio::test]
async fn the_operator_lists_revokes_and_grants_the_terminal() {
    let s = Setup::new().await;
    let (token, credential) = s.bridge.pair().await;
    let device_id = credential["device_id"].as_str().unwrap();

    let (code, out, _) = s.bridge.admin(&["list-devices"]).await;
    assert_eq!(code, 0);
    let line = out
        .lines()
        .find(|l| l.starts_with(device_id))
        .expect("device listed");
    assert!(line.contains("active"), "{line}");
    assert!(line.contains("paired-device"), "{line}");
    assert!(line.contains("[default]"), "{line}");
    assert!(line.contains("terminal=denied"), "{line}");
    assert!(!out.contains(&token), "the key is never listed");

    let (code, out, _) = s.bridge.admin(&["grant-terminal", device_id]).await;
    assert_eq!(code, 0);
    assert!(
        out.contains(&format!("terminal allowed for {device_id}")),
        "{out}"
    );
    let (_, out, _) = s.bridge.admin(&["list-devices"]).await;
    assert!(out.contains("terminal=allowed"), "{out}");
    let (code, _, _) = s.bridge.admin(&["revoke-terminal", device_id]).await;
    assert_eq!(code, 0);

    let (code, _, err) = s.bridge.admin(&["grant-terminal", "no-such-device"]).await;
    assert_eq!(code, 1);
    assert!(err.contains("no active device"), "{err}");

    let (code, out, _) = s.bridge.admin(&["revoke-device", device_id]).await;
    assert_eq!(code, 0);
    assert!(out.contains(&format!("revoked {device_id}")));
    let after = s
        .bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(error_code(after).await, (401, "invalid_credentials".into()));
    let (code, _, err) = s.bridge.admin(&["revoke-device", "no-such-device"]).await;
    assert_eq!(code, 1);
    assert!(err.contains("no such device_id"), "{err}");
}

#[tokio::test]
async fn the_pairing_code_prints_a_deep_link_only_for_an_https_base_url() {
    let s = Setup::new().await;
    let (code, out, _) = s
        .bridge
        .admin(&[
            "pairing-code",
            "--base-url",
            "https://relay.example.ts.net:8443",
        ])
        .await;
    assert_eq!(code, 0);
    let link = out
        .lines()
        .find(|l| l.starts_with("Deep link:"))
        .expect("deep link");
    assert!(
        link.contains("orsik://pair?server=https%3A%2F%2Frelay.example.ts.net%3A8443&code="),
        "{link}"
    );
    let (code, _, err) = s
        .bridge
        .admin(&["pairing-code", "--base-url", "http://relay.example"])
        .await;
    assert_eq!(code, 1);
    assert!(err.contains("https"), "{err}");
}

#[tokio::test]
async fn backup_writes_a_private_copy_and_never_overwrites() {
    use std::os::unix::fs::PermissionsExt;
    let s = Setup::new().await;
    let _ = s.bridge.pair().await;
    let dest = s.home_path().join("backup.db");
    let dest_text = dest.display().to_string();
    let (code, out, _) = s.bridge.admin(&["backup", &dest_text]).await;
    assert_eq!(code, 0, "{out}");
    let mode = std::fs::metadata(&dest).unwrap().permissions().mode() & 0o777;
    assert_eq!(mode, 0o600);
    let (code, _, err) = s.bridge.admin(&["backup", &dest_text]).await;
    assert_eq!(code, 1);
    assert!(err.contains("already exists"), "{err}");
}

#[tokio::test]
async fn the_store_is_private() {
    use std::os::unix::fs::PermissionsExt;
    let s = Setup::new().await;
    let _ = s.bridge.pair().await;
    let mode = std::fs::metadata(s.home_path().join("state.db"))
        .unwrap()
        .permissions()
        .mode()
        & 0o777;
    assert_eq!(mode, 0o600);
}
