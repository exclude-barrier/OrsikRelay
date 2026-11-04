//! The loopback HTTP surface. Routing is done by hand in one dispatcher so
//! the order and the fall-through match the Python bridge exactly: dedicated
//! routes first (method-specific), everything else reaches the closed proxy
//! catch-all, which checks the device key before the allowlist.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::body::{Body, Bytes};
use axum::extract::{ConnectInfo, Request};
use axum::http::{HeaderMap, Method, StatusCode, header};
use axum::response::{IntoResponse, Response};
use futures_util::StreamExt;
use serde_json::{Value, json};

use crate::core::{self, DeviceRecord};
use crate::pairing::RateLimiter;
use crate::store::{self, Store, StoreUnavailable};

/// Same bound as the official api_server request limit.
pub const MAX_BODY_BYTES: usize = 10_000_000;
/// Bound of a buffered (non-SSE) upstream body.
pub const MAX_BUFFERED_BODY_BYTES: usize = 16 * 1024 * 1024;
/// Bound of a non-SSE upstream error body on the chat stream.
pub const MAX_UPSTREAM_ERROR_BODY_BYTES: usize = 64 * 1024;

pub struct AppState {
    pub upstream: String,
    pub master_key: String,
    pub store: Arc<Store>,
    pub client: reqwest::Client,
    pub pair_limiter: Mutex<RateLimiter>,
    pub trust_tailscale_identity: bool,
    pub host_services: Vec<String>,
    pub terminal_command: Option<Vec<String>>,
    pub files_root: Option<PathBuf>,
    pub runhub: crate::runhub::RunHub,
    pub active_terminals: std::sync::atomic::AtomicUsize,
    /// One snapshot serves every request within its max age.
    pub host_metrics: tokio::sync::Mutex<Option<(std::time::Instant, Value)>>,
    pub projects: tokio::sync::Mutex<Option<(std::time::Instant, Value)>>,
}

pub type State = Arc<AppState>;

/// An upstream client that never follows a redirect and never uses a proxy.
pub fn upstream_client() -> reqwest::Client {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .connect_timeout(Duration::from_secs(10))
        .read_timeout(Duration::from_secs(600))
        .build()
        .expect("http client")
}

/// The closed error envelope.
pub fn closed(status: u16, code: &str) -> Response {
    let status = StatusCode::from_u16(status).unwrap_or(StatusCode::BAD_GATEWAY);
    (
        status,
        [(header::CONTENT_TYPE, "application/json; charset=utf-8")],
        json!({"error": {"code": code}}).to_string(),
    )
        .into_response()
}

pub fn json_response(status: u16, body: &Value) -> Response {
    let status = StatusCode::from_u16(status).unwrap_or(StatusCode::OK);
    (
        status,
        [(header::CONTENT_TYPE, "application/json; charset=utf-8")],
        body.to_string(),
    )
        .into_response()
}

/// Every store failure is answered closed (503), never fail open.
pub enum Failure {
    Store,
}

impl From<StoreUnavailable> for Failure {
    fn from(_: StoreUnavailable) -> Self {
        Failure::Store
    }
}

impl IntoResponse for Failure {
    fn into_response(self) -> Response {
        tracing::error!("Device store unavailable; refusing request closed");
        closed(503, "store_unavailable")
    }
}

pub type Handled = Result<Response, Failure>;

/// Runs a blocking store call off the async workers.
pub async fn db<T: Send + 'static>(
    state: &State,
    call: impl FnOnce(&Store) -> Result<T, StoreUnavailable> + Send + 'static,
) -> Result<T, Failure> {
    let store = state.store.clone();
    tokio::task::spawn_blocking(move || call(&store))
        .await
        .map_err(|_| Failure::Store)?
        .map_err(Failure::from)
}

pub fn device_token(headers: &HeaderMap) -> Option<String> {
    let value = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    if value.len() < 7 || !value[..7].eq_ignore_ascii_case("bearer ") {
        return None;
    }
    let token = value[7..].trim();
    (!token.is_empty()).then(|| token.to_string())
}

pub async fn lookup(state: &State, headers: &HeaderMap) -> Result<Option<DeviceRecord>, Failure> {
    let token = device_token(headers);
    db(state, move |s| s.lookup_device(token.as_deref())).await
}

// -- audit -----------------------------------------------------------------

fn looks_like_device_key(value: &str) -> bool {
    value.match_indices("hd_").any(|(i, _)| {
        value[i + 3..]
            .chars()
            .take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
            .count()
            >= 20
    })
}

