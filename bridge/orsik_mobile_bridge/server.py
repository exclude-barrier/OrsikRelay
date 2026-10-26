"""Orsik Mobile Bridge — loopback HTTP surface over an UNMODIFIED official Hermes.

The bridge never patches Hermes, never exposes the Hermes API key, and never
forwards an arbitrary path upstream. It mints short-lived device tokens with a
fixed permission allowlist and proxies only the closed PROXIED_ROUTES set.

Public paths mirror the Orsik Mobile Protocol v1 contract (the api_server
namespace the shipped client already speaks), so the client is a drop-in:
  GET  /health                              (proxied, unauthenticated)
  GET  /v1/capabilities                     (bridge-generated, device token)
  GET  /v1/host/metrics                     (bridge-generated, read-only, device token)
  GET  /v1/terminal                         (WebSocket PTY, only with BRIDGE_TERMINAL=1, device token)
  GET  /v1/files/list, /v1/files/content, /v1/projects
                                            (read-only, only with BRIDGE_FILES=1, device token)
  POST /v1/devices/pair                     (bridge-generated, unauthenticated)
  POST /v1/devices/self/revoke              (bridge-generated, device token)
  POST /v1/runs/{run_id}/approval           (validated, device token)
  POST /api/sessions/{id}/chat/stream       (server-side takeover, device token)
  GET  /api/sessions/{id}/runs/{run_id}/stream  (after_seq re-attach, device token)
  GET/POST /api/sessions…, POST/GET /v1/runs…   (proxied, device token)
  PATCH/DELETE /api/sessions/{id}           (archived flag only / no body, device token)
  POST /v1/runs/{id}/resolve-unknown        (admission + generation only, device token)

Pairing codes are issued ONLY by the local operator CLI
(``orsik-mobile-bridge admin pairing-code``); there is no HTTP admin surface.

Security hardening: approval choices are narrowed to once/deny
locally, upstream redirects are never followed (no master-key hop), the query
surface is allowlisted, the device store is persistent and fails closed, and
pairing is rate-limited with true store caps.

The session chat pump decodes with a frame bound coupled to the request body and
redacts the ``user_message`` echo the core puts in the first ``run.started``
frame (the whole image body, so a valid image turn would exceed the 1 MiB relay
bound); every frame must still fit that bound afterwards. This is the only place
a frame is re-serialised instead of relayed byte-for-byte.

TLS is provided out-of-band (e.g. Tailscale Serve to 127.0.0.1); this process
binds loopback plaintext only.
"""

from __future__ import annotations

import argparse
import asyncio
import concurrent.futures
import functools
import ipaddress
import json
import logging
import os
import re
import stat
import sys
import time
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlsplit

from aiohttp import ClientResponse, ClientSession, ClientTimeout, web

from . import core
from . import files as files_mod
from . import hostmetrics
from . import pairing
from . import runhub
from . import store as store_mod
from . import terminal
from .sse import (
    MAX_FRAME_BYTES,
    Frame,
    SSEDecoder,
    SSEFrameTooLarge,
    normalize_approval,
    redact_echo,
    strip_unused,
)

logger = logging.getLogger("orsik_mobile_bridge")
# One line per state-changing call, attributable to a device. Only
# validated, non-secret values (device_id, run/session ids, the choice) are
# logged; never a token, the master key or message content.
audit_logger = logging.getLogger("orsik_mobile_bridge.audit")
_AUDITED_RUN_ACTIONS = ("stop", "steer")


# A value shaped like a device key never reaches the log, even when a client
# puts its own key into a path segment that is audited (session/run id).
_DEVICE_KEY_SHAPE = re.compile(r"hd_[A-Za-z0-9_-]{20,}")


def _audit_value(value: object) -> object:
    if isinstance(value, str):
        if _DEVICE_KEY_SHAPE.search(value):
            return "[redacted]"
        # One event per line: a file name may carry newlines or escape codes.
        if not value.isprintable() or " " in value or "=" in value:
            return value.encode("unicode_escape").decode("ascii").replace(" ", "\\x20").replace("=", "\\x3d")
    return value


def _audit_event(action: str, device_id: str, **fields: object) -> None:
    detail = "".join(f" {key}={_audit_value(value)}" for key, value in fields.items())
    audit_logger.info("audit action=%s device=%s%s", action, device_id, detail)


def _audit(action: str, record: core.DeviceRecord, **fields: object) -> None:
    _audit_event(action, record.device_id, **fields)


class _AccessLogger(web.AbstractAccessLogger):
    """Method, path and status only: never the query string (a client may put
    a token there although every route refuses it) or headers."""

    def log(self, request: web.BaseRequest, response: web.StreamResponse, time: float) -> None:
        self.logger.info(
            "%s %s %s %.3fs", request.method, _audit_value(request.path), response.status, time)

APPROVAL_PATH = "/v1/runs/{run_id}/approval"
HOST_METRICS_PATH = "/v1/host/metrics"
# Match the official api_server request bound (10_000_000) so the bridge does
# not silently reject the client's supported image bodies (<= 8 MiB encoded).
_MAX_BODY_BYTES = 10_000_000
# The session chat pump decodes with a frame bound coupled to the request body:
# the core echoes at most the request body (image included) plus the event
# envelope in the first run.started frame, so a valid image turn legitimately
# decodes to more than the 1 MiB relay bound. The relay bound (MAX_FRAME_BYTES)
# is re-applied to every frame after the echo is redacted.
_MAX_ECHO_FRAME_BYTES = _MAX_BODY_BYTES + 64 * 1024
_PAIR_PER_MINUTE = 60
_RUN_STREAM_KEEPALIVE = 15.0
# How often an open run stream re-checks that its device token is still valid.
_REVOKE_CHECK_INTERVAL = 15.0
# Bounds the authoritative upstream status read used by _indeterminate_run.
_INDETERMINATE_TIMEOUT = ClientTimeout(total=10, sock_connect=5)
# Bound for a non-SSE upstream error body; a larger body fails closed instead of
# being buffered without limit.
_MAX_UPSTREAM_ERROR_BODY_BYTES = 64 * 1024


class BridgeState:
    def __init__(
        self,
        upstream: str,
        master_key: str,
        store: store_mod.BridgeStore | None = None,
        host_services: tuple[str, ...] = hostmetrics.DEFAULT_SERVICES,
        terminal_command: tuple[str, ...] | None = None,
        files_root: str | None = None,
        trust_tailscale_identity: bool = False,
    ) -> None:
        self.upstream = upstream.rstrip("/")
        self.master_key = master_key
        # Persistent by default ("restart keeps paired devices"); tests may inject
        # an in-memory store directly.
        self.store = store if store is not None else store_mod.BridgeStore(":memory:")
        # Typed as Any so tests may inject the in-memory core.TokenStore/CodeStore
        # (both expose the same mint/lookup/revoke / issue/redeem interface).
        self.codes: Any = self.store.codes
        self.tokens: Any = self.store.tokens
        # The store is synchronous (sqlite3 + a lock + a 5 s busy timeout). A
        # single worker serialises every store call onto its own thread so a
        # blocked call can never stall the aiohttp event loop.
        self.db_executor = concurrent.futures.ThreadPoolExecutor(
            max_workers=1, thread_name_prefix="bridge-db")
        self.runhub = runhub.RunHub()
        self.background: set[asyncio.Task] = set()
        self.pair_limiter = core.RateLimiter(_PAIR_PER_MINUTE, 60.0)
        self.trust_tailscale_identity = trust_tailscale_identity
        # Operator-fixed unit names for the Home card (BRIDGE_HOST_SERVICES).
        self.host_services = host_services
        # One snapshot at a time: a burst of requests never runs the
        # subprocesses in parallel.
        self.host_metrics_lock = asyncio.Lock()
        self.host_metrics_cache: tuple[float, dict] | None = None
        # None: the terminal is off (BRIDGE_TERMINAL); otherwise the fixed
        # argv of the tmux attach.
        self.terminal_command = terminal_command
        self.active_terminals = 0
        # None: files/projects are off (BRIDGE_FILES); otherwise the real
        # path of BRIDGE_FILES_ROOT.
        self.files_root = files_root
        self.projects_lock = asyncio.Lock()
        self.projects_cache: tuple[float, dict] | None = None


