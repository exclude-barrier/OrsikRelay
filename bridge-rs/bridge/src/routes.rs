//! Every route besides the bridge-generated core: the closed proxy
//! allowlist, the dedicated validating handlers and the chat stream takeover.

use std::convert::Infallible;
use std::sync::Arc;
use std::time::Duration;

use axum::body::{Body, Bytes};
use axum::extract::Request;
use axum::http::{HeaderMap, Method, header};
use axum::response::Response;
use futures_util::StreamExt;
use percent_encoding::percent_decode_str;
use serde_json::{Map, Value, json};
use tokio::sync::mpsc;

use crate::core::{self, DeviceRecord};
use crate::history;
use crate::runhub::{Attached, Item, RunBuffer};
use crate::server::{
    BodyError, Forward, Handled, MAX_UPSTREAM_ERROR_BODY_BYTES, OnStatus, State, audit, closed,
    forward, lookup, read_body, read_upstream_bounded, redact_master_key,
};
use crate::sse::{self, Decoder, Frame, MAX_FRAME_BYTES};

/// Bound of a history page before slimming (inline images included).
const MAX_HISTORY_BODY_BYTES: usize = 64 * 1024 * 1024;
/// The chat decoder bound: the core echoes the request body in run.started.
const MAX_ECHO_FRAME_BYTES: usize = crate::server::MAX_BODY_BYTES + 64 * 1024;
const RUN_STREAM_KEEPALIVE: Duration = Duration::from_secs(15);
const REVOKE_CHECK_INTERVAL: Duration = Duration::from_secs(15);
const AUDITED_RUN_ACTIONS: [&str; 2] = ["stop", "steer"];

/// The closed set of proxied upstream paths (identity mapping).
const PROXIED_ROUTES: [(&str, &str); 15] = [
    ("GET", "/api/sessions"),
    ("POST", "/api/sessions"),
    ("GET", "/api/sessions/{session_id}"),
    ("GET", "/api/sessions/{session_id}/messages"),
    ("POST", "/api/sessions/{session_id}/chat/stream"),
    ("POST", "/api/sessions/{session_id}/fork"),
    ("POST", "/api/sessions/{session_id}/model"),
    ("GET", "/api/model/options"),
    ("GET", "/v1/runs/{run_id}"),
    ("POST", "/v1/runs/{run_id}/approval"),
    ("POST", "/v1/runs/{run_id}/stop"),
    ("POST", "/v1/runs/{run_id}/steer"),
    ("POST", "/v1/runs/{run_id}/resolve-unknown"),
    ("PATCH", "/api/sessions/{session_id}"),
    ("DELETE", "/api/sessions/{session_id}"),
];

pub struct ProxyMatch {
    pub upstream_path: String,
    pub params: Vec<(String, String)>,
}

impl ProxyMatch {
    fn param(&self, name: &str) -> Option<&str> {
        self.params
            .iter()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
    }
}

/// Like aiohttp's `request.path`: percent-decoded, except an encoded `/`.
pub fn decoded_path(raw: &str) -> String {
    raw.split('/')
        .map(|segment| {
            percent_decode_str(segment)
                .decode_utf8_lossy()
                .replace('/', "%2F")
        })
        .collect::<Vec<_>>()
        .join("/")
}

fn match_template(template: &str, path: &str) -> Option<Vec<(String, String)>> {
    let t_parts: Vec<&str> = template.trim_matches('/').split('/').collect();
    let p_parts: Vec<&str> = path.trim_matches('/').split('/').collect();
    if t_parts.len() != p_parts.len() {
        return None;
    }
    let mut params = Vec::new();
    for (t, p) in t_parts.iter().zip(&p_parts) {
        if let Some(name) = t.strip_prefix('{').and_then(|t| t.strip_suffix('}')) {
            params.push((name.to_string(), p.to_string()));
        } else if t != p {
            return None;
        }
    }
    Some(params)
}

pub fn match_proxy_route(method: &str, path: &str) -> Option<ProxyMatch> {
    let trimmed = path.trim_end_matches('/');
    let normalized = if trimmed.is_empty() { "/" } else { trimmed };
    for (route_method, template) in PROXIED_ROUTES {
        if route_method != method {
            continue;
        }
        let Some(params) = match_template(template, normalized) else {
            continue;
        };
        if !params.iter().all(|(_, v)| core::safe_segment(v)) {
            return None;
        }
        let mut upstream_path = template.to_string();
        for (name, value) in &params {
            upstream_path = upstream_path.replace(&format!("{{{name}}}"), value);
        }
        return Some(ProxyMatch {
            upstream_path,
            params,
        });
    }
    None
}

