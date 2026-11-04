//! The bridge under test opens the store the Python bridge wrote, so a
//! phone paired before the cutover keeps working without pairing again.

use orsik_bridge_contract::*;

#[tokio::test]
async fn a_device_paired_by_the_python_bridge_keeps_working() {
    let hermes = FakeHermes::start().await;
    let home = tempfile::tempdir().unwrap();
    let config = BridgeConfig::new(&hermes, home.path());

    let python = Bridge::start(config.clone().python()).await;
    let (token, credential) = python.pair().await;
    let (revoked_token, revoked) = python.pair().await;
    let device_id = credential["device_id"].as_str().unwrap().to_string();
    python.admin(&["grant-terminal", &device_id]).await;
    python
        .admin(&["revoke-device", revoked["device_id"].as_str().unwrap()])
        .await;
    let leftover_code = python.pairing_code().await;
    drop(python);
    tokio::time::sleep(std::time::Duration::from_millis(300)).await;

    let bridge = Bridge::start(config).await;
    let caps = bridge
        .get("/v1/capabilities", Some(&token))
        .send()
        .await
        .unwrap();
    assert_eq!(caps.status(), 200, "the key paired by Python authenticates");
    let gone = bridge
        .get("/v1/capabilities", Some(&revoked_token))
        .send()
        .await
        .unwrap();
    assert_eq!(
        error_code(gone).await,
        (401, "invalid_credentials".into()),
        "a revocation survives"
    );
    let (_, out, _) = bridge.admin(&["list-devices"]).await;
    let line = out
        .lines()
        .find(|l| l.starts_with(&device_id))
        .expect("device listed");
    assert!(line.contains("terminal=allowed"), "{line}");
    // A code issued by the Python bridge is still single-use and valid.
    let paired = bridge
        .post("/v1/devices/pair", None)
        .json(&serde_json::json!({"code": leftover_code}))
        .send()
        .await
        .unwrap();
    assert_eq!(paired.status(), 201);
}