async def _db(state: BridgeState, fn, *args, **kwargs):
    """Run a synchronous store call on the dedicated DB thread, off the loop."""
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        state.db_executor, functools.partial(fn, *args, **kwargs))


def _safe_upstream_label(upstream: str) -> str:
    parts = urlsplit(upstream)
    return f"{parts.scheme}://{parts.hostname}:{parts.port or ''}".rstrip(":")


_SECRET_FILE_ERROR = (
    "Bridge refuses secret file {path}: it must be a regular file owned by the"
    " current user with permissions 0600 (run: chmod 600 {path})"
)


def _check_secret_file(path: str) -> None:
    """Fail closed before reading a file that can carry the master key."""
    try:
        info = os.stat(path)
    except OSError:
        raise SystemExit(_SECRET_FILE_ERROR.format(path=path)) from None
    if (
        not stat.S_ISREG(info.st_mode)
        or info.st_uid != os.geteuid()
        or (info.st_mode & 0o077) != 0
    ):
        raise SystemExit(_SECRET_FILE_ERROR.format(path=path))


def _env_value(raw: str) -> str:
    """dotenv-style value: matching outer quotes are removed (their content is
    taken verbatim); an unquoted value ends at a " #" comment."""
    val = raw.strip()
    if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
        return val[1:-1]
    comment = val.find(" #")
    return val[:comment].rstrip() if comment >= 0 else val


def _load_env_file(path: str) -> dict[str, str]:
    _check_secret_file(path)
    try:
        text = Path(path).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        # Fail closed: a file that passed the checks but cannot be read must
        # never fall back to other key sources.
        raise SystemExit(f"Could not read env file: {path}") from None
    return _parse_env_text(text)


