"""Orsik Mobile Bridge — loopback HTTP surface over an UNMODIFIED official Hermes.

The bridge never patches Hermes, never exposes the Hermes API key, and never
forwards an arbitrary path upstream. It mints short-lived device tokens with a
fixed permission allowlist and proxies only the closed PROXIED_ROUTES set.

Public paths mirror the Orsik Mobile Protocol v1 contract (the api_server
namespace the shipped client already speaks), so the client is a drop-in:
  GET  /health                              (proxied, unauthenticated)
  GET  /v1/capabilities                     (bridge-generated, device token)
  POST /v1/devices/pair                     (bridge-generated, unauthenticated)
  POST /v1/devices/self/revoke              (bridge-generated, device token)
  POST /v1/runs/{run_id}/approval           (validated, device token)
  POST /api/sessions/{id}/chat/stream       (server-side takeover, device token)
  GET  /api/sessions/{id}/runs/{run_id}/stream  (after_seq re-attach, device token)
  GET/POST /api/sessions…, POST/GET /v1/runs…   (proxied, device token)

Pairing codes are issued ONLY by the local operator CLI
(``orsik-mobile-bridge admin pairing-code``); there is no HTTP admin surface.

Security hardening (P12.1 + P13): approval choices are narrowed to once/deny
locally, upstream redirects are never followed (no master-key hop), the query
surface is allowlisted, the device store is persistent and fails closed, and
pairing is rate-limited with true store caps.

TLS is provided out-of-band (e.g. Tailscale Serve to 127.0.0.1); this process
binds loopback plaintext only.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from aiohttp import ClientResponse, ClientSession, ClientTimeout, web

from . import core
from . import pairing
from . import runhub
from . import store as store_mod
from .sse import SSEDecoder, SSEFrameTooLarge

logger = logging.getLogger("orsik_mobile_bridge")

APPROVAL_PATH = "/v1/runs/{run_id}/approval"
# Match the official api_server request bound (10_000_000) so the bridge does
# not silently reject the client's supported image bodies (<= 8 MiB encoded).
_MAX_BODY_BYTES = 10_000_000
_PAIR_PER_MINUTE = 60
_RUN_STREAM_KEEPALIVE = 15.0
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
        self.runhub = runhub.RunHub()
        self.background: set[asyncio.Task] = set()
        self.pair_limiter = core.RateLimiter(_PAIR_PER_MINUTE, 60.0)


def _safe_upstream_label(upstream: str) -> str:
    parts = urlsplit(upstream)
    return f"{parts.scheme}://{parts.hostname}:{parts.port or ''}".rstrip(":")


def _load_env_file(path: str) -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            values[key.strip()] = val.strip()
    except OSError:
        logger.error("Could not read env file: %s", path)
    return values


def _state_db_path(env: dict[str, str]) -> str:
    if env.get("BRIDGE_STATE_DB"):
        return env["BRIDGE_STATE_DB"]
    home = env.get("BRIDGE_HOME") or os.path.join(
        os.path.expanduser("~"), ".local", "orsik-mobile-bridge")
    return os.path.join(home, "state.db")


def build_state_from_env(env: dict[str, str]) -> BridgeState:
    upstream = env.get("BRIDGE_UPSTREAM", "http://127.0.0.1:8899")
    master_key = ""
    hermes_env = env.get("BRIDGE_HERMES_ENV", "")
    if hermes_env:
        master_key = _load_env_file(hermes_env).get("API_SERVER_KEY", "")
    master_key = master_key or env.get("HERMES_API_KEY", "")
    if not master_key:
        raise SystemExit("Bridge needs the Hermes API key (BRIDGE_HERMES_ENV or HERMES_API_KEY)")
    store = store_mod.BridgeStore(_state_db_path(env))
    return BridgeState(upstream=upstream, master_key=master_key, store=store)


def _device_token(request: web.Request) -> str | None:
    header = request.headers.get("Authorization", "")
    if not header.lower().startswith("bearer "):
        return None
    return header[7:].strip() or None


def _closed(status: int, code: str) -> web.Response:
    # Closed error shape: no body echo, no URL, no key, no internal detail.
    return web.json_response({"error": {"code": code}}, status=status)


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
    return app


async def _handle_health(request: web.Request) -> web.StreamResponse:
    # Unauthenticated liveness; proxied so the client sees the real core health.
    return await _proxy(request, request.app[STATE_KEY], "/health")


async def _handle_capabilities(request: web.Request) -> web.Response:
    state: BridgeState = request.app[STATE_KEY]
    if state.tokens.lookup(_device_token(request)) is None:
        return _closed(401, "invalid_credentials")
    return web.json_response(core.build_capabilities())


async def _handle_pair(request: web.Request) -> web.Response:
    state: BridgeState = request.app[STATE_KEY]
    if not state.pair_limiter.allow():
        return _closed(429, "rate_limited")
    try:
        body = await request.json()
    except web.HTTPRequestEntityTooLarge:
        return _closed(413, "body_too_large")
    except Exception:
        return _closed(400, "invalid_json")
    if not isinstance(body, dict) or not state.codes.redeem(body.get("code")):
        return _closed(400, "pairing_code_invalid")
    minted = state.tokens.mint(label="paired-device", profile="default")
    if minted is None:
        return _closed(503, "device_capacity_reached")
    token, record = minted
    return web.json_response(core.build_credential(record, token, time.time()), status=201)


async def _handle_self_revoke(request: web.Request) -> web.Response:
    state: BridgeState = request.app[STATE_KEY]
    record = state.tokens.revoke(_device_token(request))
    if record is None:
        return _closed(401, "invalid_credentials")
    return web.json_response({"revoked": True, "device_id": record.device_id})


async def _handle_approval(request: web.Request) -> web.StreamResponse:
    """Strictly validate a Mobile-v1 approval and only then forward it.

    Orsik Mobile-v1 permits the choices once/deny only. Any other choice, a
    malformed body, wrong field types or extra fields are rejected closed and
    NEVER reach Hermes.
    """
    state: BridgeState = request.app[STATE_KEY]
    if state.tokens.lookup(_device_token(request)) is None:
        return _closed(401, "invalid_credentials")
    run_id = request.match_info.get("run_id", "")
    if not core.is_safe_run_id(run_id):
        return _closed(400, "invalid_run_id")
    try:
        body = await request.json()
    except web.HTTPRequestEntityTooLarge:
        return _closed(413, "body_too_large")
    except Exception:
        return _closed(400, "invalid_json")
    ok, code = core.validate_approval_body(body)
    if not ok:
        return _closed(400, code)
    # Forward a canonical body (only the two contract fields) upstream.
    payload = json.dumps(
        {"choice": body["choice"], "request_id": body["request_id"]}
    ).encode("utf-8")
    return await _proxy(request, state, f"/v1/runs/{run_id}/approval", body_override=payload)


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
    if state.tokens.lookup(_device_token(request)) is None:
        return _closed(401, "invalid_credentials")
    session_id = request.match_info.get("session_id", "")
    if not core._safe_segment(session_id):
        return _closed(400, "invalid_session_id")
    if request.query:
        return _closed(403, "query_not_allowed")
    body = await request.read() if request.can_read_body else None
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


async def _pump_chat_stream(
    state: BridgeState, buf: runhub.RunBuffer, upstream_path: str,
    body: bytes | None, headers: dict[str, str],
) -> None:
    """Read the upstream SSE stream into the run buffer, independent of clients."""
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
            buf.fail(upstream.status, content_type, payload)
            return
        decoder = SSEDecoder()
        async for chunk in upstream.content.iter_any():
            try:
                frames = decoder.feed(chunk)
            except SSEFrameTooLarge as exc:
                # Frames that completed before the violation are still relayed;
                # the oversized frame itself is dropped and the upstream closed,
                # never followed by a resync (that would be a hole).
                upstream.release()
                for frame in exc.frames:
                    buf.append(frame)
                    if buf.run_id:
                        state.runhub.register(buf)
                if buf.state == "pending":
                    buf.fail(502, "application/json",
                             b'{"error":{"code":"upstream_frame_too_large"}}')
                else:
                    buf.abort_reason = "upstream_frame_too_large"
                    logger.warning("upstream SSE frame exceeded %d bytes", exc.limit)
                return
            for frame in frames:
                buf.append(frame)
                if buf.run_id:
                    state.runhub.register(buf)
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
    if state.tokens.lookup(_device_token(request)) is None:
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

    Fail-closed mapping (P13.1):
      * 200                  -> 409 ``resume_gap`` (run known, transport gone: the
                                state is indeterminate and non-terminal)
      * 404                  -> 404 ``run_not_found`` (confirmed absent upstream)
      * 3xx                  -> 502 ``upstream_redirect`` (never followed)
      * 401/403/5xx/other    -> 502 ``run_state_indeterminate`` (never a false 404)
      * timeout/network/etc. -> 502 ``run_state_indeterminate`` (never a false 404)

    Only a *confirmed* upstream 404 is reported as "not found"; every other
    outcome is a distinct, non-terminal error so the client keeps run ownership
    (P6/P7). No run completion is invented and no chat POST is repeated.
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
    if state.tokens.lookup(_device_token(request)) is None:
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
    return await _proxy(request, state, match.upstream_path)


async def _proxy(
    request: web.Request,
    state: BridgeState,
    upstream_path: str,
    body_override: bytes | None = None,
) -> web.StreamResponse:
    # Query surface is allowlisted per route; unknown keys never reach upstream.
    allowed = core.allowed_query_keys(request.method, upstream_path)
    for key in request.query.keys():
        if key not in allowed:
            return _closed(403, "query_not_allowed")

    upstream_url = state.upstream + upstream_path
    if request.query_string:
        upstream_url += "?" + request.query_string

    if body_override is not None:
        body: bytes | None = body_override
    else:
        body = await request.read() if request.can_read_body else None

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
            return _closed(502, "upstream_unavailable")  # type: ignore[return-value]

        if 300 <= upstream.status < 400:
            # Fail closed; do not relay a Location that could induce an SSRF hop.
            upstream.release()
            return _closed(502, "upstream_redirect")  # type: ignore[return-value]

        content_type = upstream.headers.get("Content-Type", "application/json")
        downstream = web.StreamResponse(status=upstream.status)
        downstream.content_type = content_type.split(";")[0].strip()
        if "text/event-stream" in content_type:
            downstream.headers["Cache-Control"] = "no-cache"
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
    issue.add_argument("--base-url", default=os.environ.get("BRIDGE_PUBLIC_BASE_URL", ""),
                       help="HTTPS base URL for the orsik://pair deep link/QR")
    sub.add_parser("list-devices", help="list paired devices (no secrets)")
    revoke = sub.add_parser("revoke-device", help="revoke a device by device_id")
    revoke.add_argument("device_id")
    args = parser.parse_args(argv)

    env = _load_env()
    state = build_state_from_env(env)
    try:
        if args.cmd == "pairing-code":
            issued = state.codes.issue()
            if issued is None:
                print("error: pairing-code capacity reached", file=sys.stderr)
                return 1
            code, ttl = issued
            print(f"Pairing code: {pairing.format_code(code)}  (valid {ttl}s, single-use)")
            if args.base_url:
                uri = pairing.pairing_uri(args.base_url, code)
                print(f"Deep link:    {uri}")
                qr = pairing.render_ascii_qr(uri)
                print(qr if qr else "(install 'qrencode' for an ASCII QR; deep link above is equivalent)")
            else:
                print("(set BRIDGE_PUBLIC_BASE_URL or --base-url to print the orsik://pair deep link/QR)")
            return 0
        if args.cmd == "list-devices":
            for device in state.store.list_devices():
                flag = "revoked" if device["revoked"] else "active"
                print(f"{device['device_id']}  {flag:7}  {device['label']}  [{device['profile']}]")
            return 0
        if args.cmd == "revoke-device":
            if state.store.revoke_device_by_id(args.device_id):
                print(f"revoked {args.device_id}")
                return 0
            print("error: no such device_id", file=sys.stderr)
            return 1
        return 2
    finally:
        state.store.close()


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "admin":
        logging.basicConfig(level=logging.WARNING, format="%(message)s")
        return _admin_main(argv[1:])
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    env = _load_env()
    state = build_state_from_env(env)
    bind = env.get("BRIDGE_BIND", "127.0.0.1")
    port = int(env.get("BRIDGE_PORT", "9842"))
    if bind not in {"127.0.0.1", "::1", "localhost"}:
        raise SystemExit("Bridge refuses to bind a non-loopback address")
    logger.info(
        "Orsik Mobile Bridge on http://%s:%d -> %s",
        bind, port, _safe_upstream_label(state.upstream),
    )
    web.run_app(create_app(state), host=bind, port=port, print=None)
    return 0


if __name__ == "__main__":
    sys.exit(main())
