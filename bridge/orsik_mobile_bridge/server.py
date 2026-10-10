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
  GET/POST /api/sessions…, POST/GET /v1/runs…  (proxied, device token)
  POST /admin/pairing-code                  (operator-only, admin token)

Security hardening (P12.1): approval choices are narrowed to once/deny locally,
upstream redirects are never followed (no master-key hop), the query surface is
allowlisted, and pairing/admin are rate-limited with true store caps.

TLS is provided out-of-band (e.g. Tailscale Serve to 127.0.0.1); this process
binds loopback plaintext only.
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import sys
import time
from pathlib import Path
from urllib.parse import urlsplit

from aiohttp import ClientSession, ClientTimeout, web

from . import core

logger = logging.getLogger("orsik_mobile_bridge")

ADMIN_HEADER = "X-Admin-Token"
ADMIN_PATH = "/admin/pairing-code"
APPROVAL_PATH = "/v1/runs/{run_id}/approval"
# Match the official api_server request bound (10_000_000) so the bridge does
# not silently reject the client's supported image bodies (<= 8 MiB encoded).
_MAX_BODY_BYTES = 10_000_000
_PAIR_PER_MINUTE = 60
_ADMIN_PER_MINUTE = 60


class BridgeState:
    def __init__(self, upstream: str, master_key: str, admin_token: str) -> None:
        self.upstream = upstream.rstrip("/")
        self.master_key = master_key
        self.admin_token = admin_token
        self.codes = core.CodeStore()
        self.tokens = core.TokenStore()
        self.pair_limiter = core.RateLimiter(_PAIR_PER_MINUTE, 60.0)
        self.admin_limiter = core.RateLimiter(_ADMIN_PER_MINUTE, 60.0)


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


def build_state_from_env(env: dict[str, str]) -> BridgeState:
    upstream = env.get("BRIDGE_UPSTREAM", "http://127.0.0.1:8899")
    admin_token = env.get("BRIDGE_ADMIN_TOKEN") or ""
    master_key = ""
    hermes_env = env.get("BRIDGE_HERMES_ENV", "")
    if hermes_env:
        master_key = _load_env_file(hermes_env).get("API_SERVER_KEY", "")
    master_key = master_key or env.get("HERMES_API_KEY", "")
    if not master_key:
        raise SystemExit("Bridge needs the Hermes API key (BRIDGE_HERMES_ENV or HERMES_API_KEY)")
    if not admin_token:
        raise SystemExit("Bridge needs BRIDGE_ADMIN_TOKEN to issue pairing codes")
    return BridgeState(upstream=upstream, master_key=master_key, admin_token=admin_token)


def _device_token(request: web.Request) -> str | None:
    header = request.headers.get("Authorization", "")
    if not header.lower().startswith("bearer "):
        return None
    return header[7:].strip() or None


def _closed(status: int, code: str) -> web.Response:
    # Closed error shape: no body echo, no URL, no key, no internal detail.
    return web.json_response({"error": {"code": code}}, status=status)


STATE_KEY = web.AppKey("state", BridgeState)


def create_app(state: BridgeState) -> web.Application:
    app = web.Application(client_max_size=_MAX_BODY_BYTES)
    app[STATE_KEY] = state
    app.router.add_get("/health", _handle_health)
    app.router.add_get("/v1/capabilities", _handle_capabilities)
    app.router.add_post("/v1/devices/pair", _handle_pair)
    app.router.add_post("/v1/devices/self/revoke", _handle_self_revoke)
    app.router.add_post(ADMIN_PATH, _handle_admin_code)
    # Approval gets a dedicated, strictly validated handler (before the catch-all).
    app.router.add_post(APPROVAL_PATH, _handle_approval)
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


async def _handle_admin_code(request: web.Request) -> web.Response:
    state: BridgeState = request.app[STATE_KEY]
    # Authenticate first so only the legitimate operator consumes the limit
    # (an unauthenticated caller must not be able to lock the operator out).
    provided = request.headers.get(ADMIN_HEADER, "")
    if not state.admin_token or not hmac.compare_digest(provided, state.admin_token):
        return _closed(403, "admin_forbidden")
    if not state.admin_limiter.allow():
        return _closed(429, "rate_limited")
    issued = state.codes.issue()
    if issued is None:
        return _closed(503, "code_capacity_reached")
    code, ttl = issued
    return web.json_response({"code": code, "expires_in": ttl})


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


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    env = dict(os.environ)
    env_file = env.get("BRIDGE_ENV_FILE")
    if env_file:
        env.update(_load_env_file(env_file))
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