def _parse_env_text(text: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        values[key.strip()] = _env_value(val)
    return values


def _state_db_path(env: dict[str, str]) -> str:
    if env.get("BRIDGE_STATE_DB"):
        return env["BRIDGE_STATE_DB"]
    home = env.get("BRIDGE_HOME") or os.path.join(
        os.path.expanduser("~"), ".local", "orsik-mobile-bridge")
    return os.path.join(home, "state.db")


_UPSTREAM_ERROR = "BRIDGE_UPSTREAM must be loopback HTTP, e.g. http://127.0.0.1:8642"
_BIND_ERROR = "BRIDGE_BIND must be a literal loopback IP address (127.0.0.1 or ::1)"


def _validate_port(raw: str) -> int:
    try:
        port = int(raw)
    except ValueError:
        port = 0
    if not 1 <= port <= 65535:
        raise SystemExit("BRIDGE_PORT must be a number between 1 and 65535")
    return port


def _validate_bind(bind: str) -> str:
    """Only literal loopback IPs; a name like ``localhost`` is refused."""
    try:
        host = ipaddress.ip_address(bind)
    except ValueError:
        raise SystemExit(_BIND_ERROR) from None
    if not host.is_loopback:
        raise SystemExit(_BIND_ERROR)
    return bind


def _validate_upstream(upstream: str) -> str:
    """Reject anything that could send the master key off the loopback host.

    Only a plaintext-HTTP loopback IP literal with an explicit port is allowed,
    so a typo cannot silently forward the Hermes key to a DNS name or remote
    host. The value is never echoed back (it may carry userinfo secrets).
    """
    try:
        parts = urlsplit(upstream)
        host = ipaddress.ip_address(parts.hostname or "")
        port = parts.port
    except ValueError:
        raise SystemExit(_UPSTREAM_ERROR) from None
    if (
        parts.scheme != "http"
        or parts.username is not None
        or parts.password is not None
        or parts.path
        or parts.query
        or parts.fragment
        or not host.is_loopback
        or port is None
    ):
        raise SystemExit(_UPSTREAM_ERROR)
    return upstream


def build_state_from_env(env: dict[str, str]) -> BridgeState:
    upstream = _validate_upstream(
        env.get("BRIDGE_UPSTREAM", "http://127.0.0.1:8642"))
    master_key = ""
    hermes_env = env.get("BRIDGE_HERMES_ENV", "")
    if hermes_env:
        master_key = _load_env_file(hermes_env).get("API_SERVER_KEY", "")
    master_key = master_key or env.get("HERMES_API_KEY", "")
    if not master_key:
        raise SystemExit("Bridge needs the Hermes API key (BRIDGE_HERMES_ENV or HERMES_API_KEY)")
    host_services = hostmetrics.parse_services(env.get("BRIDGE_HOST_SERVICES"))
    files_root = _files_root_from_env(env)
    store = store_mod.BridgeStore(_state_db_path(env))
    return BridgeState(
        upstream=upstream, master_key=master_key, store=store,
        host_services=host_services, files_root=files_root,
        trust_tailscale_identity=env.get("BRIDGE_TRUST_TAILSCALE_IDENTITY") == "1")


def _files_root_from_env(env: dict[str, str]) -> str | None:
    if not files_mod.parse_enabled(env.get("BRIDGE_FILES")):
        return None
    home = os.path.expanduser("~")
    state_db = _state_db_path(env)
    bridge_home = env.get("BRIDGE_HOME") or os.path.join(home, ".local", "orsik-mobile-bridge")
    hermes_home = env.get("HERMES_HOME") or os.path.join(home, ".hermes")
    hermes_env = env.get("BRIDGE_HERMES_ENV", "")
    # Never the home folder (it holds the Hermes home and the bridge), never
    # the bridge's own files or the Hermes env file (also behind a symlink),
    # and never a folder inside the bridge or the Hermes home.
    homes = [bridge_home, os.path.dirname(os.path.abspath(state_db)), hermes_home]
    protected = [home, *homes, state_db, env.get("BRIDGE_ENV_FILE", "")]
    if hermes_env:
        protected += [hermes_env, os.path.dirname(os.path.abspath(hermes_env)),
                      os.path.dirname(os.path.realpath(hermes_env))]
    return files_mod.resolve_root(env.get("BRIDGE_FILES_ROOT"), protected, sealed=homes)


def _terminal_command_from_env(env: dict[str, str]) -> tuple[str, ...] | None:
    """Only the serving bridge resolves tmux; the admin commands never need it."""
    return terminal.default_command() if terminal.parse_enabled(env.get("BRIDGE_TERMINAL")) else None


def _device_token(request: web.Request) -> str | None:
    header = request.headers.get("Authorization", "")
    if not header.lower().startswith("bearer "):
        return None
    return header[7:].strip() or None


def _closed(status: int, code: str) -> web.Response:
    # Closed error shape: no body echo, no URL, no key, no internal detail.
    return web.json_response({"error": {"code": code}}, status=status)


_REDACTED = b"[redacted]"
# Bound of a buffered (non-SSE) upstream body. Twice the app's own bound for
# the largest JSON answer (message history, 8 MiB).
_MAX_BUFFERED_BODY_BYTES = 16 * 1024 * 1024
# Raw history pages before their inline images are dropped.
_MAX_HISTORY_BODY_BYTES = 64 * 1024 * 1024


def _redact_master_key(state: BridgeState, payload: bytes) -> bytes:
    """Defense in depth for buffered upstream bodies.

    A reflected ``Authorization`` header must never reach the device. SSE
    bodies are relayed chunk by chunk and deliberately left untouched.
    """
    key = state.master_key.encode("utf-8")
    if not key:
        return payload
    return payload.replace(key, _REDACTED)


def _buffered_body(
    state: BridgeState, payload: bytes, transform: Callable[[bytes], bytes] | None
) -> bytes:
    """Transform then redact one buffered body; runs in the default executor."""
    if transform is not None:
        payload = transform(payload)
    return _redact_master_key(state, payload)


STATE_KEY = web.AppKey("state", BridgeState)


@web.middleware
async def _store_fail_closed(request: web.Request, handler):
    """Any store failure is answered closed (503); the bridge never fails open."""
    try:
        return await handler(request)
    except store_mod.StoreUnavailable:
        logger.error("Device store unavailable; refusing request closed")
        return _closed(503, "store_unavailable")


def create_app(state: BridgeState) -> web.Application:
    app = web.Application(client_max_size=_MAX_BODY_BYTES, middlewares=[_store_fail_closed])
    app[STATE_KEY] = state
    app.router.add_get("/health", _handle_health)
    app.router.add_get("/v1/capabilities", _handle_capabilities)
    app.router.add_get(HOST_METRICS_PATH, _handle_host_metrics)
    # GET only: aiohttp would add HEAD to a GET route, and its WebSocket
    # handshake no longer refuses another method.
    app.router.add_get(core.TERMINAL_PATH, _handle_terminal, allow_head=False)
    app.router.add_get(core.FILES_LIST_PATH, _handle_files_list, allow_head=False)
    app.router.add_get(core.FILES_CONTENT_PATH, _handle_files_content, allow_head=False)
    app.router.add_get(core.PROJECTS_PATH, _handle_projects, allow_head=False)
    app.router.add_post("/v1/devices/pair", _handle_pair)
    app.router.add_post("/v1/devices/self/revoke", _handle_self_revoke)
    # No HTTP admin surface: pairing codes are issued only via the local operator
    # CLI (`orsik-mobile-bridge admin pairing-code`), which needs filesystem
    # access to the store. Admin and device authority are strictly separated.
    # Approval gets a dedicated, strictly validated handler (before the catch-all).
    app.router.add_post(APPROVAL_PATH, _handle_approval)
    # Session chat stream is taken over server-side so a run survives downstream
    # disconnects and can be re-attached on the same run_id (after_seq replay).
    app.router.add_post(
        "/api/sessions/{session_id}/chat/stream", _handle_chat_stream)
    app.router.add_get(
        "/api/sessions/{session_id}/runs/{run_id}/stream", _handle_run_reattach)
    # Closed proxy surface: one catch-all that consults the allowlist.
    app.router.add_route("*", "/{tail:.*}", _handle_proxy)

    async def _close_db_executor(_: web.Application) -> None:
        # Drain the DB thread off the loop so shutdown cannot stall it.
        await asyncio.to_thread(state.db_executor.shutdown, wait=True)

    app.on_cleanup.append(_close_db_executor)
    return app


async def _handle_health(request: web.Request) -> web.StreamResponse:
    # Unauthenticated liveness; proxied so the client sees the real core health.
    # A request body is never read or forwarded: /health needs no body and the
    # endpoint has no device token to bound the work.
    if (
        request.headers.get("Content-Length") not in (None, "0")
        or "Transfer-Encoding" in request.headers
    ):
        return _closed(400, "body_not_allowed")
    return await _proxy(request, request.app[STATE_KEY], "/health")


async def _handle_capabilities(request: web.Request) -> web.Response:
    state: BridgeState = request.app[STATE_KEY]
    record = await _db(state, state.tokens.lookup, _device_token(request))
    if record is None:
        return _closed(401, "invalid_credentials")
    return web.json_response(core.build_capabilities(
        terminal=state.terminal_command is not None and record.terminal_allowed,
        files=state.files_root is not None))


async def _handle_host_metrics(request: web.Request) -> web.Response:
    """Read-only CPU/RAM/GPU/service snapshot of the bridge host (bridge-generated)."""
    state: BridgeState = request.app[STATE_KEY]
    if await _db(state, state.tokens.lookup, _device_token(request)) is None:
        return _closed(401, "invalid_credentials")
    if request.query_string:
        return _closed(403, "query_not_allowed")
    try:
        has_body = request.can_read_body and bool(await request.read())
    except web.HTTPRequestEntityTooLarge:
        return _closed(413, "body_too_large")
    if has_body:
        return _closed(400, "body_not_allowed")
    async with state.host_metrics_lock:
        # One snapshot serves every request within HOST_METRICS_MAX_AGE, so
        # fast pollers never queue up subprocess work behind the lock.
        now = time.monotonic()
        cached = state.host_metrics_cache
        if cached is None or now - cached[0] > hostmetrics.MAX_AGE_SECONDS:
            loop = asyncio.get_running_loop()
            snapshot = await loop.run_in_executor(None, hostmetrics.collect, state.host_services)
            state.host_metrics_cache = (time.monotonic(), snapshot)
            cached = state.host_metrics_cache
    return web.json_response(cached[1])


async def _files_request(request: web.Request, *, with_path: bool) -> tuple[str | None, web.Response | None]:
    """Shared checks of the read-only file routes; returns (path, error)."""
    state: BridgeState = request.app[STATE_KEY]
    if await _db(state, state.tokens.lookup, _device_token(request)) is None:
        return None, _closed(401, "invalid_credentials")
    if state.files_root is None:
        return None, _closed(403, "endpoint_out_of_scope")
    query = request.query
    allowed = {"path"} if with_path else set()
    if set(query) - allowed or any(len(query.getall(key)) > 1 for key in query):
        return None, _closed(403, "query_not_allowed")
    try:
        has_body = request.can_read_body and bool(await request.read())
    except web.HTTPRequestEntityTooLarge:
        return None, _closed(413, "body_too_large")
    if has_body:
        return None, _closed(400, "body_not_allowed")
    return query.get("path", ""), None


def _files_error(error: Exception) -> web.Response:
    if isinstance(error, files_mod.PathRefused):
        return _closed(403, "path_not_allowed")
    if isinstance(error, files_mod.TooLarge):
        return _closed(413, "file_too_large")
    return _closed(404, "not_found")


async def _handle_files_list(request: web.Request) -> web.Response:
    path, error = await _files_request(request, with_path=True)
    if error is not None:
        return error
    state: BridgeState = request.app[STATE_KEY]
    try:
        listing = await asyncio.to_thread(files_mod.list_dir, state.files_root, path)
    except (files_mod.PathRefused, files_mod.NotFound) as refused:
        return _files_error(refused)
    return web.json_response(listing, headers={"Cache-Control": "no-store"})


async def _handle_files_content(request: web.Request) -> web.Response:
    path, error = await _files_request(request, with_path=True)
    if error is not None:
        return error
    state: BridgeState = request.app[STATE_KEY]
    try:
        data, name = await asyncio.to_thread(files_mod.read_file, state.files_root, path)
    except (files_mod.PathRefused, files_mod.NotFound, files_mod.TooLarge) as refused:
        return _files_error(refused)
    record = await _db(state, state.tokens.lookup, _device_token(request))
    if record is not None:
        _audit("file_read", record, path=path, bytes=len(data))
    return web.Response(
        body=data,
        headers={
            "Content-Type": files_mod.content_type(name, data),
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
        },
    )


async def _handle_projects(request: web.Request) -> web.Response:
    _, error = await _files_request(request, with_path=False)
    if error is not None:
        return error
    state: BridgeState = request.app[STATE_KEY]
    async with state.projects_lock:
        # git runs per repository; a burst of requests shares one snapshot.
        now = time.monotonic()
        cached = state.projects_cache
        if cached is None or now - cached[0] > _PROJECTS_MAX_AGE:
            snapshot = await asyncio.to_thread(files_mod.list_projects, state.files_root)
            state.projects_cache = cached = (time.monotonic(), snapshot)
    return web.json_response(cached[1], headers={"Cache-Control": "no-store"})


_PROJECTS_MAX_AGE = 10.0


# WebSocket close codes: 1000 the shell ended, 1008 a malformed control
# message, 4401 the device was revoked, 4403 its terminal grant was removed.
_TERMINAL_CLOSE_CODES = {"exited": 1000, "protocol": 1008, "revoked": 4401,
                         "terminal_revoked": 4403}


async def _handle_terminal(request: web.Request) -> web.StreamResponse:
    """A PTY on the tmux session, over a WebSocket (binary: bytes both ways;
    text: a resize). Off unless BRIDGE_TERMINAL=1; never retried by design."""
    state: BridgeState = request.app[STATE_KEY]
    token = _device_token(request)
    record = await _db(state, state.tokens.lookup, token)
    if record is None:
        return _closed(401, "invalid_credentials")
    if state.terminal_command is None:
        return _closed(403, "endpoint_out_of_scope")
    if not record.terminal_allowed:
        _audit("terminal", record, status=403)
        return _closed(403, "terminal_not_allowed")
    if request.query_string:
        return _closed(403, "query_not_allowed")
    # A browser always sends Origin on a WebSocket; the app never does. This
    # closes cross-site WebSocket hijacking even if a token leaked into one.
    if "Origin" in request.headers:
        _audit("terminal", record, status=403)
        return _closed(403, "origin_not_allowed")
    # aiohttp refuses a message of exactly max_msg_size; MAX_INPUT_BYTES itself
    # is allowed.
    ws = web.WebSocketResponse(
        max_msg_size=terminal.MAX_INPUT_BYTES + 1, heartbeat=30.0, autoping=True)
    if not ws.can_prepare(request).ok:
        return _closed(400, "websocket_required")
    if state.active_terminals >= terminal.MAX_TERMINALS:
        _audit("terminal", record, status=429)
        return _closed(429, "terminal_limit")
    state.active_terminals += 1
    try:
        pty = await terminal.Pty.spawn(state.terminal_command)
    except asyncio.CancelledError:
        # The client left during the handshake (aiohttp cancels the handler).
        state.active_terminals -= 1
        raise
    except Exception:
        # Also a missing passwd entry (KeyError) or a failing NSS lookup.
        state.active_terminals -= 1
        logger.error("Terminal could not be started")
        _audit("terminal", record, status=503)
        return _closed(503, "terminal_unavailable")
    # aiohttp cancels the handler when the connection drops; the client is
    # then simply gone.
    reason = "client"
    try:
        # Spawning yields: an operator may have revoked the token/grant while
        # the PTY was starting. Recheck before exposing it to the client.
        current = await _db(state, state.tokens.lookup, token)
        if current is None:
            reason = "revoked"
            return _closed(401, "invalid_credentials")
        if not current.terminal_allowed:
            reason = "terminal_revoked"
            return _closed(403, "terminal_not_allowed")
        await ws.prepare(request)
        _audit("terminal_open", record)
        reason = await _pump_terminal(state, token, ws, pty)
    finally:
        # Shielded: a second cancellation must not leak the PTY or leave the
        # tmux client running. The slot is free once the shell is gone, not
        # only after the close handshake (a silent peer delays that).
        try:
            await asyncio.shield(pty.close())
        finally:
            state.active_terminals -= 1
            _audit("terminal_close", record, reason=reason)
    if not ws.closed:
        await ws.close(code=_TERMINAL_CLOSE_CODES.get(reason, 1011), message=reason.encode())
    return ws


async def _pump_terminal(
    state: BridgeState, token: str | None, ws: web.WebSocketResponse, pty: terminal.Pty
) -> str:
    """Relays until the shell exits, the client leaves, sends garbage or is
    revoked; returns which one (never input or output content)."""

    async def output() -> str:
        while True:
            data = await pty.read()
            if not data:
                return "exited"
            await ws.send_bytes(data)

    async def input_() -> str:
        async for msg in ws:
            if msg.type == web.WSMsgType.BINARY:
                await pty.write(msg.data)
            elif msg.type == web.WSMsgType.TEXT:
                try:
                    size = terminal.parse_resize(json.loads(msg.data))
                except (ValueError, RecursionError):
                    size = None
                if size is None:
                    return "protocol"
                pty.resize(*size)
            else:
                return "error"
        return "client"

    async def revocation() -> str:
        while True:
            await asyncio.sleep(_REVOKE_CHECK_INTERVAL)
            record = await _db(state, state.tokens.lookup, token)
            if record is None:
                return "revoked"
            if not record.terminal_allowed:
                return "terminal_revoked"

    tasks = [asyncio.create_task(job()) for job in (output, input_, revocation)]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    first = next(task for task in tasks if task in done)
    if first.cancelled() or first.exception() is not None:
        return "error"
    return first.result()


def _pairing_client_key(request: web.Request, state: BridgeState) -> str:
    """Rate-limit identity only; a valid one-time code is still mandatory.

    Never trust arbitrary forwarding headers. Serve identity is opt-in and
    accepted only from loopback, where the operator must exclude untrusted
    local callers/proxies. Serve strips incoming copies of its identity header.
    Missing/tagged identities conservatively share their transport peer budget.
    """
    peer = request.remote or "unknown"
    try:
        loopback = ipaddress.ip_address(peer).is_loopback
    except ValueError:
        loopback = False
    if state.trust_tailscale_identity and loopback:
        logins = request.headers.getall("Tailscale-User-Login", [])
        if len(logins) == 1 and 0 < len(logins[0]) <= 1024 and logins[0].strip():
            return "serve:" + core.sha256_hex(logins[0])
    # Retain no raw IP/login in the bounded in-memory table or audit log.
    return "peer:" + core.sha256_hex(peer)


async def _handle_pair(request: web.Request) -> web.Response:
    state: BridgeState = request.app[STATE_KEY]
    if not state.pair_limiter.allow(key=_pairing_client_key(request, state)):
        _audit_event("pair", "-", status=429)
        return _closed(429, "rate_limited")
    try:
        # Contract: Content-Type application/json and exactly {"code": ...}.
        if request.content_type != "application/json":
            raise ValueError("not JSON")
        body = await request.json()
    except web.HTTPRequestEntityTooLarge:
        _audit_event("pair", "-", status=413)
        return _closed(413, "body_too_large")
    except Exception:
        _audit_event("pair", "-", status=400)
        return _closed(400, "invalid_json")
    if isinstance(body, dict) and set(body) != {"code"}:
        _audit_event("pair", "-", status=400)
        return _closed(400, "invalid_fields")
    if not isinstance(body, dict) or not await _db(state, state.codes.redeem, body.get("code")):
        _audit_event("pair", "-", status=400)
        return _closed(400, "pairing_code_invalid")
    minted = await _db(state, state.tokens.mint, label="paired-device", profile="default")
    if minted is None:
        _audit_event("pair", "-", status=503)
        return _closed(503, "device_capacity_reached")
    token, record = minted
    _audit("pair", record, status=201)
    return web.json_response(core.build_credential(record, token, time.time()), status=201)


async def _handle_self_revoke(request: web.Request) -> web.Response:
    state: BridgeState = request.app[STATE_KEY]
    # Contract: exactly 0 bytes. The token is checked first, so an anonymous
    # caller learns nothing from the body rule.
    try:
        has_body = request.can_read_body and bool(await request.read())
    except web.HTTPRequestEntityTooLarge:
        record = await _db(state, state.tokens.lookup, _device_token(request))
        if record is None:
            return _closed(401, "invalid_credentials")
        _audit("revoke", record, status=413)
        return _closed(413, "body_too_large")
    if has_body:
        if await _db(state, state.tokens.lookup, _device_token(request)) is None:
            return _closed(401, "invalid_credentials")
        return _closed(400, "body_not_allowed")
    record = await _db(state, state.tokens.revoke, _device_token(request))
    if record is None:
        _audit_event("revoke", "-", status=401)
        return _closed(401, "invalid_credentials")
    _audit("revoke", record, status=200)
    # true for the fresh revocation, false when it was already revoked.
    return web.json_response({"revoked": not record.already_revoked, "device_id": record.device_id})


async def _handle_approval(request: web.Request) -> web.StreamResponse:
    """Strictly validate a Mobile-v1 approval and only then forward it.

    Orsik Mobile-v1 permits the choices once/deny only. Any other choice, a
    malformed body, wrong field types or extra fields are rejected closed and
    NEVER reach Hermes.
    """
    state: BridgeState = request.app[STATE_KEY]
    record = await _db(state, state.tokens.lookup, _device_token(request))
    if record is None:
        return _closed(401, "invalid_credentials")
    run_id = request.match_info.get("run_id", "")
    if not core.is_safe_run_id(run_id):
        return _closed(400, "invalid_run_id")
    try:
        body = await request.json()
    except web.HTTPRequestEntityTooLarge:
        return _closed(413, "body_too_large")
    except Exception:
        _audit("approval", record, run=run_id, choice="rejected", status=400)
        return _closed(400, "invalid_json")
    ok, code = core.validate_approval_body(body)
    if not ok:
        # The raw (rejected) choice is client input and is never logged.
        _audit("approval", record, run=run_id, choice="rejected", status=400)
        return _closed(400, code)
    # Forward a canonical body (only the two contract fields) upstream. A core
    # with the session authority (Hermes 0.22+) also requires the run's current
    # execution_generation; it is read from the run status, never from the device.
    canonical = {"choice": body["choice"], "request_id": body["request_id"]}
    generation = await _run_execution_generation(state, run_id)
    if generation is not None:
        canonical["execution_generation"] = generation
    payload = json.dumps(canonical).encode("utf-8")
    choice = body["choice"]
    return await _proxy(
        request, state, f"/v1/runs/{run_id}/approval", body_override=payload,
        on_status=lambda status: _audit("approval", record, run=run_id, choice=choice, status=status),
    )


async def _run_execution_generation(state: BridgeState, run_id: str) -> int | None:
    """The run's ``execution_generation`` from ``GET /v1/runs/{run_id}``, or None.

    One idempotent read without retry. Anything but a 200 JSON object with an
    integer field (an older core, an unknown run, a timeout) yields None, and
    the approval is then forwarded in its plain v1 form.
    """
    timeout = ClientTimeout(total=15, sock_connect=10)
    headers = {"Authorization": "Bearer " + state.master_key, "Accept": "application/json"}
    try:
        async with ClientSession(timeout=timeout) as session:
            async with session.get(
                f"{state.upstream}/v1/runs/{run_id}", headers=headers, allow_redirects=False
            ) as upstream:
                if upstream.status != 200:
                    return None
                raw = await upstream.content.read(64 * 1024 + 1)
    except Exception:
        return None
    if len(raw) > 64 * 1024:
        return None
    try:
        run = json.loads(raw)
    except (ValueError, RecursionError):
        return None
    generation = run.get("execution_generation") if isinstance(run, dict) else None
    # bool is an int subclass; a JSON true is not a generation.
    if type(generation) is not int:
        return None
    return generation


def _upstream_headers(request: web.Request) -> dict[str, str]:
    headers = {"Accept": request.headers.get("Accept", "text/event-stream")}
    upstream_ct = request.headers.get("Content-Type")
    if upstream_ct:
        headers["Content-Type"] = upstream_ct
    return headers


async def _handle_chat_stream(request: web.Request) -> web.StreamResponse:
    """Server-side takeover of the session chat stream.

    The bridge owns the upstream SSE connection independently of the calling
    client, so a downstream disconnect does not interrupt the Hermes run; the
    run's frames are buffered and can be replayed on re-attach.
    """
    state: BridgeState = request.app[STATE_KEY]
    record = await _db(state, state.tokens.lookup, _device_token(request))
    if record is None:
        return _closed(401, "invalid_credentials")
    session_id = request.match_info.get("session_id", "")
    if not core._safe_segment(session_id):
        return _closed(400, "invalid_session_id")
    if request.query:
        return _closed(403, "query_not_allowed")
    _audit("chat", record, session=session_id)
    try:
        body = await request.read() if request.can_read_body else None
    except web.HTTPRequestEntityTooLarge:
        _audit("chat", record, session=session_id, status=413)
        return _closed(413, "body_too_large")
    upstream_path = f"/api/sessions/{session_id}/chat/stream"
    try:
        buf = state.runhub.create_pending(session_id)
    except runhub.RunHubBusy:
        # Admission control: refuse before any upstream POST is made so a burst
        # of runs cannot exhaust bridge or Hermes resources.
        resp = _closed(503, "bridge_busy")
        resp.headers["Retry-After"] = "5"
        return resp
    task = asyncio.create_task(
        _pump_chat_stream(state, buf, upstream_path, body, _upstream_headers(request)))
    state.background.add(task)
    task.add_done_callback(state.background.discard)
    # The originating client attaches once the first frame (or a failure) is
    # observed, i.e. after the pump's first chunk. Should that chunk alone
    # overflow the backlog bounds, attach(-1) fails closed with 409 resume_gap
    # rather than relaying a stream with a hole.
    return await _stream_buffer_to_client(request, buf, after_seq=-1)


def _relay_frame(state: BridgeState, buf: runhub.RunBuffer, frame: Frame) -> bool:
    """Redact the run.started echo, narrow approval.request to Mobile-v1, drop
    the fields a phone never reads, enforce the relay bound, then buffer the
    frame.

    Returns False when a frame is still above :data:`MAX_FRAME_BYTES` after
    redaction; the caller then closes the upstream exactly like the decoder's own
    oversize path. Such a frame is never buffered.
    """
    frame = strip_unused(normalize_approval(redact_echo(frame)))
    if len(frame.raw) > MAX_FRAME_BYTES:
        return False
    buf.append(frame)
    if buf.run_id:
        state.runhub.register(buf)
    return True


def _oversized_frame(upstream: ClientResponse, buf: runhub.RunBuffer, limit: int) -> None:
    """Fail closed on a frame above ``limit``: 502 while pending, else abort.

    Mirrors the decoder's :class:`SSEFrameTooLarge` handling: the offending frame
    is never relayed and the upstream is closed, never resynced (that would be a
    hole in the stream).
    """
    upstream.release()
    if buf.state == "pending":
        buf.fail(502, "application/json",
                 b'{"error":{"code":"upstream_frame_too_large"}}')
    else:
        buf.abort_reason = "upstream_frame_too_large"
        logger.warning("upstream SSE frame exceeded %d bytes", limit)


async def _pump_chat_stream(
    state: BridgeState, buf: runhub.RunBuffer, upstream_path: str,
    body: bytes | None, headers: dict[str, str],
) -> None:
    """Read the upstream SSE stream into the run buffer, independent of clients.

    Frames are decoded with a bound coupled to the request body and the
    ``run.started`` echo is redacted before buffering, so an image turn is not
    mistaken for an oversized frame.
    """
    timeout = ClientTimeout(total=None, sock_connect=10, sock_read=600)
    session = ClientSession(timeout=timeout)
    try:
        try:
            upstream = await session.request(
                "POST", state.upstream + upstream_path, data=body,
                headers={"Authorization": "Bearer " + state.master_key, **headers},
                allow_redirects=False,
            )
        except Exception:
            buf.fail(502, "application/json", b'{"error":{"code":"upstream_unavailable"}}')
            return
        if 300 <= upstream.status < 400:
            upstream.release()
            buf.fail(502, "application/json", b'{"error":{"code":"upstream_redirect"}}')
            return
        content_type = upstream.headers.get("Content-Type", "application/json")
        if upstream.status != 200 or "text/event-stream" not in content_type:
            payload = await _read_bounded_body(upstream, _MAX_UPSTREAM_ERROR_BODY_BYTES)
            upstream.release()
            if payload is None:
                buf.fail(502, "application/json",
                         b'{"error":{"code":"upstream_error_too_large"}}')
                return
            buf.fail(upstream.status, content_type, _redact_master_key(state, payload))
            return
        decoder = SSEDecoder(max_frame_bytes=_MAX_ECHO_FRAME_BYTES)
        async for chunk in upstream.content.iter_any():
            try:
                frames = decoder.feed(chunk)
            except SSEFrameTooLarge as exc:
                # Frames that completed before the violation are still relayed;
                # the oversized frame itself is dropped and the upstream closed,
                # never followed by a resync (that would be a hole).
                for frame in exc.frames:
                    if not _relay_frame(state, buf, frame):
                        _oversized_frame(upstream, buf, MAX_FRAME_BYTES)
                        return
                _oversized_frame(upstream, buf, exc.limit)
                return
            for frame in frames:
                # The body-coupled decoder bound only buys room for the echoed
                # run.started payload: every frame still has to fit the 1 MiB
                # relay bound after the echo is redacted.
                if not _relay_frame(state, buf, frame):
                    _oversized_frame(upstream, buf, MAX_FRAME_BYTES)
                    return
    except Exception:
        logger.debug("session chat stream pump ended with error")
    finally:
        buf.finish()
        await session.close()


async def _read_bounded_body(upstream: ClientResponse, limit: int) -> bytes | None:
    """Read an upstream body up to ``limit`` bytes; None if it would exceed it."""
    data = bytearray()
    async for chunk in upstream.content.iter_chunked(65536):
        data += chunk
        if len(data) > limit:
            return None
    return bytes(data)


async def _handle_run_reattach(request: web.Request) -> web.StreamResponse:
    """GET /api/sessions/{session_id}/runs/{run_id}/stream?after_seq=N.

    Replays retained frames with ``seq > after_seq`` then follows live. A buffer
    gap or a run that cannot be reconstructed fails closed (409/404); the bridge
    never fabricates events and never re-issues the originating chat POST.
    """
    state: BridgeState = request.app[STATE_KEY]
    if await _db(state, state.tokens.lookup, _device_token(request)) is None:
        return _closed(401, "invalid_credentials")
    session_id = request.match_info.get("session_id", "")
    run_id = request.match_info.get("run_id", "")
    if not core._safe_segment(session_id) or not core.is_safe_run_id(run_id):
        return _closed(404, "run_not_found")
    after_seq = _parse_after_seq(request)
    if after_seq is None:
        return _closed(400, "invalid_after_seq")
    buf = state.runhub.lookup(session_id, run_id)
    if buf is None:
        if state.runhub.belongs_to_other_session(session_id, run_id):
            # A run of another session is "not found" for this one (contract
            # section 8.2), never a resume gap of this session.
            return _closed(404, "run_not_found")
        return await _indeterminate_run(state, run_id)
    return await _stream_buffer_to_client(request, buf, after_seq=after_seq)


def _parse_after_seq(request: web.Request) -> int | None:
    raw = request.query.get("after_seq")
    if raw is None:
        return -1
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return None
    return value if value >= -1 else None


async def _stream_buffer_to_client(
    request: web.Request, buf: runhub.RunBuffer, *, after_seq: int
) -> web.StreamResponse:
    await buf.ready.wait()
    if buf.state == "failed":
        return web.Response(
            status=buf.error_status, body=buf.error_body,
            content_type=buf.error_content_type)
    attached = buf.attach(after_seq)
    if attached is None:
        return _closed(409, "resume_gap")
    subscriber, replay = attached
    state: BridgeState = request.app[STATE_KEY]
    token = _device_token(request)
    loop = asyncio.get_running_loop()
    next_auth_check = loop.time() + _REVOKE_CHECK_INTERVAL
    downstream = web.StreamResponse(status=200)
    downstream.content_type = "text/event-stream"
    downstream.headers["Cache-Control"] = "no-cache"
    await downstream.prepare(request)
    try:
        for _seq, raw in replay:
            await downstream.write(raw)
        if buf.terminal and subscriber.empty():
            await _write_abort_comment(downstream, buf)
            return downstream
        while True:
            if loop.time() >= next_auth_check:
                # A revoked device (from the phone or `admin revoke-device` in
                # another process) loses an open stream within one interval,
                # not only on its next request. The run itself keeps running.
                if await _db(state, state.tokens.lookup, token) is None:
                    await downstream.write(b": bridge_revoked\n\n")
                    break
                next_auth_check = loop.time() + _REVOKE_CHECK_INTERVAL
            try:
                item = await asyncio.wait_for(subscriber.get(), timeout=_RUN_STREAM_KEEPALIVE)
            except TimeoutError:
                await downstream.write(b": keepalive\n\n")
                continue
            seq, raw = item
            if seq is None:
                # A slow subscriber was dropped: signal it explicitly rather than
                # ending with a clean EOF, so the client re-attaches with after_seq.
                if raw is runhub.OVERFLOW:
                    await downstream.write(b": bridge_overflow\n\n")
                else:
                    await _write_abort_comment(downstream, buf)
                break
            if isinstance(raw, bytes):
                await downstream.write(raw)
    except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, OSError):
        pass
    finally:
        buf.detach(subscriber)
    return downstream