/// One event per line; a value shaped like a device key never reaches the log.
pub fn audit_value(value: &str) -> String {
    if looks_like_device_key(value) {
        return "[redacted]".into();
    }
    let printable = value.chars().all(|c| !c.is_control());
    if !printable || value.contains(' ') || value.contains('=') {
        let mut out = String::new();
        for c in value.chars() {
            match c {
                ' ' => out.push_str("\\x20"),
                '=' => out.push_str("\\x3d"),
                c if c.is_control() || !c.is_ascii() => out.extend(c.escape_unicode()),
                c => out.push(c),
            }
        }
        return out;
    }
    value.to_string()
}

pub fn audit(action: &str, device_id: &str, fields: &[(&str, String)]) {
    let detail: String = fields
        .iter()
        .map(|(k, v)| format!(" {k}={}", audit_value(v)))
        .collect();
    tracing::info!(target: "orsik_mobile_bridge::audit", "audit action={action} device={device_id}{detail}");
}

// -- bodies ----------------------------------------------------------------

pub enum BodyError {
    TooLarge,
    Broken,
}

/// The request body, bounded to MAX_BODY_BYTES.
pub async fn read_body(body: Body) -> Result<Bytes, BodyError> {
    axum::body::to_bytes(body, MAX_BODY_BYTES)
        .await
        .map_err(|error| {
            let mut source: Option<&dyn std::error::Error> = Some(&error);
            while let Some(err) = source {
                if err.is::<http_body_util::LengthLimitError>() {
                    return BodyError::TooLarge;
                }
                source = err.source();
            }
            BodyError::Broken
        })
}

fn media_type(value: Option<&str>) -> String {
    value
        .unwrap_or("")
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase()
}

pub fn request_media_type(headers: &HeaderMap) -> String {
    media_type(
        headers
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok()),
    )
}

/// A reflected master key must never reach the device.
pub fn redact_master_key(master_key: &str, payload: Bytes) -> Bytes {
    if master_key.is_empty() {
        return payload;
    }
    let key = master_key.as_bytes();
    if !payload.windows(key.len()).any(|w| w == key) {
        return payload;
    }
    let mut out = Vec::with_capacity(payload.len());
    let mut i = 0;
    while i < payload.len() {
        if payload[i..].starts_with(key) {
            out.extend_from_slice(b"[redacted]");
            i += key.len();
        } else {
            out.push(payload[i]);
            i += 1;
        }
    }
    Bytes::from(out)
}

// -- the generic proxy -----------------------------------------------------

pub type OnStatus = Box<dyn FnOnce(u16) + Send>;

pub struct Forward<'a> {
    pub method: Method,
    pub upstream_path: String,
    pub query: Option<&'a str>,
    pub headers: &'a HeaderMap,
    pub body: Option<Bytes>,
    pub on_status: Option<OnStatus>,
    pub transform: Option<fn(Bytes) -> Bytes>,
    pub max_buffered_bytes: usize,
}

/// Only the session reads may carry query keys, and only these.
pub fn allowed_query_keys(method: &Method, upstream_path: &str) -> &'static [&'static str] {
    if method != Method::GET {
        return &[];
    }
    let path = upstream_path.trim_end_matches('/');
    if path == "/api/sessions" || path.starts_with("/api/sessions/") {
        return &["limit", "offset", "order", "include_children"];
    }
    &[]
}

pub fn query_keys(query: Option<&str>) -> Vec<String> {
    query
        .map(|q| {
            url::form_urlencoded::parse(q.as_bytes())
                .map(|(k, _)| k.into_owned())
                .collect()
        })
        .unwrap_or_default()
}