/// aiohttp's `{name}` placeholder: one non-empty segment without `{}`.
fn route_params(template: &str, path: &str) -> Option<Vec<(String, String)>> {
    let params = match_template(template, path)?;
    let t_count = template.split('/').count();
    if path.split('/').count() != t_count
        || params
            .iter()
            .any(|(_, v)| v.is_empty() || v.contains(['{', '}']))
    {
        return None;
    }
    Some(params)
}

/// The dedicated routes, then the closed catch-all (aiohttp order).
pub async fn dispatch(state: &State, request: Request) -> Handled {
    let method = request.method().as_str().to_string();
    let path = decoded_path(request.uri().path());
    let get_like = method == "GET" || method == "HEAD";
    if get_like && path == "/v1/host/metrics" {
        return crate::host::handle_metrics(state, request).await;
    }
    if method == "GET" {
        match path.as_str() {
            core::TERMINAL_PATH => return crate::terminal::handle(state, request).await,
            core::FILES_LIST_PATH => return crate::files::handle_list(state, request).await,
            core::FILES_CONTENT_PATH => return crate::files::handle_content(state, request).await,
            core::PROJECTS_PATH => return crate::files::handle_projects(state, request).await,
            _ => {}
        }
    }
    if method == "POST"
        && let Some(params) = route_params("/v1/runs/{run_id}/approval", &path)
    {
        return approval(state, request, params[0].1.clone()).await;
    }
    if method == "POST"
        && let Some(params) = route_params("/api/sessions/{session_id}/chat/stream", &path)
    {
        return chat_stream(state, request, params[0].1.clone()).await;
    }
    if get_like
        && let Some(params) = route_params("/api/sessions/{session_id}/runs/{run_id}/stream", &path)
    {
        return reattach(state, request, params[0].1.clone(), params[1].1.clone()).await;
    }
    proxy(state, request, &method, &path).await
}

fn reject_with(on_status: Option<OnStatus>, status: u16, code: &str) -> Response {
    if let Some(callback) = on_status {
        callback(status);
    }
    closed(status, code)
}