async def _write_abort_comment(
    downstream: web.StreamResponse, buf: runhub.RunBuffer
) -> None:
    """Emit a trailing ``: bridge_<reason>`` comment for an aborted-but-not-EOF run."""
    if buf.abort_reason:
        await downstream.write(b": bridge_" + buf.abort_reason.encode() + b"\n\n")


async def _indeterminate_run(state: BridgeState, run_id: str) -> web.Response:
    """No buffer: consult the authoritative upstream status, never fabricate.

    Fail-closed mapping:
      * 200                  -> 409 ``resume_gap`` (run known, transport gone: the
                                state is indeterminate and non-terminal)
      * 404                  -> 404 ``run_not_found`` (confirmed absent upstream)
      * 3xx                  -> 502 ``upstream_redirect`` (never followed)
      * 401/403/5xx/other    -> 502 ``run_state_indeterminate`` (never a false 404)
      * timeout/network/etc. -> 502 ``run_state_indeterminate`` (never a false 404)

    Only a *confirmed* upstream 404 is reported as "not found"; every other
    outcome is a distinct, non-terminal error so the client keeps run ownership.
    No run completion is invented and no chat POST is repeated.
    """
    try:
        async with ClientSession(timeout=_INDETERMINATE_TIMEOUT) as session:
            resp = await session.get(
                f"{state.upstream}/v1/runs/{run_id}",
                headers={"Authorization": "Bearer " + state.master_key},
                allow_redirects=False,
            )
            status = resp.status
            resp.release()
    except Exception:
        return _closed(502, "run_state_indeterminate")
    if status == 200:
        return _closed(409, "resume_gap")
    if status == 404:
        return _closed(404, "run_not_found")
    if 300 <= status < 400:
        return _closed(502, "upstream_redirect")
    return _closed(502, "run_state_indeterminate")