/// Forward one request upstream and relay the answer. Redirects are never
/// followed; SSE is streamed; anything else is buffered, transformed (only on
/// 200) and redacted before the status goes out.
pub async fn forward(state: &State, request: Forward<'_>) -> Response {
    let Forward {
        method,
        upstream_path,
        query,
        headers,
        body,
        mut on_status,
        transform,
        max_buffered_bytes,
    } = request;
    let mut closed_with = |status: u16, code: &str| {
        if let Some(callback) = on_status.take() {
            callback(status);
        }
        closed(status, code)
    };
    let allowed = allowed_query_keys(&method, &upstream_path);
    if query_keys(query)
        .iter()
        .any(|key| !allowed.contains(&key.as_str()))
    {
        return closed_with(403, "query_not_allowed");
    }
    let mut url = format!("{}{}", state.upstream, upstream_path);
    if let Some(q) = query.filter(|q| !q.is_empty()) {
        url.push('?');
        url.push_str(q);
    }
    let mut builder = state.client.request(method, &url).header(
        header::AUTHORIZATION,
        format!("Bearer {}", state.master_key),
    );
    if let Some(ct) = headers.get(header::CONTENT_TYPE) {
        builder = builder.header(header::CONTENT_TYPE, ct);
    }
    if let Some(accept) = headers.get(header::ACCEPT) {
        builder = builder.header(header::ACCEPT, accept);
    }
    if let Some(body) = body {
        builder = builder.body(body);
    }
    let upstream = match builder.send().await {
        Ok(response) => response,
        Err(_) => return closed_with(502, "upstream_unavailable"),
    };
    let status = upstream.status().as_u16();
    if (300..400).contains(&status) {
        return closed_with(502, "upstream_redirect");
    }
    let content_type = upstream
        .headers()
        .get(header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("application/json")
        .to_string();
    let media = media_type(Some(&content_type));
    if content_type.contains("text/event-stream") {
        if let Some(callback) = on_status.take() {
            callback(status);
        }
        let stream = upstream
            .bytes_stream()
            .take_while(|chunk| std::future::ready(chunk.is_ok()));
        return Response::builder()
            .status(status)
            .header(header::CONTENT_TYPE, media)
            .header(header::CACHE_CONTROL, "no-cache")
            .body(Body::from_stream(stream))
            .unwrap();
    }
    let limit = if max_buffered_bytes == 0 {
        MAX_BUFFERED_BODY_BYTES
    } else {
        max_buffered_bytes
    };
    let payload = match read_upstream_bounded(upstream, limit).await {
        Ok(Some(payload)) => payload,
        Ok(None) => return closed_with(502, "upstream_error_too_large"),
        Err(()) => return closed_with(502, "upstream_unavailable"),
    };
    let master_key = state.master_key.clone();
    let transform = if status == 200 { transform } else { None };
    let payload = match tokio::task::spawn_blocking(move || {
        let payload = match transform {
            Some(transform) => transform(payload),
            None => payload,
        };
        redact_master_key(&master_key, payload)
    })
    .await
    {
        Ok(payload) => payload,
        Err(_) => return closed_with(502, "upstream_unavailable"),
    };
    if let Some(callback) = on_status.take() {
        callback(status);
    }
    let mut response = Response::builder().status(status);
    if !media.is_empty() {
        response = response.header(header::CONTENT_TYPE, media);
    }
    response.body(Body::from(payload)).unwrap()
}

/// The upstream body up to `limit` bytes; Ok(None) when it would exceed it.
pub async fn read_upstream_bounded(
    upstream: reqwest::Response,
    limit: usize,
) -> Result<Option<Bytes>, ()> {
    let mut data = Vec::new();
    let mut stream = upstream.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| ())?;
        data.extend_from_slice(&chunk);
        if data.len() > limit {
            return Ok(None);
        }
    }
    Ok(Some(Bytes::from(data)))
}

// -- dispatch ----------------------------------------------------------------

pub async fn dispatch(
    axum::extract::State(state): axum::extract::State<State>,
    request: Request,
) -> Response {
    let method = request.method().clone();
    let path = request.uri().path().to_string();
    let handled = match (method.as_str(), path.as_str()) {
        ("GET" | "HEAD", "/health") => health(&state, request).await,
        ("GET" | "HEAD", "/v1/capabilities") => capabilities(&state, request).await,
        ("POST", "/v1/devices/pair") => pair(&state, request).await,
        ("POST", "/v1/devices/self/revoke") => self_revoke(&state, request).await,
        _ => crate::routes::dispatch(&state, request).await,
    };
    handled.unwrap_or_else(IntoResponse::into_response)
}

async fn health(state: &State, request: Request) -> Handled {
    let headers = request.headers();
    let length = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok());
    if !matches!(length, None | Some("0")) || headers.contains_key(header::TRANSFER_ENCODING) {
        return Ok(closed(400, "body_not_allowed"));
    }
    Ok(forward(
        state,
        Forward {
            method: Method::GET,
            upstream_path: "/health".into(),
            query: request.uri().query(),
            headers,
            body: None,
            on_status: None,
            transform: None,
            max_buffered_bytes: 0,
        },
    )
    .await)
}