fn audit_hook(
    action: &str,
    record: &DeviceRecord,
    fields: Vec<(&'static str, String)>,
) -> OnStatus {
    let action = action.to_string();
    let device = record.device_id.clone();
    Box::new(move |status: u16| {
        let mut all = fields;
        all.push(("status", status.to_string()));
        audit(&action, &device, &all);
    })
}

/// Forwards the client's request (query, content type, accept) with `body`.
async fn proxy_to(
    state: &State,
    headers: &HeaderMap,
    method: Method,
    query: Option<&str>,
    upstream_path: &str,
    body: Option<Bytes>,
    on_status: Option<OnStatus>,
) -> Response {
    forward(
        state,
        Forward {
            method,
            upstream_path: upstream_path.into(),
            query,
            headers,
            body: body.filter(|b| !b.is_empty()),
            on_status,
            transform: None,
            max_buffered_bytes: 0,
        },
    )
    .await
}

async fn proxy(state: &State, request: Request, method: &str, path: &str) -> Handled {
    let Some(record) = lookup(state, request.headers()).await? else {
        return Ok(closed(401, "invalid_credentials"));
    };
    let Some(matched) = match_proxy_route(method, path) else {
        return Ok(closed(403, "endpoint_out_of_scope"));
    };
    if matched.upstream_path != path {
        return Ok(closed(403, "endpoint_out_of_scope"));
    }
    if method == "POST" && matched.upstream_path.ends_with("/approval") {
        return Ok(closed(403, "endpoint_out_of_scope"));
    }
    let last = matched
        .upstream_path
        .rsplit('/')
        .next()
        .unwrap_or("")
        .to_string();
    if method == "POST" && (last == "fork" || last == "model") {
        return session_command(state, request, &record, &matched).await;
    }
    let (parts, body) = request.into_parts();
    let query = parts.uri.query();
    let method_value = parts.method.clone();
    if method == "GET" && matched.upstream_path == "/api/model/options" {
        if !record.has_permission("chat") {
            return Ok(closed(403, "device_scope_denied"));
        }
        match read_body(body).await {
            Ok(b) if !b.is_empty() => return Ok(closed(400, "body_not_allowed")),
            Err(BodyError::TooLarge) => return Ok(closed(413, "body_too_large")),
            _ => {}
        }
        let hook = audit_hook("model_options", &record, vec![]);
        return Ok(proxy_to(
            state,
            &parts.headers,
            method_value,
            query,
            &matched.upstream_path,
            None,
            Some(hook),
        )
        .await);
    }
    if matched.param("session_id").is_some() && (method == "PATCH" || method == "DELETE") {
        return session_management(
            state,
            &parts.headers,
            body,
            method_value,
            query,
            &record,
            &matched,
        )
        .await;
    }
    if method == "POST" && matched.upstream_path.ends_with("/resolve-unknown") {
        return resolve_unknown(state, &parts.headers, body, query, &record, &matched).await;
    }
    let mut on_status: Option<OnStatus> = None;
    if method == "POST" && matched.upstream_path == "/api/sessions" {
        on_status = Some(audit_hook("session_create", &record, vec![]));
    } else if method == "POST"
        && let Some(run_id) = matched.param("run_id")
        && AUDITED_RUN_ACTIONS.contains(&last.as_str())
    {
        on_status = Some(audit_hook(
            &last,
            &record,
            vec![("run", run_id.to_string())],
        ));
    }
    let body = match read_body(body).await {
        Ok(body) => body,
        Err(BodyError::TooLarge) => return Ok(reject_with(on_status, 413, "body_too_large")),
        // A body cut off mid-upload is never forwarded as an empty one.
        Err(BodyError::Broken) => return Ok(reject_with(on_status, 400, "body_incomplete")),
    };
    if method == "GET" && matched.upstream_path.ends_with("/messages") {
        return Ok(forward(
            state,
            Forward {
                method: method_value,
                upstream_path: matched.upstream_path.clone(),
                query,
                headers: &parts.headers,
                body: Some(body).filter(|b| !b.is_empty()),
                on_status: None,
                transform: Some(history::strip_history_images),
                max_buffered_bytes: MAX_HISTORY_BODY_BYTES,
            },
        )
        .await);
    }
    Ok(proxy_to(
        state,
        &parts.headers,
        method_value,
        query,
        &matched.upstream_path,
        Some(body),
        on_status,
    )
    .await)
}

fn parse_json(raw: &[u8]) -> Option<Value> {
    serde_json::from_slice(raw).ok()
}

async fn resolve_unknown(
    state: &State,
    headers: &HeaderMap,
    body: Body,
    query: Option<&str>,
    record: &DeviceRecord,
    matched: &ProxyMatch,
) -> Handled {
    let run_id = matched.param("run_id").unwrap_or("").to_string();
    let hook = || audit_hook("resolve_unknown", record, vec![("run", run_id.clone())]);
    let raw = match read_body(body).await {
        Ok(raw) => raw,
        Err(BodyError::TooLarge) => return Ok(reject_with(Some(hook()), 413, "body_too_large")),
        Err(BodyError::Broken) => Bytes::new(),
    };
    let Some(parsed) = parse_json(&raw) else {
        return Ok(reject_with(Some(hook()), 400, "invalid_json"));
    };
    if let Err(code) = core::validate_resolve_unknown_body(&parsed) {
        return Ok(reject_with(Some(hook()), 400, code));
    }
    let canonical = json!({
        "admission_id": parsed["admission_id"],
        "execution_generation": parsed["execution_generation"],
    });
    let body = Bytes::from(canonical.to_string());
    Ok(proxy_to(
        state,
        headers,
        Method::POST,
        query,
        &matched.upstream_path,
        Some(body),
        Some(hook()),
    )
    .await)
}

async fn session_command(
    state: &State,
    request: Request,
    record: &DeviceRecord,
    matched: &ProxyMatch,
) -> Handled {
    let is_model = matched.upstream_path.ends_with("/model");
    let action = if is_model {
        "session_model_lock"
    } else {
        "session_fork"
    };
    let hook = || audit_hook(action, record, vec![]);
    if !record.has_permission("chat") {
        return Ok(reject_with(Some(hook()), 403, "device_scope_denied"));
    }
    let (parts, body) = request.into_parts();
    let raw = match read_body(body).await {
        Ok(raw) => raw,
        Err(BodyError::TooLarge) => return Ok(reject_with(Some(hook()), 413, "body_too_large")),
        Err(BodyError::Broken) => Bytes::new(),
    };
    if raw.len() > 4096 {
        return Ok(reject_with(Some(hook()), 413, "body_too_large"));
    }
    let Some(parsed) = parse_json(&raw) else {
        return Ok(reject_with(Some(hook()), 400, "invalid_json"));
    };
    if is_model {
        if let Err(code) = core::validate_session_model_body(&parsed) {
            return Ok(reject_with(Some(hook()), 400, code));
        }
    } else if !parsed.as_object().is_some_and(Map::is_empty) {
        return Ok(reject_with(Some(hook()), 400, "invalid_fields"));
    }
    let body = Bytes::from(parsed.to_string());
    let query = parts.uri.query();
    Ok(proxy_to(
        state,
        &parts.headers,
        Method::POST,
        query,
        &matched.upstream_path,
        Some(body),
        Some(hook()),
    )
    .await)
}

async fn session_management(
    state: &State,
    headers: &HeaderMap,
    body: Body,
    method: Method,
    query: Option<&str>,
    record: &DeviceRecord,
    matched: &ProxyMatch,
) -> Handled {
    let session_id = matched.param("session_id").unwrap_or("").to_string();
    let mut action = if method == Method::DELETE {
        "session_delete"
    } else {
        "session_archive"
    };
    if !record.has_permission("chat") {
        return Ok(closed(403, "device_scope_denied"));
    }
    let hook = |action: &str| audit_hook(action, record, vec![("session", session_id.clone())]);
    let raw = match read_body(body).await {
        Ok(raw) => raw,
        Err(BodyError::TooLarge) => {
            return Ok(reject_with(Some(hook(action)), 413, "body_too_large"));
        }
        Err(BodyError::Broken) => {
            return Ok(reject_with(Some(hook(action)), 400, "body_incomplete"));
        }
    };
    let body = if method == Method::DELETE {
        if !raw.is_empty() {
            return Ok(reject_with(Some(hook(action)), 400, "body_not_allowed"));
        }
        Bytes::new()
    } else {
        let Some(parsed) = parse_json(&raw) else {
            return Ok(reject_with(Some(hook(action)), 400, "invalid_json"));
        };
        if let Err(code) = core::validate_session_patch_body(&parsed) {
            return Ok(reject_with(Some(hook(action)), 400, code));
        }
        if parsed.get("title").is_some() {
            action = "session_title";
        }
        Bytes::from(parsed.to_string())
    };
    Ok(proxy_to(
        state,
        headers,
        method,
        query,
        &matched.upstream_path,
        Some(body),
        Some(hook(action)),
    )
    .await)
}

// -- approval ----------------------------------------------------------------

async fn approval(state: &State, request: Request, run_id: String) -> Handled {
    let Some(record) = lookup(state, request.headers()).await? else {
        return Ok(closed(401, "invalid_credentials"));
    };
    if !core::is_safe_run_id(&run_id) {
        return Ok(closed(400, "invalid_run_id"));
    }
    let rejected = |status: u16, code: &str| {
        audit(
            "approval",
            &record.device_id,
            &[
                ("run", run_id.clone()),
                ("choice", "rejected".into()),
                ("status", status.to_string()),
            ],
        );
        closed(status, code)
    };
    let (parts, body) = request.into_parts();
    let raw = match read_body(body).await {
        Ok(raw) => raw,
        Err(BodyError::TooLarge) => return Ok(closed(413, "body_too_large")),
        Err(BodyError::Broken) => return Ok(rejected(400, "invalid_json")),
    };
    let Some(body) = parse_json(&raw) else {
        return Ok(rejected(400, "invalid_json"));
    };
    if let Err(code) = core::validate_approval_body(&body) {
        return Ok(rejected(400, code));
    }
    let choice = body["choice"].as_str().unwrap_or("").to_string();
    let mut canonical = Map::new();
    canonical.insert("choice".into(), body["choice"].clone());
    canonical.insert("request_id".into(), body["request_id"].clone());
    if let Some(generation) = run_execution_generation(state, &run_id).await {
        canonical.insert("execution_generation".into(), Value::from(generation));
    }
    let payload = Bytes::from(Value::Object(canonical).to_string());
    let hook = audit_hook(
        "approval",
        &record,
        vec![("run", run_id.clone()), ("choice", choice)],
    );
    let path = format!("/v1/runs/{run_id}/approval");
    Ok(proxy_to(
        state,
        &parts.headers,
        Method::POST,
        parts.uri.query(),
        &path,
        Some(payload),
        Some(hook),
    )
    .await)
}

/// The run's integer `execution_generation`, or None (one read, no retry).
async fn run_execution_generation(state: &State, run_id: &str) -> Option<i64> {
    let response = state
        .client
        .get(format!("{}/v1/runs/{run_id}", state.upstream))
        .header(
            header::AUTHORIZATION,
            format!("Bearer {}", state.master_key),
        )
        .header(header::ACCEPT, "application/json")
        .timeout(Duration::from_secs(15))
        .send()
        .await
        .ok()?;
    if response.status().as_u16() != 200 {
        return None;
    }
    let raw = read_upstream_bounded(response, 64 * 1024).await.ok()??;
    match serde_json::from_slice::<Value>(&raw)
        .ok()?
        .get("execution_generation")?
    {
        Value::Number(n) if n.is_i64() => n.as_i64(),
        _ => None,
    }
}

// -- chat stream takeover ------------------------------------------------------

async fn chat_stream(state: &State, request: Request, session_id: String) -> Handled {
    let Some(record) = lookup(state, request.headers()).await? else {
        return Ok(closed(401, "invalid_credentials"));
    };
    if !core::safe_segment(&session_id) {
        return Ok(closed(400, "invalid_session_id"));
    }
    let (parts, body) = request.into_parts();
    if !crate::server::query_keys(parts.uri.query()).is_empty() {
        return Ok(closed(403, "query_not_allowed"));
    }
    audit(
        "chat",
        &record.device_id,
        &[("session", session_id.clone())],
    );
    let body = match read_body(body).await {
        Ok(body) => body,
        Err(BodyError::TooLarge) => {
            audit(
                "chat",
                &record.device_id,
                &[("session", session_id.clone()), ("status", "413".into())],
            );
            return Ok(closed(413, "body_too_large"));
        }
        // Never start a run from a cut-off request body.
        Err(BodyError::Broken) => return Ok(closed(400, "body_incomplete")),
    };
    let Ok(buffer) = state.runhub.create_pending(&session_id) else {
        let mut response = closed(503, "bridge_busy");
        response
            .headers_mut()
            .insert(header::RETRY_AFTER, "5".parse().unwrap());
        return Ok(response);
    };
    let accept = parts
        .headers
        .get(header::ACCEPT)
        .cloned()
        .unwrap_or_else(|| "text/event-stream".parse().unwrap());
    let content_type = parts.headers.get(header::CONTENT_TYPE).cloned();
    let path = format!("/api/sessions/{session_id}/chat/stream");
    let pump_state = state.clone();
    let pump_buffer = buffer.clone();
    tokio::spawn(async move {
        pump_chat_stream(&pump_state, &pump_buffer, &path, body, accept, content_type).await;
        pump_buffer.finish();
    });
    Ok(stream_buffer_to_client(state, &parts.headers, buffer, -1).await)
}

fn relay_frame(state: &State, buffer: &Arc<RunBuffer>, frame: Frame) -> bool {
    let frame = sse::strip_unused(sse::normalize_approval(sse::redact_echo(frame)));
    if frame.raw.len() > MAX_FRAME_BYTES {
        return false;
    }
    buffer.append(&frame);
    if buffer.run_id().is_some() {
        state.runhub.register(buffer);
    }
    true
}

fn oversized_frame(buffer: &RunBuffer, limit: usize) {
    if buffer.is_pending() {
        buffer.fail(
            502,
            "application/json",
            Bytes::from_static(b"{\"error\":{\"code\":\"upstream_frame_too_large\"}}"),
        );
    } else {
        buffer.set_abort_reason("upstream_frame_too_large");
        tracing::warn!("upstream SSE frame exceeded {limit} bytes");
    }
}

fn fail_code(buffer: &RunBuffer, code: &str) {
    buffer.fail(
        502,
        "application/json",
        Bytes::from(json!({"error": {"code": code}}).to_string()),
    );
}

async fn pump_chat_stream(
    state: &State,
    buffer: &Arc<RunBuffer>,
    path: &str,
    body: Bytes,
    accept: header::HeaderValue,
    content_type: Option<header::HeaderValue>,
) {
    let mut builder = state
        .client
        .post(format!("{}{path}", state.upstream))
        .header(
            header::AUTHORIZATION,
            format!("Bearer {}", state.master_key),
        )
        .header(header::ACCEPT, accept);
    if let Some(content_type) = content_type {
        builder = builder.header(header::CONTENT_TYPE, content_type);
    }
    if !body.is_empty() {
        builder = builder.body(body);
    }
    let Ok(upstream) = builder.send().await else {
        return fail_code(buffer, "upstream_unavailable");
    };
    let status = upstream.status().as_u16();
    if (300..400).contains(&status) {
        return fail_code(buffer, "upstream_redirect");
    }
    let content_type = upstream
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("application/json")
        .to_string();
    if status != 200 || !content_type.contains("text/event-stream") {
        match read_upstream_bounded(upstream, MAX_UPSTREAM_ERROR_BODY_BYTES).await {
            Ok(Some(payload)) => buffer.fail(
                status,
                &content_type,
                redact_master_key(&state.master_key, payload),
            ),
            Ok(None) => fail_code(buffer, "upstream_error_too_large"),
            Err(()) => {}
        }
        return;
    }
    let mut decoder = Decoder::new(MAX_ECHO_FRAME_BYTES);
    let mut chunks = upstream.bytes_stream();
    while let Some(Ok(chunk)) = chunks.next().await {
        match decoder.feed(&chunk) {
            Ok(frames) => {
                for frame in frames {
                    if !relay_frame(state, buffer, frame) {
                        return oversized_frame(buffer, MAX_FRAME_BYTES);
                    }
                }
            }
            Err(too_large) => {
                for frame in too_large.frames {
                    if !relay_frame(state, buffer, frame) {
                        return oversized_frame(buffer, MAX_FRAME_BYTES);
                    }
                }
                return oversized_frame(buffer, too_large.limit);
            }
        }
    }
}

fn parse_after_seq(query: Option<&str>) -> Option<i64> {
    let raw = query.and_then(|q| {
        url::form_urlencoded::parse(q.as_bytes())
            .find(|(k, _)| k == "after_seq")
            .map(|(_, v)| v.into_owned())
    });
    let Some(raw) = raw else { return Some(-1) };
    let trimmed = raw.trim();
    let digits = trimmed.strip_prefix(['+', '-']).unwrap_or(trimmed);
    if digits.is_empty() || !digits.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    let value = trimmed
        .parse::<i128>()
        .unwrap_or(if trimmed.starts_with('-') {
            i128::MIN
        } else {
            i128::MAX
        });
    (value >= -1).then(|| value.min(i64::MAX as i128) as i64)
}

async fn reattach(state: &State, request: Request, session_id: String, run_id: String) -> Handled {
    if lookup(state, request.headers()).await?.is_none() {
        return Ok(closed(401, "invalid_credentials"));
    }
    if !core::safe_segment(&session_id) || !core::is_safe_run_id(&run_id) {
        return Ok(closed(404, "run_not_found"));
    }
    let Some(after_seq) = parse_after_seq(request.uri().query()) else {
        return Ok(closed(400, "invalid_after_seq"));
    };
    let Some(buffer) = state.runhub.lookup(&session_id, &run_id) else {
        if state.runhub.belongs_to_other_session(&session_id, &run_id) {
            return Ok(closed(404, "run_not_found"));
        }
        return Ok(indeterminate_run(state, &run_id).await);
    };
    Ok(stream_buffer_to_client(state, request.headers(), buffer, after_seq).await)
}

/// No buffer: only a confirmed upstream 404 is "not found"; never fabricate.
async fn indeterminate_run(state: &State, run_id: &str) -> Response {
    let response = state
        .client
        .get(format!("{}/v1/runs/{run_id}", state.upstream))
        .header(
            header::AUTHORIZATION,
            format!("Bearer {}", state.master_key),
        )
        .timeout(Duration::from_secs(10))
        .send()
        .await;
    match response.map(|r| r.status().as_u16()) {
        Ok(200) => closed(409, "resume_gap"),
        Ok(404) => closed(404, "run_not_found"),
        Ok(status) if (300..400).contains(&status) => closed(502, "upstream_redirect"),
        _ => closed(502, "run_state_indeterminate"),
    }
}

async fn stream_buffer_to_client(
    state: &State,
    headers: &HeaderMap,
    buffer: Arc<RunBuffer>,
    after_seq: i64,
) -> Response {
    let (subscriber, replay, terminal) = match buffer.attach(after_seq).await {
        Attached::Failed {
            status,
            content_type,
            body,
        } => {
            return Response::builder()
                .status(status)
                .header(header::CONTENT_TYPE, content_type)
                .body(Body::from(body))
                .unwrap();
        }
        Attached::Gap => return closed(409, "resume_gap"),
        Attached::Stream {
            subscriber,
            replay,
            terminal,
        } => (subscriber, replay, terminal),
    };
    let (tx, rx) = mpsc::channel::<Bytes>(16);
    let state = state.clone();
    let token = crate::server::device_token(headers);
    tokio::spawn(async move {
        let send = |bytes: Bytes| tx.send(bytes);
        let mut alive = true;
        for raw in replay {
            if send(raw).await.is_err() {
                alive = false;
                break;
            }
        }
        if alive && terminal && subscriber.is_empty() {
            if let Some(reason) = buffer.abort_reason() {
                let _ = send(Bytes::from(format!(": bridge_{reason}\n\n"))).await;
            }
            alive = false;
        }
        let mut next_auth_check = tokio::time::Instant::now() + REVOKE_CHECK_INTERVAL;
        while alive {
            if tokio::time::Instant::now() >= next_auth_check {
                let token = token.clone();
                let valid =
                    crate::server::db(&state, move |s| s.lookup_device(token.as_deref())).await;
                // A store failure ends the stream closed as well.
                if !matches!(valid, Ok(Some(_))) {
                    let _ = send(Bytes::from_static(b": bridge_revoked\n\n")).await;
                    break;
                }
                next_auth_check = tokio::time::Instant::now() + REVOKE_CHECK_INTERVAL;
            }
            let item = match tokio::time::timeout(RUN_STREAM_KEEPALIVE, subscriber.get()).await {
                Ok(item) => item,
                Err(_) => {
                    alive = send(Bytes::from_static(b": keepalive\n\n")).await.is_ok();
                    continue;
                }
            };
            match item {
                Item::Frame(raw) => alive = send(raw).await.is_ok(),
                Item::Overflow => {
                    let _ = send(Bytes::from_static(b": bridge_overflow\n\n")).await;
                    break;
                }
                Item::End => {
                    if let Some(reason) = buffer.abort_reason() {
                        let _ = send(Bytes::from(format!(": bridge_{reason}\n\n"))).await;
                    }
                    break;
                }
            }
        }
        buffer.detach(&subscriber);
    });
    let stream = futures_util::stream::unfold(rx, |mut rx| async move {
        rx.recv()
            .await
            .map(|bytes| (Ok::<_, Infallible>(bytes), rx))
    });
    Response::builder()
        .status(200)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .header(header::CACHE_CONTROL, "no-cache")
        .body(Body::from_stream(stream))
        .unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_allowlisted_routes_match() {
        assert!(match_proxy_route("GET", "/api/sessions/s1/messages").is_some());
        assert!(
            match_proxy_route("GET", "/api/sessions/s1/")
                .unwrap()
                .upstream_path
                == "/api/sessions/s1"
        );
        assert!(match_proxy_route("POST", "/api/sessions/s1/messages").is_none());
        assert!(match_proxy_route("GET", "/api/sessions/a%2Fb").is_none());
        assert!(match_proxy_route("GET", "/v1/admin").is_none());
    }

    #[test]
    fn the_path_is_decoded_except_a_slash() {
        assert_eq!(
            decoded_path("/api/sessions/a%2Fb/x%41"),
            "/api/sessions/a%2Fb/xA"
        );
    }

    #[test]
    fn after_seq_follows_python_int() {
        assert_eq!(parse_after_seq(None), Some(-1));
        assert_eq!(parse_after_seq(Some("after_seq=+5")), Some(5));
        assert_eq!(parse_after_seq(Some("after_seq=-1")), Some(-1));
        assert_eq!(parse_after_seq(Some("after_seq=-2")), None);
        assert_eq!(parse_after_seq(Some("after_seq=x")), None);
    }
}