async def _handle_proxy(request: web.Request) -> web.StreamResponse:
    state: BridgeState = request.app[STATE_KEY]
    record = await _db(state, state.tokens.lookup, _device_token(request))
    if record is None:
        return _closed(401, "invalid_credentials")
    match = core.match_proxy_route(request.method, request.path)
    if match is None:
        # Out-of-scope path (admin/jobs/cron/memory/skills/toolsets/...): never proxied.
        return _closed(403, "endpoint_out_of_scope")
    # Exact match only: a non-canonical spelling (e.g. a trailing slash) must not
    # reach a stricter dedicated handler's path through this generic catch-all.
    if match.upstream_path != request.path:
        return _closed(403, "endpoint_out_of_scope")
    # Defense in depth: the approval route is only ever served by the dedicated
    # validating handler, never forwarded by the generic proxy.
    if request.method == "POST" and match.upstream_path.endswith("/approval"):
        return _closed(403, "endpoint_out_of_scope")
    if "session_id" in match.params and request.method in ("PATCH", "DELETE"):
        return await _handle_session_management(request, state, record, match)
    if request.method == "POST" and match.upstream_path.endswith("/resolve-unknown"):
        return await _handle_resolve_unknown(request, state, record, match)
    action = match.upstream_path.rsplit("/", 1)[-1]
    on_status = None
    if request.method == "POST" and match.upstream_path == "/api/sessions":
        on_status = lambda status: _audit("session_create", record, status=status)  # noqa: E731
    elif request.method == "POST" and "run_id" in match.params and action in _AUDITED_RUN_ACTIONS:
        run_id = match.params["run_id"]
        on_status = lambda status: _audit(action, record, run=run_id, status=status)  # noqa: E731
    if request.method == "GET" and match.upstream_path.endswith("/messages"):
        # History pages carry the inline images of earlier turns; they are
        # dropped here (see core.strip_history_images), so the raw page may
        # be far larger than what reaches the phone.
        return await _proxy(
            request, state, match.upstream_path,
            transform=core.strip_history_images,
            max_buffered_bytes=_MAX_HISTORY_BODY_BYTES,
        )
    return await _proxy(request, state, match.upstream_path, on_status=on_status)