async fn capabilities(state: &State, request: Request) -> Handled {
    let Some(record) = lookup(state, request.headers()).await? else {
        return Ok(closed(401, "invalid_credentials"));
    };
    let terminal = state.terminal_command.is_some() && record.terminal_allowed;
    Ok(json_response(
        200,
        &core::build_capabilities(terminal, state.files_root.is_some()),
    ))
}

/// Rate-limit identity only; a valid one-time code is still mandatory.
fn pairing_client_key(state: &State, request: &Request) -> String {
    let peer = request
        .extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .map(|c| c.0.ip());
    if state.trust_tailscale_identity && peer.is_some_and(crate::config::is_loopback) {
        let logins: Vec<&str> = request
            .headers()
            .get_all("Tailscale-User-Login")
            .iter()
            .filter_map(|v| v.to_str().ok())
            .collect();
        if logins.len() == 1
            && !logins[0].is_empty()
            && logins[0].len() <= 1024
            && !logins[0].trim().is_empty()
        {
            return format!("serve:{}", core::sha256_hex(logins[0]));
        }
    }
    let peer = peer
        .map(|ip| ip.to_string())
        .unwrap_or_else(|| "unknown".into());
    format!("peer:{}", core::sha256_hex(&peer))
}

async fn pair(state: &State, request: Request) -> Handled {
    let key = pairing_client_key(state, &request);
    if !state.pair_limiter.lock().unwrap().allow(&key, store::now()) {
        audit("pair", "-", &[("status", "429".into())]);
        return Ok(closed(429, "rate_limited"));
    }
    let is_json = request_media_type(request.headers()) == "application/json";
    let body = match read_body(request.into_body()).await {
        Ok(body) => body,
        Err(BodyError::TooLarge) => {
            audit("pair", "-", &[("status", "413".into())]);
            return Ok(closed(413, "body_too_large"));
        }
        Err(BodyError::Broken) => Bytes::new(),
    };
    let parsed: Option<Value> = if is_json {
        serde_json::from_slice(&body).ok()
    } else {
        None
    };
    let Some(parsed) = parsed else {
        audit("pair", "-", &[("status", "400".into())]);
        return Ok(closed(400, "invalid_json"));
    };
    if let Some(obj) = parsed.as_object()
        && (obj.len() != 1 || !obj.contains_key("code"))
    {
        audit("pair", "-", &[("status", "400".into())]);
        return Ok(closed(400, "invalid_fields"));
    }
    let code = parsed.get("code").cloned().unwrap_or(Value::Null);
    let redeemed = parsed.is_object() && db(state, move |s| s.redeem_code(&code)).await?;
    if !redeemed {
        audit("pair", "-", &[("status", "400".into())]);
        return Ok(closed(400, "pairing_code_invalid"));
    }
    let Some((token, record, issued_at)) =
        db(state, |s| s.mint_device("paired-device", "default")).await?
    else {
        audit("pair", "-", &[("status", "503".into())]);
        return Ok(closed(503, "device_capacity_reached"));
    };
    audit("pair", &record.device_id, &[("status", "201".into())]);
    Ok(json_response(
        201,
        &core::build_credential(&record, &token, issued_at),
    ))
}

async fn self_revoke(state: &State, request: Request) -> Handled {
    let (parts, body) = request.into_parts();
    let token = device_token(&parts.headers);
    let has_body = match read_body(body).await {
        Ok(body) => !body.is_empty(),
        Err(BodyError::TooLarge) => {
            let Some(record) = lookup(state, &parts.headers).await? else {
                return Ok(closed(401, "invalid_credentials"));
            };
            audit("revoke", &record.device_id, &[("status", "413".into())]);
            return Ok(closed(413, "body_too_large"));
        }
        Err(BodyError::Broken) => false,
    };
    if has_body {
        if lookup(state, &parts.headers).await?.is_none() {
            return Ok(closed(401, "invalid_credentials"));
        }
        return Ok(closed(400, "body_not_allowed"));
    }
    let Some(record) = db(state, move |s| s.revoke_device(token.as_deref())).await? else {
        audit("revoke", "-", &[("status", "401".into())]);
        return Ok(closed(401, "invalid_credentials"));
    };
    audit("revoke", &record.device_id, &[("status", "200".into())]);
    Ok(json_response(
        200,
        &json!({"revoked": !record.already_revoked, "device_id": record.device_id}),
    ))
}
