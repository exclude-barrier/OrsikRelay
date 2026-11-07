//! Mobile-v1 constants, request validators and the bridge-generated
//! documents (capabilities, device credential). Mirrors the Python
//! `core.py`; the contract is docs/bridge-contract.md.

use rand::RngExt;
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};

pub const CODE_ALPHABET: &str = "ABCDEFGHJKMNPQRSTVWXYZ123456789";
pub const CODE_LENGTH: usize = 24;
pub const CODE_TTL_SECONDS: u64 = 300;
pub const PROTOCOL_VERSION: u64 = 1;
pub const DEVICE_PERMISSIONS: [&str; 3] = ["approvals", "chat", "runs"];
pub const MAX_RUN_ID_LENGTH: usize = 256;
pub const MAX_REQUEST_ID_LENGTH: usize = 256;

pub const TERMINAL_PATH: &str = "/v1/terminal";
pub const FILES_LIST_PATH: &str = "/v1/files/list";
pub const FILES_CONTENT_PATH: &str = "/v1/files/content";
pub const PROJECTS_PATH: &str = "/v1/projects";

pub fn sha256_hex(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

/// Python's `str.isalnum()` for one character (Unicode letters and digits).
fn is_alnum(ch: char) -> bool {
    ch.is_alphanumeric()
}

/// A path segment: not empty, not `.`/`..`, only letters, digits, `-_.`.
pub fn safe_segment(value: &str) -> bool {
    !value.is_empty()
        && value != "."
        && value != ".."
        && !value.contains('/')
        && value.chars().all(|ch| is_alnum(ch) || "-_.".contains(ch))
}

pub fn is_control_free(value: &str) -> bool {
    !value
        .chars()
        .any(|ch| (ch as u32) < 0x20 || ch as u32 == 0x7F)
}

pub fn is_safe_run_id(run_id: &str) -> bool {
    !run_id.is_empty()
        && run_id.chars().count() <= MAX_RUN_ID_LENGTH
        && safe_segment(run_id)
        && is_control_free(run_id)
}

/// Python `str.strip()` emptiness: only Unicode whitespace.
fn is_blank(value: &str) -> bool {
    value.chars().all(char::is_whitespace)
}

fn keys(body: &Map<String, Value>) -> Vec<&str> {
    let mut keys: Vec<&str> = body.keys().map(String::as_str).collect();
    keys.sort_unstable();
    keys
}

/// A JSON integer that is not a bool (Python `type(x) is int`).
fn as_int(value: &Value) -> Option<i64> {
    match value {
        Value::Number(n) if n.is_i64() || n.is_u64() => n.as_i64(),
        _ => None,
    }
}

pub fn validate_approval_body(body: &Value) -> Result<(), &'static str> {
    let Some(obj) = body.as_object() else {
        return Err("invalid_json");
    };
    if keys(obj) != ["choice", "request_id"] {
        return Err("invalid_fields");
    }
    match obj["choice"].as_str() {
        Some("once") | Some("deny") => {}
        _ => return Err("invalid_choice"),
    }
    match obj["request_id"].as_str() {
        Some(id)
            if !is_blank(id)
                && id.chars().count() <= MAX_REQUEST_ID_LENGTH
                && is_control_free(id) =>
        {
            Ok(())
        }
        _ => Err("invalid_request_id"),
    }
}

fn has_c1(value: &str) -> bool {
    value.chars().any(|ch| (0x80..=0x9F).contains(&(ch as u32)))
}

pub fn validate_session_patch_body(body: &Value) -> Result<(), &'static str> {
    let Some(obj) = body.as_object() else {
        return Err("invalid_json");
    };
    if keys(obj) == ["title"] {
        return match obj["title"].as_str() {
            Some(t)
                if !is_blank(t) && t.chars().count() <= 512 && is_control_free(t) && !has_c1(t) =>
            {
                Ok(())
            }
            _ => Err("invalid_title"),
        };
    }
    if keys(obj) != ["archived"] {
        return Err("invalid_fields");
    }
    if !obj["archived"].is_boolean() {
        return Err("invalid_archived");
    }
    Ok(())
}