async def _handle_resolve_unknown(
    request: web.Request, state: BridgeState, record: core.DeviceRecord, match: core.ProxyMatch
) -> web.StreamResponse:
    """Acknowledge one lost run; the validated body is re-serialised.

    A local rejection is audited like an upstream answer, so an attempt to
    smuggle other fields is visible to the operator.
    """
    run_id = match.params["run_id"]

    def rejected(status: int, code: str) -> web.Response:
        _audit("resolve_unknown", record, run=run_id, status=status)
        return _closed(status, code)

    try:
        raw = await request.read() if request.can_read_body else b""
    except web.HTTPRequestEntityTooLarge:
        return rejected(413, "body_too_large")
    try:
        parsed = json.loads(raw)
    except (ValueError, UnicodeDecodeError, RecursionError):
        return rejected(400, "invalid_json")
    ok, code = core.validate_resolve_unknown_body(parsed)
    if not ok:
        return rejected(400, code)
    body = json.dumps({
        "admission_id": parsed["admission_id"],
        "execution_generation": parsed["execution_generation"],
    }).encode()
    on_status = lambda status: _audit("resolve_unknown", record, run=run_id, status=status)  # noqa: E731
    return await _proxy(request, state, match.upstream_path, body_override=body, on_status=on_status)


async def _handle_session_management(
    request: web.Request, state: BridgeState, record: core.DeviceRecord, match: core.ProxyMatch
) -> web.StreamResponse:
    """Archive (PATCH, archived flag only) or delete (DELETE, no body) one session.

    The body is validated locally and re-serialised, so no other session field
    (title, pin, hidden, end_reason) can ever reach Hermes through the bridge.
    """
    session_id = match.params["session_id"]
    action = "session_delete" if request.method == "DELETE" else "session_archive"

    def rejected(status: int, code: str) -> web.Response:
        # Local rejections are audited too (least-privilege attempts stay visible).
        _audit(action, record, session=session_id, status=status)
        return _closed(status, code)

    try:
        raw = await request.read() if request.can_read_body else b""
    except web.HTTPRequestEntityTooLarge:
        return rejected(413, "body_too_large")
    if request.method == "DELETE":
        if raw:
            return rejected(400, "body_not_allowed")
        body = b""
    else:
        try:
            parsed = json.loads(raw)
        except (ValueError, UnicodeDecodeError, RecursionError):
            return rejected(400, "invalid_json")
        ok, code = core.validate_session_patch_body(parsed)
        if not ok:
            return rejected(400, code)
        body = json.dumps({"archived": parsed["archived"]}).encode()
    on_status = lambda status: _audit(action, record, session=session_id, status=status)  # noqa: E731
    return await _proxy(request, state, match.upstream_path, body_override=body, on_status=on_status)


