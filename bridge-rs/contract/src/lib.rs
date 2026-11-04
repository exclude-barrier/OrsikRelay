//! Black-box contract harness for the Orsik Mobile Bridge.
//!
//! The suite never links against a bridge. It starts one as a process
//! (`BRIDGE_CMD`, default: the Python bridge via `BRIDGE_PY -m
//! orsik_mobile_bridge`) against a fake Hermes upstream and checks the
//! Mobile-v1 behaviour over HTTP only, so the same tests hold the Python and
//! the Rust bridge to one contract.

use std::collections::HashMap;
use std::net::{SocketAddr, TcpListener as StdListener};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::Router;
use axum::body::{Body, Bytes};
use axum::extract::{Request, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::Response;
use serde_json::Value;
use tokio::process::{Child, Command};
use tokio::sync::mpsc;

/// The Hermes API key the fake upstream expects; it must never reach a device.
pub const MASTER_KEY: &str = "contract-master-key-0123456789abcdef";

/// One request the fake Hermes received.
#[derive(Clone, Debug)]
pub struct Recorded {
    pub method: String,
    pub path: String,
    pub query: Option<String>,
    pub headers: HeaderMap,
    pub body: Bytes,
}

impl Recorded {
    pub fn json(&self) -> Value {
        serde_json::from_slice(&self.body).expect("recorded body is JSON")
    }
}

/// What the fake Hermes answers for one method and path.
#[derive(Clone)]
pub enum Reply {
    /// A complete response.
    Raw {
        status: u16,
        content_type: String,
        body: Vec<u8>,
        headers: Vec<(String, String)>,
    },
    /// An SSE response whose chunks the test sends while it is open; the
    /// stream ends when the sender is dropped.
    Live(Arc<Mutex<Option<mpsc::UnboundedReceiver<Bytes>>>>),
}

impl Reply {
    pub fn json(status: u16, body: Value) -> Self {
        Reply::Raw {
            status,
            content_type: "application/json".into(),
            body: serde_json::to_vec(&body).unwrap(),
            headers: vec![],
        }
    }

    pub fn status(status: u16) -> Self {
        Reply::Raw {
            status,
            content_type: "application/json".into(),
            body: b"{}".to_vec(),
            headers: vec![],
        }
    }

    pub fn sse(chunks: &[&str]) -> Self {
        Reply::Raw {
            status: 200,
            content_type: "text/event-stream".into(),
            body: chunks.concat().into_bytes(),
            headers: vec![],
        }
    }

    pub fn redirect(location: &str) -> Self {
        Reply::Raw {
            status: 302,
            content_type: "text/plain".into(),
            body: vec![],
            headers: vec![("Location".into(), location.into())],
        }
    }

    /// A live SSE stream and the sender that feeds it.
    pub fn live() -> (Self, mpsc::UnboundedSender<Bytes>) {
        let (tx, rx) = mpsc::unbounded_channel();
        (Reply::Live(Arc::new(Mutex::new(Some(rx)))), tx)
    }
}

#[derive(Default)]
struct FakeState {
    replies: HashMap<(String, String), Reply>,
    recorded: Vec<Recorded>,
}

/// A loopback HTTP server standing in for the official Hermes API server.
#[derive(Clone)]
pub struct FakeHermes {
    pub addr: SocketAddr,
    state: Arc<Mutex<FakeState>>,
}

impl FakeHermes {
    pub async fn start() -> Self {
        let state = Arc::new(Mutex::new(FakeState::default()));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let app = Router::new()
            .fallback(fake_handler)
            .with_state(state.clone());
        tokio::spawn(async move {
            axum::serve(listener, app).await.ok();
        });
        let fake = FakeHermes { addr, state };
        fake.reply(
            "GET",
            "/health",
            Reply::json(200, serde_json::json!({"status": "ok"})),
        );
        fake
    }

    pub fn url(&self) -> String {
        format!("http://{}", self.addr)
    }

    /// Answer `method path` (no query) with `reply` from now on.
    pub fn reply(&self, method: &str, path: &str, reply: Reply) {
        self.state
            .lock()
            .unwrap()
            .replies
            .insert((method.into(), path.into()), reply);
    }

    pub fn recorded(&self) -> Vec<Recorded> {
        self.state.lock().unwrap().recorded.clone()
    }

    /// Requests for one path (any method), in arrival order.
    pub fn requests_to(&self, path: &str) -> Vec<Recorded> {
        self.recorded()
            .into_iter()
            .filter(|r| r.path == path)
            .collect()
    }

    pub fn clear(&self) {
        self.state.lock().unwrap().recorded.clear();
    }
}

async fn fake_handler(State(state): State<Arc<Mutex<FakeState>>>, request: Request) -> Response {
    let (parts, body) = request.into_parts();
    let body = axum::body::to_bytes(body, 64 * 1024 * 1024)
        .await
        .unwrap_or_default();
    let path = parts.uri.path().to_string();
    let method = parts.method.to_string();
    let reply = {
        let mut guard = state.lock().unwrap();
        guard.recorded.push(Recorded {
            method: method.clone(),
            path: path.clone(),
            query: parts.uri.query().map(str::to_string),
            headers: parts.headers.clone(),
            body,
        });
        guard.replies.get(&(method, path)).cloned()
    };
    match reply {
        None => Response::builder()
            .status(404)
            .body(Body::from("{\"error\":\"not found\"}"))
            .unwrap(),
        Some(Reply::Raw {
            status,
            content_type,
            body,
            headers,
        }) => {
            let mut builder = Response::builder()
                .status(StatusCode::from_u16(status).unwrap())
                .header("Content-Type", content_type);
            for (name, value) in headers {
                builder = builder.header(name, value);
            }
            builder.body(Body::from(body)).unwrap()
        }
        Some(Reply::Live(slot)) => {
            let rx = slot
                .lock()
                .unwrap()
                .take()
                .expect("a live reply serves one request");
            let stream = futures_util::stream::unfold(rx, |mut rx| async move {
                rx.recv()
                    .await
                    .map(|chunk| (Ok::<_, std::convert::Infallible>(chunk), rx))
            });
            Response::builder()
                .status(200)
                .header("Content-Type", "text/event-stream")
                .body(Body::from_stream(stream))
                .unwrap()
        }
    }
}

/// The Python bridge, whatever bridge is under test (store compatibility).
fn python_command() -> (String, Vec<String>) {
    let python = std::env::var("BRIDGE_PY").unwrap_or_else(|_| "python3".into());
    (python, vec!["-m".into(), "orsik_mobile_bridge".into()])
}

/// How to start the bridge under test.
fn bridge_command() -> (String, Vec<String>) {
    if let Some(cmd) = std::env::var("BRIDGE_CMD")
        .ok()
        .filter(|c| !c.trim().is_empty())
    {
        let mut parts = cmd.split_whitespace().map(str::to_string);
        let program = parts.next().expect("BRIDGE_CMD is empty");
        return (program, parts.collect());
    }
    let python = std::env::var("BRIDGE_PY").unwrap_or_else(|_| "python3".into());
    (python, vec!["-m".into(), "orsik_mobile_bridge".into()])
}

/// A free loopback port never handed out twice in this test process, so two
/// bridges started in parallel cannot race for the same one.
fn free_port() -> u16 {
    static GIVEN: Mutex<Vec<u16>> = Mutex::new(Vec::new());
    loop {
        let port = StdListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let mut given = GIVEN.lock().unwrap();
        if !given.contains(&port) {
            given.push(port);
            return port;
        }
    }
}

/// Settings for one bridge process; every value can be overridden per test.
#[derive(Clone)]
pub struct BridgeConfig {
    pub env: HashMap<String, String>,
    /// Start the Python bridge even when another one is under test.
    pub python: bool,
}

impl BridgeConfig {
    pub fn new(upstream: &FakeHermes, home: &Path) -> Self {
        let mut env = HashMap::new();
        env.insert("BRIDGE_UPSTREAM".into(), upstream.url());
        env.insert("HERMES_API_KEY".into(), MASTER_KEY.into());
        env.insert("BRIDGE_HOME".into(), home.display().to_string());
        env.insert(
            "BRIDGE_STATE_DB".into(),
            home.join("state.db").display().to_string(),
        );
        env.insert("BRIDGE_BIND".into(), "127.0.0.1".into());
        // Fixed, harmless unit names so the host metrics never ask for
        // services of the machine running the suite by surprise.
        env.insert("BRIDGE_HOST_SERVICES".into(), "contract-test-unit".into());
        BridgeConfig { env, python: false }
    }

    /// The same settings for the Python bridge (store compatibility checks).
    pub fn python(mut self) -> Self {
        self.python = true;
        self
    }

    pub fn set(mut self, key: &str, value: &str) -> Self {
        self.env.insert(key.into(), value.into());
        self
    }

    pub fn unset(mut self, key: &str) -> Self {
        self.env.remove(key);
        self
    }
}

fn base_command(config: &BridgeConfig) -> Command {
    let (program, args) = if config.python {
        python_command()
    } else {
        bridge_command()
    };
    let mut command = Command::new(program);
    command.args(args);
    command.env_clear();
    for key in ["PATH", "HOME", "LANG", "PYTHONPATH"] {
        if let Ok(value) = std::env::var(key) {
            command.env(key, value);
        }
    }
    command.envs(&config.env);
    command.kill_on_drop(true);
    command
}

/// The outcome of a bridge process that was expected to stop at start-up.
pub struct Exit {
    pub code: Option<i32>,
    pub stderr: String,
}

/// Starts the bridge and expects it to refuse (a configuration error).
pub async fn start_expecting_refusal(config: &BridgeConfig) -> Exit {
    let mut command = base_command(config);
    command.env("BRIDGE_PORT", free_port().to_string());
    command.stdout(Stdio::null()).stderr(Stdio::piped());
    let output = tokio::time::timeout(Duration::from_secs(20), command.output())
        .await
        .expect("a refusing bridge exits quickly")
        .unwrap();
    Exit {
        code: output.status.code(),
        stderr: String::from_utf8_lossy(&output.stderr).into(),
    }
}

/// A running bridge process.
pub struct Bridge {
    pub config: BridgeConfig,
    pub port: u16,
    child: Child,
    pub client: reqwest::Client,
}

impl Bridge {
    pub async fn start(config: BridgeConfig) -> Self {
        // A port reported free can be taken by another listener before the
        // bridge binds it; the bridge then exits and a fresh port is tried.
        for _ in 0..3 {
            if let Some(bridge) = Self::try_start(config.clone()).await {
                return bridge;
            }
        }
        panic!("bridge exited at start-up three times");
    }

    async fn try_start(config: BridgeConfig) -> Option<Self> {
        let port = free_port();
        let mut command = base_command(&config);
        command.env("BRIDGE_PORT", port.to_string());
        command.stdout(Stdio::null()).stderr(Stdio::null());
        let child = command.spawn().expect("bridge command starts");
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .no_proxy()
            .build()
            .unwrap();
        let mut bridge = Bridge {
            config,
            port,
            child,
            client,
        };
        for _ in 0..200 {
            if let Ok(Some(_)) = bridge.child.try_wait() {
                return None;
            }
            // Only the bridge answers an anonymous capabilities read with its
            // closed 401 envelope; another listener on the port does not.
            if let Ok(response) = bridge.get("/v1/capabilities", None).send().await
                && error_code(response).await == (401, "invalid_credentials".into())
            {
                return Some(bridge);
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        panic!("bridge did not listen on port {port}");
    }

    pub fn url(&self, path: &str) -> String {
        format!("http://127.0.0.1:{}{}", self.port, path)
    }

    /// Runs `bridge admin <args>` against the same store and environment.
    pub async fn admin(&self, args: &[&str]) -> (i32, String, String) {
        let mut command = base_command(&self.config);
        command.arg("admin").args(args);
        command.stdout(Stdio::piped()).stderr(Stdio::piped());
        let output = tokio::time::timeout(Duration::from_secs(30), command.output())
            .await
            .expect("admin command finishes")
            .unwrap();
        (
            output.status.code().unwrap_or(-1),
            String::from_utf8_lossy(&output.stdout).into(),
            String::from_utf8_lossy(&output.stderr).into(),
        )
    }

    /// Issues a one-time pairing code through the operator CLI.
    pub async fn pairing_code(&self) -> String {
        let (code, out, err) = self.admin(&["pairing-code"]).await;
        assert_eq!(code, 0, "pairing-code failed: {err}");
        let line = out
            .lines()
            .find(|l| l.starts_with("Pairing code:"))
            .expect("code line");
        line.trim_start_matches("Pairing code:")
            .split_whitespace()
            .next()
            .unwrap()
            .to_string()
    }

    /// Pairs one device and returns its device key and credential.
    pub async fn pair(&self) -> (String, Value) {
        let code = self.pairing_code().await;
        let response = self
            .client
            .post(self.url("/v1/devices/pair"))
            .json(&serde_json::json!({"code": code}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), 201);
        let credential: Value = response.json().await.unwrap();
        (
            credential["device_key"].as_str().unwrap().to_string(),
            credential,
        )
    }

    pub fn get(&self, path: &str, token: Option<&str>) -> reqwest::RequestBuilder {
        self.authed(self.client.get(self.url(path)), token)
    }

    pub fn post(&self, path: &str, token: Option<&str>) -> reqwest::RequestBuilder {
        self.authed(self.client.post(self.url(path)), token)
    }

    pub fn request(
        &self,
        method: &str,
        path: &str,
        token: Option<&str>,
    ) -> reqwest::RequestBuilder {
        let method = reqwest::Method::from_bytes(method.as_bytes()).unwrap();
        self.authed(self.client.request(method, self.url(path)), token)
    }

    fn authed(
        &self,
        builder: reqwest::RequestBuilder,
        token: Option<&str>,
    ) -> reqwest::RequestBuilder {
        match token {
            Some(token) => builder.bearer_auth(token),
            None => builder,
        }
    }
}

impl Drop for Bridge {
    fn drop(&mut self) {
        let _ = self.child.start_kill();
    }
}

/// A fake Hermes, a temporary bridge home and a bridge on it.
pub struct Setup {
    pub hermes: FakeHermes,
    pub home: tempfile::TempDir,
    pub bridge: Bridge,
}

impl Setup {
    pub async fn new() -> Self {
        Self::with(|config| config).await
    }

    pub async fn with(customize: impl FnOnce(BridgeConfig) -> BridgeConfig) -> Self {
        let hermes = FakeHermes::start().await;
        let home = tempfile::tempdir().unwrap();
        let config = customize(BridgeConfig::new(&hermes, home.path()));
        let bridge = Bridge::start(config).await;
        Setup {
            hermes,
            home,
            bridge,
        }
    }

    pub fn home_path(&self) -> PathBuf {
        self.home.path().to_path_buf()
    }
}

/// The closed error envelope `{"error": {"code": ...}}` of a response.
pub async fn error_code(response: reqwest::Response) -> (u16, String) {
    let status = response.status().as_u16();
    let body: Value = response.json().await.unwrap_or(Value::Null);
    (
        status,
        body["error"]["code"].as_str().unwrap_or("").to_string(),
    )
}

/// Reads an SSE response to its end and returns the raw text.
pub async fn read_all(response: reqwest::Response) -> String {
    String::from_utf8_lossy(&response.bytes().await.unwrap()).into_owned()
}

/// One SSE frame as the fake Hermes sends it.
pub fn frame(event: &str, data: Value) -> String {
    format!(
        "event: {event}\ndata: {}\n\n",
        serde_json::to_string(&data).unwrap()
    )
}

pub fn header(response: &reqwest::Response, name: &str) -> Option<String> {
    response
        .headers()
        .get(name)
        .and_then(|v: &HeaderValue| v.to_str().ok())
        .map(str::to_string)
}