pub fn validate_session_model_body(body: &Value) -> Result<(), &'static str> {
    let Some(obj) = body.as_object() else {
        return Err("invalid_fields");
    };
    if keys(obj) != ["model", "provider"] {
        return Err("invalid_fields");
    }
    match obj["provider"].as_str() {
        Some(p)
            if (1..=80).contains(&p.chars().count())
                && p.chars()
                    .all(|c| c.is_ascii_alphanumeric() || "_.-".contains(c)) => {}
        _ => return Err("invalid_provider"),
    }
    match obj["model"].as_str() {
        Some(m)
            if !m.is_empty()
                && m.chars().count() <= 200
                && is_control_free(m)
                && !m
                    .chars()
                    .any(|c| c.is_whitespace() || (0x80..=0x9F).contains(&(c as u32)))
                && !m.contains("::") =>
        {
            Ok(())
        }
        _ => Err("invalid_model"),
    }
}

pub fn validate_resolve_unknown_body(body: &Value) -> Result<(), &'static str> {
    let Some(obj) = body.as_object() else {
        return Err("invalid_json");
    };
    if keys(obj) != ["admission_id", "execution_generation"] {
        return Err("invalid_fields");
    }
    match obj["admission_id"].as_str() {
        Some(id)
            if !is_blank(id)
                && id.chars().count() <= MAX_REQUEST_ID_LENGTH
                && is_control_free(id) => {}
        _ => return Err("invalid_admission_id"),
    }
    match as_int(&obj["execution_generation"]) {
        Some(g) if g >= 0 => Ok(()),
        _ => Err("invalid_generation"),
    }
}

/// Trim, upper-case, drop `-`, `_` and every whitespace; then 24 characters
/// of the code alphabet.
pub fn normalize_code(raw: &Value) -> Option<String> {
    let raw = raw.as_str()?;
    let cleaned: String = raw
        .trim()
        .to_uppercase()
        .chars()
        .filter(|ch| *ch != '-' && *ch != '_' && !ch.is_whitespace())
        .collect();
    if cleaned.chars().count() != CODE_LENGTH
        || !cleaned.chars().all(|ch| CODE_ALPHABET.contains(ch))
    {
        return None;
    }
    Some(cleaned)
}

pub fn generate_code() -> String {
    let alphabet: Vec<char> = CODE_ALPHABET.chars().collect();
    let mut rng = rand::rng();
    (0..CODE_LENGTH)
        .map(|_| alphabet[rng.random_range(0..alphabet.len())])
        .collect()
}

/// `hd_` + 32 random bytes as unpadded base64url (46 characters).
pub fn generate_device_key() -> String {
    use base64::Engine;
    let mut bytes = [0u8; 32];
    rand::rng().fill(&mut bytes);
    format!(
        "hd_{}",
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
    )
}

#[derive(Clone, Debug)]
pub struct DeviceRecord {
    pub device_id: String,
    pub label: String,
    pub profile: String,
    pub permissions: Vec<String>,
    pub already_revoked: bool,
    pub terminal_allowed: bool,
}

impl DeviceRecord {
    pub fn has_permission(&self, permission: &str) -> bool {
        self.permissions.iter().any(|p| p == permission)
    }
}