async def _proxy(
    request: web.Request,
    state: BridgeState,
    upstream_path: str,
    body_override: bytes | None = None,
    on_status: Callable[[int], None] | None = None,
    transform: Callable[[bytes], bytes] | None = None,
    max_buffered_bytes: int = 0,
) -> web.StreamResponse:
    """Forward one request upstream and relay the response.

    ``on_status`` (the audit hook) is called with the final status before
    any byte is sent downstream, so the record exists before the client is done.
    """
    def closed(status: int, code: str) -> web.Response:
        if on_status is not None:
            on_status(status)
        return _closed(status, code)

    # Query surface is allowlisted per route; unknown keys never reach upstream.
    allowed = core.allowed_query_keys(request.method, upstream_path)
    for key in request.query.keys():
        if key not in allowed:
            return closed(403, "query_not_allowed")

    upstream_url = state.upstream + upstream_path
    if request.query_string:
        upstream_url += "?" + request.query_string

    if body_override is not None:
        body: bytes | None = body_override
    else:
        try:
            body = await request.read() if request.can_read_body else None
        except web.HTTPRequestEntityTooLarge:
            return closed(413, "body_too_large")

    headers = {"Authorization": "Bearer " + state.master_key}
    upstream_ct = request.headers.get("Content-Type")
    if upstream_ct:
        headers["Content-Type"] = upstream_ct
    if request.headers.get("Accept"):
        headers["Accept"] = request.headers["Accept"]

    timeout = ClientTimeout(total=None, sock_connect=10, sock_read=600)
    async with ClientSession(timeout=timeout) as session:
        try:
            # Never follow redirects: the master key must not hop to another host.
            upstream = await session.request(
                request.method, upstream_url, data=body, headers=headers, allow_redirects=False
            )
        except Exception:
            return closed(502, "upstream_unavailable")  # type: ignore[return-value]

        if 300 <= upstream.status < 400:
            # Fail closed; do not relay a Location that could induce an SSRF hop.
            upstream.release()
            return closed(502, "upstream_redirect")  # type: ignore[return-value]

        content_type = upstream.headers.get("Content-Type", "application/json")
        if "text/event-stream" in content_type:
            # Streaming path: status and headers go out before the first chunk.
            downstream = web.StreamResponse(status=upstream.status)
            downstream.content_type = content_type.split(";")[0].strip()
            downstream.headers["Cache-Control"] = "no-cache"
            if on_status is not None:
                on_status(upstream.status)
            await downstream.prepare(request)
            try:
                async for chunk in upstream.content.iter_chunked(4096):
                    await downstream.write(chunk)
            except Exception:
                pass
            finally:
                upstream.release()
            await downstream.write_eof()
            return downstream

        # Buffered path: read and transform BEFORE the status/headers go out, so
        # an oversize body or a failed transform is a closed error envelope
        # instead of a half-sent 200 whose audit line still says 200.
        try:
            payload = await _read_bounded_body(
                upstream, max_buffered_bytes or _MAX_BUFFERED_BODY_BYTES)
        except Exception:
            upstream.release()
            return closed(502, "upstream_unavailable")  # type: ignore[return-value]
        if payload is None:
            upstream.release()
            return closed(502, "upstream_error_too_large")  # type: ignore[return-value]
        # Transform (json.loads/dumps over up to 64 MiB) and redaction run in the
        # default executor, never on the aiohttp event loop.
        loop = asyncio.get_running_loop()
        effective_transform = transform if upstream.status == 200 else None
        try:
            payload = await loop.run_in_executor(
                None, functools.partial(_buffered_body, state, payload, effective_transform))
        except Exception:
            upstream.release()
            return closed(502, "upstream_unavailable")  # type: ignore[return-value]

        upstream.release()
        downstream = web.StreamResponse(status=upstream.status)
        downstream.content_type = content_type.split(";")[0].strip()
        if on_status is not None:
            on_status(upstream.status)
        try:
            await downstream.prepare(request)
            await downstream.write(payload)
            await downstream.write_eof()
        except Exception:
            pass
        return downstream


def _load_env() -> dict[str, str]:
    env = dict(os.environ)
    env_file = env.get("BRIDGE_ENV_FILE")
    if env_file:
        env.update(_load_env_file(env_file))
    return env


def _admin_main(argv: list[str]) -> int:
    """Local operator commands (no HTTP admin surface).

    The operator already has filesystem access to the store, which is the
    authorization boundary; devices never get an admin credential or route.
    """
    parser = argparse.ArgumentParser(prog="orsik-mobile-bridge admin")
    sub = parser.add_subparsers(dest="cmd", required=True)
    issue = sub.add_parser("pairing-code", help="issue a one-time pairing code")
    issue.add_argument("--base-url", default=None,
                       help="HTTPS base URL for the orsik://pair deep link/QR")
    sub.add_parser("list-devices", help="list paired devices (no secrets)")
    revoke = sub.add_parser("revoke-device", help="revoke a device by device_id")
    revoke.add_argument("device_id")
    grant_terminal = sub.add_parser("grant-terminal", help="allow shell access for one active device")
    grant_terminal.add_argument("device_id")
    revoke_terminal = sub.add_parser("revoke-terminal", help="remove shell access for one active device")
    revoke_terminal.add_argument("device_id")
    backup = sub.add_parser("backup", help="write an atomic snapshot of the store")
    backup.add_argument("dest", help="destination path for the backup file")
    args = parser.parse_args(argv)

    env = _load_env()
    state = build_state_from_env(env)
    try:
        if args.cmd == "pairing-code":
            # The default comes from bridge.env too, not only the process env.
            base_url = args.base_url if args.base_url is not None else env.get("BRIDGE_PUBLIC_BASE_URL", "")
            if base_url:
                try:
                    pairing.pairing_uri(base_url, "A" * core.CODE_LENGTH)
                except ValueError:
                    print("error: BRIDGE_PUBLIC_BASE_URL / --base-url must be https://<host> without user info",
                          file=sys.stderr)
                    return 1
            issued = state.codes.issue()
            if issued is None:
                print("error: pairing-code capacity reached", file=sys.stderr)
                return 1
            code, ttl = issued
            print(f"Pairing code: {pairing.format_code(code)}  (valid {ttl}s, single-use)")
            if base_url:
                uri = pairing.pairing_uri(base_url, code)
                print(f"Deep link:    {uri}")
                qr = pairing.render_ascii_qr(uri)
                print(qr if qr else "(install 'qrencode' for an ASCII QR; deep link above is equivalent)")
            else:
                print("(set BRIDGE_PUBLIC_BASE_URL or --base-url to print the orsik://pair deep link/QR)")
            return 0
        if args.cmd == "list-devices":
            for device in state.store.list_devices():
                flag = "revoked" if device["revoked"] else "active"
                terminal_flag = "allowed" if device["terminal_allowed"] else "denied"
                print(f"{device['device_id']}  {flag:7}  {device['label']}  [{device['profile']}]"
                      f"  terminal={terminal_flag}")
            return 0
        if args.cmd in ("grant-terminal", "revoke-terminal"):
            allowed = args.cmd == "grant-terminal"
            try:
                found = state.store.set_terminal_access(args.device_id, allowed)
            except store_mod.StoreUnavailable:
                print("error: device store unavailable", file=sys.stderr)
                return 1
            if not found:
                print("error: no active device with this device_id", file=sys.stderr)
                return 1
            print(f"terminal {'allowed' if allowed else 'denied'} for {args.device_id}")
            return 0
        if args.cmd == "revoke-device":
            if state.store.revoke_device_by_id(args.device_id):
                print(f"revoked {args.device_id}")
                return 0
            print("error: no such device_id", file=sys.stderr)
            return 1
        if args.cmd == "backup":
            try:
                state.store.backup_to(args.dest)
            except store_mod.StoreUnavailable as exc:
                print(f"error: {exc}", file=sys.stderr)
                return 1
            print(f"backup written: {args.dest}")
            return 0
        return 2
    finally:
        state.store.close()


def main(argv: list[str] | None = None) -> int:
    # Files created by the bridge (store, sidecars, backups) must never be
    # group/world accessible, even before _harden_file runs.
    os.umask(0o077)
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "update":
        from . import update
        return update.main(argv[1:])
    if argv and argv[0] == "setup":
        from . import setup
        return setup.main(argv[1:])
    if argv and argv[0] == "admin":
        logging.basicConfig(level=logging.WARNING, format="%(message)s")
        return _admin_main(argv[1:])
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    env = _load_env()
    state = build_state_from_env(env)
    state.terminal_command = _terminal_command_from_env(env)
    # An empty value means the default, exactly as install.sh --check reads it.
    bind = _validate_bind(env.get("BRIDGE_BIND") or "127.0.0.1")
    port = _validate_port(env.get("BRIDGE_PORT") or "9842")
    logger.info(
        "Orsik Mobile Bridge on http://%s:%d -> %s",
        bind, port, _safe_upstream_label(state.upstream),
    )
    web.run_app(create_app(state), host=bind, port=port, print=None, access_log_class=_AccessLogger)
    return 0


if __name__ == "__main__":
    sys.exit(main())