/// `host_metrics` is false on the public ingress, which refuses that route.
pub fn build_capabilities(terminal: bool, files: bool, host_metrics: bool) -> Value {
    let mut caps = json!({
        "object": "hermes.api_server.capabilities",
        "platform": "hermes-agent",
        "auth": {"type": "bearer", "required": true},
        "runtime": {
            "mode": "server_agent",
            "tool_execution": "server",
            "bridge": "orsik-mobile-bridge",
            "core": "official-hermes",
        },
        "features": {
            "session_resources": true,
            "session_chat_streaming": true,
            "run_approval_response": true,
            "run_status": true,
            "run_stop": true,
            "run_steer": true,
            "run_unknown_resolution": true,
            "host_metrics": host_metrics,
            "terminal": terminal,
            "files": files,
            "projects": files,
            "session_archive": true,
            "session_title": true,
            "session_fork": true,
            "session_model_lock": true,
            "session_delete": true,
            "session_chat_interactive_approvals": {
                "enabled": true,
                "protocol_version": PROTOCOL_VERSION,
                "event": "approval.request",
                "choices": ["once", "deny"],
                "response_endpoint": "run_approval",
            },
            "device_credentials": {
                "enabled": true,
                "protocol_version": PROTOCOL_VERSION,
                "pair_endpoint": "device_pair",
                "self_revoke_endpoint": "device_self_revoke",
            },
        },
        "endpoints": {
            "health": {"method": "GET", "path": "/health"},
            "sessions": {"method": "GET", "path": "/api/sessions"},
            "session_create": {"method": "POST", "path": "/api/sessions"},
            "session_detail": {"method": "GET", "path": "/api/sessions/{session_id}"},
            "session_messages": {"method": "GET", "path": "/api/sessions/{session_id}/messages"},
            "session_chat_stream": {"method": "POST", "path": "/api/sessions/{session_id}/chat/stream"},
            "run_unknown_resolution": {"method": "POST", "path": "/v1/runs/{run_id}/resolve-unknown"},
            "session_update": {"method": "PATCH", "path": "/api/sessions/{session_id}"},
            "session_fork": {"method": "POST", "path": "/api/sessions/{session_id}/fork"},
            "session_model_lock": {"method": "POST", "path": "/api/sessions/{session_id}/model"},
            "model_options": {"method": "GET", "path": "/api/model/options"},
            "session_delete": {"method": "DELETE", "path": "/api/sessions/{session_id}"},
            "run_status": {"method": "GET", "path": "/v1/runs/{run_id}"},
            "run_approval": {"method": "POST", "path": "/v1/runs/{run_id}/approval"},
            "run_stop": {"method": "POST", "path": "/v1/runs/{run_id}/stop"},
            "run_steer": {"method": "POST", "path": "/v1/runs/{run_id}/steer"},
            "device_pair": {"method": "POST", "path": "/v1/devices/pair"},
            "device_self_revoke": {"method": "POST", "path": "/v1/devices/self/revoke"},
        },
    });
    let endpoints = caps["endpoints"].as_object_mut().unwrap();
    if host_metrics {
        endpoints.insert(
            "host_metrics".into(),
            json!({"method": "GET", "path": "/v1/host/metrics"}),
        );
    }
    if terminal {
        endpoints.insert(
            "terminal".into(),
            json!({"method": "GET", "path": TERMINAL_PATH}),
        );
    }
    if files {
        endpoints.insert(
            "files_list".into(),
            json!({"method": "GET", "path": FILES_LIST_PATH}),
        );
        endpoints.insert(
            "files_content".into(),
            json!({"method": "GET", "path": FILES_CONTENT_PATH}),
        );
        endpoints.insert(
            "projects".into(),
            json!({"method": "GET", "path": PROJECTS_PATH}),
        );
    }
    caps
}

pub fn build_credential(record: &DeviceRecord, token: &str, issued_at: f64) -> Value {
    json!({
        "object": "hermes.device.credential",
        "device_id": record.device_id,
        "device_key": token,
        "profile": record.profile,
        "label": record.label,
        "permissions": record.permissions,
        "issued_at": issued_at,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_normalised_like_the_python_bridge() {
        let code = "ABCD-EFGH-JKMN-PQRS-TVWX-YZ12";
        assert_eq!(
            normalize_code(&json!(code)).as_deref(),
            Some("ABCDEFGHJKMNPQRSTVWXYZ12")
        );
        assert_eq!(
            normalize_code(&json!(" abcd efgh_jkmn\u{a0}pqrs-tvwx-yz12 ")).as_deref(),
            Some("ABCDEFGHJKMNPQRSTVWXYZ12")
        );
        assert_eq!(
            normalize_code(&json!("ABCD-EFGH-JKMN-PQRS-TVWX-YZ1I")),
            None,
            "I is not in the alphabet"
        );
        assert_eq!(normalize_code(&json!("ABC")), None);
        assert_eq!(normalize_code(&json!(42)), None);
        let generated = generate_code();
        assert_eq!(
            normalize_code(&json!(generated)).as_deref(),
            Some(generated.as_str())
        );
    }

    #[test]
    fn device_keys_have_the_mobile_v1_shape() {
        let key = generate_device_key();
        assert_eq!(key.len(), 46);
        assert!(key.starts_with("hd_"));
        assert_ne!(key, generate_device_key());
    }

    #[test]
    fn the_sha256_hex_matches_pythons_hashlib() {
        assert_eq!(
            sha256_hex("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn segments_accept_unicode_letters_like_python_isalnum() {
        assert!(safe_segment("run_1.a-b"));
        assert!(safe_segment("käse"));
        assert!(!safe_segment(".."));
        assert!(!safe_segment("a b"));
        assert!(!safe_segment(""));
    }

    #[test]
    fn host_metrics_are_offered_only_where_the_route_is_reachable() {
        let private = build_capabilities(false, false, true);
        assert_eq!(private["features"]["host_metrics"], json!(true));
        assert!(private["endpoints"]["host_metrics"].is_object());
        let public = build_capabilities(false, false, false);
        assert_eq!(public["features"]["host_metrics"], json!(false));
        assert!(public["endpoints"].get("host_metrics").is_none());
    }
}
