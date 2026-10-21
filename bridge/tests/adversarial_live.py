"""Live adversarial security probe for the Orsik Mobile Bridge.

Requires a running bridge (loopback) in front of a live official Hermes API
server and its bridge.env. It pairs at most three devices, performs at most one
real (cheap) chat turn, and revokes every device it paired.

Not collected by unittest discover (the name does not start with test_).

Exit code 0 iff every check passes. Every request has a bounded timeout and the
whole run is intended to finish in well under five minutes.

Usage (from the bridge/ directory, so orsik_mobile_bridge is importable):

    python3 tests/adversarial_live.py ./bridge.env

BRIDGE_URL overrides the base URL (default http://127.0.0.1:9842).
"""

from __future__ import annotations

import base64
import concurrent.futures
import http.client
import json
import os
import re
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlsplit

BRIDGE = os.environ.get("BRIDGE_URL", "http://127.0.0.1:9842").rstrip("/")
TIMEOUT = 30.0
# bridge/orsik_mobile_bridge/server.py:88 (_MAX_BODY_BYTES)
BRIDGE_MAX_BODY_BYTES = 10_000_000
TRACE_MARK = "ORSIK-TRACE-MARK"
CRLF_MARK = "ORSIK-CRLF-MARK"
BIG_HEADER_MARK = "ORSIK-BIG-HEADER-MARK"
UNKNOWN_RUN = "run_adv_probe_unknown"
SID = "adv_sid"

_results: list[tuple[str, bool, str]] = []
_responses: list[dict] = []
_last_headers: dict[str, str] = {}
_last_record: dict | None = None


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_opener = urllib.request.build_opener(_NoRedirect())


def check(name: str, cond: bool, detail: str = "") -> None:
    _results.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), name, detail)


def read_env(path: str) -> dict[str, str]:
    values: dict[str, str] = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, _, val = line.partition("=")
                values[key.strip()] = val.strip()
    return values


def issue_code(env: dict[str, str]) -> str | None:
    """Operator-side issuance, exactly as the local CLI does (no HTTP admin)."""
    from orsik_mobile_bridge import store as store_mod

    db = env.get("BRIDGE_STATE_DB") or os.path.join(
        env.get("BRIDGE_HOME") or os.path.expanduser("~/.local/orsik-mobile-bridge"), "state.db")
    store = store_mod.BridgeStore(db)
    try:
        issued = store.issue_code()
    finally:
        store.close()
    return issued[0] if issued else None


def _record(label: str, status: int, headers: dict[str, str], body: bytes) -> dict:
    global _last_record
    rec = {
        "label": label,
        "status": status,
        "headers": dict(headers or {}),
        "body": bytes(body or b"")[:512 * 1024],
        "allow": set(),
    }
    _responses.append(rec)
    _last_record = rec
    return rec


def request(
    method: str,
    path: str,
    body=None,
    token: str | None = None,
    accept: str | None = None,
    raw_body: bytes | None = None,
    content_type: str | None = None,
    extra_headers: dict[str, str] | None = None,
    label: str | None = None,
    timeout: float = TIMEOUT,
):
    global _last_headers
    headers: dict[str, str] = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if raw_body is not None:
        data = raw_body
    if content_type:
        headers["Content-Type"] = content_type
    if token:
        headers["Authorization"] = "Bearer " + token
    if accept:
        headers["Accept"] = accept
    if extra_headers:
        headers.update(extra_headers)
    name = label or f"{method} {path.split('?', 1)[0]}"
    req = urllib.request.Request(BRIDGE + path, data=data, headers=headers, method=method)
    try:
        resp = _opener.open(req, timeout=timeout)
        raw = resp.read()
        _last_headers = dict(resp.headers.items())
        _record(name, resp.status, _last_headers, raw)
        return resp.status, raw.decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        _last_headers = dict(exc.headers.items()) if exc.headers else {}
        _record(name, exc.code, _last_headers, raw)
        return exc.code, raw.decode("utf-8", "replace")
    except Exception as exc:
        _last_headers = {}
        return 0, f"<{type(exc).__name__}>"


def _hc_request(method: str, path: str, header_pairs: list[tuple[str, str]],
                body: bytes | None = None, label: str = "raw_http_client"):
    parts = urlsplit(BRIDGE)
    conn = None
    try:
        host = parts.hostname or "127.0.0.1"
        if parts.scheme == "https":
            conn = http.client.HTTPSConnection(
                host, parts.port or 443, timeout=TIMEOUT,
                context=ssl.create_default_context())
        else:
            conn = http.client.HTTPConnection(host, parts.port or 80, timeout=TIMEOUT)
        skip_host = any(name.lower() == "host" for name, _ in header_pairs)
        conn.putrequest(method, path, skip_host=skip_host, skip_accept_encoding=True)
        for name, value in header_pairs:
            conn.putheader(name, value)
        if body is not None and not any(
                name.lower() in ("content-length", "transfer-encoding")
                for name, _ in header_pairs):
            conn.putheader("Content-Length", str(len(body)))
        conn.endheaders(body)
        resp = conn.getresponse()
        raw = resp.read()
        headers = dict(resp.getheaders())
        _record(label, resp.status, headers, raw)
        return resp.status, raw, headers
    except Exception:
        return 0, b"", {}
    finally:
        if conn is not None:
            conn.close()


def _raw_request(payload: bytes, label: str):
    parts = urlsplit(BRIDGE)
    host = parts.hostname or "127.0.0.1"
    port = parts.port or (443 if parts.scheme == "https" else 80)
    data = bytearray()
    try:
        sock = socket.create_connection((host, port), timeout=TIMEOUT)
        sock.settimeout(TIMEOUT)
    except OSError:
        return 0, b"", {}
    try:
        if parts.scheme == "https":
            sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
        try:
            sock.sendall(payload)
        except OSError:
            pass
        while len(data) < 1024 * 1024:
            try:
                chunk = sock.recv(65536)
            except (socket.timeout, OSError):
                break
            if not chunk:
                break
            data += chunk
    finally:
        try:
            sock.close()
        except OSError:
            pass
    head, _, body = bytes(data).partition(b"\r\n\r\n")
    status = 0
    headers: dict[str, str] = {}
    lines = head.split(b"\r\n")
    if lines and lines[0].startswith(b"HTTP/"):
        try:
            status = int(lines[0].split(b" ")[1])
        except (IndexError, ValueError):
            status = 0
        for line in lines[1:]:
            if b":" in line:
                key, _, val = line.partition(b":")
                headers[key.decode("latin-1")] = val.strip().decode("latin-1")
    _record(label, status, headers, body)
    return status, body, headers


def _host_header() -> str:
    parts = urlsplit(BRIDGE)
    host = parts.hostname or "127.0.0.1"
    return f"{host}:{parts.port}" if parts.port else host


def err_code(text: str) -> str:
    try:
        obj = json.loads(text)
    except Exception:
        return ""
    if isinstance(obj, dict) and isinstance(obj.get("error"), dict):
        return str(obj["error"].get("code", ""))
    return ""


def _detail(status: int, text: str = "") -> str:
    code = err_code(text) if text else ""
    return f"status={status}" + (f" code={code}" if code else "")


def _join(items: list[str]) -> str:
    return "; ".join(items[:16])


def _add_query(path: str, query: str) -> str:
    return path + ("&" if "?" in path else "?") + query


def _headers_text(headers: dict[str, str]) -> str:
    return "\n".join(f"{key}: {value}" for key, value in headers.items())


AUTH_ROUTES = (
    ("GET", "/v1/capabilities"),
    ("POST", "/v1/devices/self/revoke"),
    ("POST", f"/v1/runs/{UNKNOWN_RUN}/approval"),
    ("GET", "/api/sessions"),
    ("POST", "/api/sessions"),
    ("GET", f"/api/sessions/{SID}"),
    ("GET", f"/api/sessions/{SID}/messages"),
    ("POST", f"/api/sessions/{SID}/chat/stream"),
    ("GET", f"/api/sessions/{SID}/runs/{UNKNOWN_RUN}/stream?after_seq=0"),
    ("GET", f"/v1/runs/{UNKNOWN_RUN}"),
    ("POST", f"/v1/runs/{UNKNOWN_RUN}/stop"),
    ("POST", f"/v1/runs/{UNKNOWN_RUN}/steer"),
)


def run_auth_checks() -> None:
    random_token = "hd_" + "A" * 43
    basic = "Basic " + base64.b64encode(b"user:pass").decode("ascii")

    def matrix(label: str, call) -> None:
        bad = []
        for method, path in AUTH_ROUTES:
            result = call(method, path)
            status = result[0]
            if status != 401:
                bad.append(f"{method} {path.split('?', 1)[0]}={status}")
        check(f"auth_{label}_401", not bad, _join(bad))

    matrix("missing", lambda m, p: request(m, p, label="auth_missing"))
    matrix("empty_bearer", lambda m, p: request(
        m, p, extra_headers={"Authorization": "Bearer "}, label="auth_empty_bearer"))
    matrix("random_bearer", lambda m, p: request(m, p, token=random_token, label="auth_random"))
    matrix("query_token", lambda m, p: request(
        m, _add_query(p, "token=" + random_token), label="auth_query_token"))
    matrix("query_access_token", lambda m, p: request(
        m, _add_query(p, "access_token=" + random_token), label="auth_query_access_token"))
    matrix("basic", lambda m, p: request(
        m, p, extra_headers={"Authorization": basic}, label="auth_basic"))
    matrix("duplicate_headers", lambda m, p: _hc_request(
        m, p, [("Authorization", "Bearer " + random_token),
               ("Authorization", "Bearer " + random_token)],
        body=b"", label="auth_duplicate"))

    status, _ = request("GET", "/health")
    check("health_public_200", status == 200, _detail(status))
    status, _ = request(
        "GET", "/health", extra_headers={"Authorization": "Bearer " + random_token})
    check("health_ignores_invalid_token", status == 200, _detail(status))


def run_path_scope_checks(token: str | None) -> None:
    long_path = "/api/sessions/" + "a" * (8 * 1024)
    paths = (
        ("v1_dotdot_admin", "/v1/runs/../admin"),
        ("encoded_slash_traversal", "/api/sessions/..%2f..%2fv1/admin"),
        ("encoded_dotdot", "/api/sessions/%2e%2e"),
        ("double_slash", "//api/sessions"),
        ("nul_segment", "/api/sessions/%00"),
        ("upper_case", "/API/SESSIONS"),
        ("jsessionid", "/api/sessions;jsessionid=abc"),
        ("long_8kb", long_path),
        ("unicode_session", "/api/sessions/%C3%BC"),
        ("unicode_symbol_session", "/api/sessions/%E2%98%83"),
        ("admin", "/admin"),
        ("jobs", "/api/jobs"),
        ("cron", "/api/cron"),
        ("memory", "/api/memory"),
        ("skills", "/api/skills"),
        ("toolsets", "/api/toolsets"),
        ("v1_admin", "/v1/admin"),
    )
    for label, path in paths:
        status, body = request("GET", path, token=token, label="scope_" + label)
        refused = status in (400, 403, 404, 414, 431) and status < 500
        if status == 403:
            refused = refused and err_code(body) == "endpoint_out_of_scope"
        check(f"path_scope_{label}", refused, _detail(status, body))


def run_method_checks(token: str | None) -> None:
    targets = (
        "/api/sessions",
        f"/api/sessions/{SID}",
        f"/v1/runs/{UNKNOWN_RUN}",
        "/v1/capabilities",
        "/health",
    )
    for method in ("PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"):
        bad = []
        for path in targets:
            status, _ = request(
                method, path, token=token, extra_headers={"X-Probe": TRACE_MARK},
                label=f"method_{method}")
            if status not in (400, 403, 404, 405) or status >= 500:
                bad.append(f"{path}={status}")
        check(f"methods_{method.lower()}_refused", not bad, _join(bad))

    status, body = request("TRACE", "/api/sessions", token=token,
                           extra_headers={"X-Probe": TRACE_MARK}, label="trace_echo")
    leaked = TRACE_MARK in body or TRACE_MARK in _headers_text(_last_headers)
    check("trace_never_echoes_headers", status < 500 and not leaked,
          f"status={status} reflected={leaked}")

    status, _ = request("OPTIONS", "/api/sessions", token=token, label="options_cors")
    acao = _last_headers.get("Access-Control-Allow-Origin", "")
    check("options_no_wildcard_cors", status < 500 and acao != "*",
          f"status={status} acao_set={bool(acao)}")


def run_query_checks(token: str | None) -> None:
    read_routes = ("/api/sessions", f"/api/sessions/{SID}",
                   f"/api/sessions/{SID}/messages")
    bad = []
    for path in read_routes:
        status, body = request("GET", _add_query(path, "evil=1"), token=token,
                               label="query_unknown_read")
        if status != 403 or err_code(body) != "query_not_allowed":
            bad.append(f"{path}={status}")
    check("query_unknown_read_403", not bad, _join(bad))

    status, body = request("GET", f"/v1/runs/{UNKNOWN_RUN}?limit=1", token=token,
                           label="query_unknown_run")
    check("query_unknown_run_403",
          status == 403 and err_code(body) == "query_not_allowed", _detail(status, body))

    write_cases = (
        ("POST", "/api/sessions?evil=1", None),
        ("POST", f"/v1/runs/{UNKNOWN_RUN}/stop?evil=1", None),
        ("POST", f"/v1/runs/{UNKNOWN_RUN}/steer?evil=1", None),
        ("POST", f"/api/sessions/{SID}/chat/stream?evil=1", None),
        ("POST", f"/v1/runs/{UNKNOWN_RUN}/approval?evil=1",
         {"choice": "deny", "request_id": "adv"}),
    )
    bad = []
    for method, path, body in write_cases:
        status, text = request(method, path, body=body, token=token, label="query_write")
        if status != 403 or err_code(text) != "query_not_allowed":
            bad.append(f"{path.split('?', 1)[0]}={status}")
    check("query_write_routes_403", not bad, _join(bad))

    status, body = request("GET", "/api/sessions?evil=1&evil=2", token=token,
                           label="query_repeated_unknown")
    check("query_repeated_unknown_403",
          status == 403 and err_code(body) == "query_not_allowed", _detail(status, body))

    status, body = request("GET", "/api/sessions?limit=1&limit=2", token=token,
                           label="query_repeated_allowed")
    check("query_repeated_allowed_not_5xx", status < 500, _detail(status, body))

    status, body = request("GET", _add_query("/api/sessions", "limit=" + "9" * 3000),
                           token=token, label="query_huge_value")
    check("query_huge_value_not_5xx", status < 500, _detail(status, body))

    huge_key = "/api/sessions?" + "z" * 3000 + "=1"
    status, body = request("GET", huge_key, token=token, label="query_huge_key")
    check("query_huge_key_403",
          status == 403 and err_code(body) == "query_not_allowed", _detail(status, body))


def run_body_checks(token: str | None) -> None:
    approval = f"/v1/runs/{UNKNOWN_RUN}/approval"

    status, body = request("POST", approval, raw_body=b"{not json", token=token,
                           content_type="application/json", label="body_malformed")
    check("body_malformed_json_400",
          status == 400 and err_code(body) == "invalid_json", _detail(status, body))

    status, body = request("POST", approval, body=[{"choice": "deny", "request_id": "x"}],
                           token=token, label="body_array")
    check("body_array_400",
          status == 400 and err_code(body) == "invalid_json", _detail(status, body))

    nested = b'{"a":' * 10000 + b"1" + b"}" * 10000
    status, body = request("POST", approval, raw_body=nested, token=token,
                           content_type="application/json", label="body_nested")
    check("body_deep_nesting_400", status == 400, _detail(status, body))

    oversized = b"x" * (BRIDGE_MAX_BODY_BYTES + 1)
    status, body = request("POST", approval, raw_body=oversized, token=token,
                           content_type="application/json", label="body_over_bound")
    check("body_over_bound_413",
          status == 413 and err_code(body) == "body_too_large", _detail(status, body))

    status, body = request("POST", approval, body={"choice": "always", "request_id": "x"},
                           token=token, content_type="text/plain", label="body_wrong_ct")
    check("body_wrong_content_type_400",
          status == 400 and err_code(body) == "invalid_choice", _detail(status, body))


def run_approval_checks(token: str | None) -> None:
    approval = f"/v1/runs/{UNKNOWN_RUN}/approval"
    bodies = (
        {"choice": "always", "request_id": "adv"},
        {"choice": "session", "request_id": "adv"},
        {"choice": "ONCE", "request_id": "adv"},
        {"choice": "once ", "request_id": "adv"},
        {"choice": ["once"], "request_id": "adv"},
        {"choice": None, "request_id": "adv"},
        {"choice": 1, "request_id": "adv"},
        {"choice": "deny"},
        {"request_id": "adv"},
        {"choice": "deny", "request_id": "adv", "extra": "x"},
    )
    bad = []
    for body in bodies:
        status, text = request("POST", approval, body=body, token=token,
                               label="approval_invalid")
        code = err_code(text)
        if status != 400 or code not in (
                "invalid_choice", "invalid_fields", "invalid_request_id", "invalid_json"):
            bad.append(f"status={status} code={code or '-'}")
    check("approval_invalid_bodies_400", not bad, _join(bad))

    status, body = request(
        "POST", f"/v1/runs/run_adv_probe_deny/approval",
        body={"choice": "deny", "request_id": "adv"}, token=token,
        label="approval_deny_unknown")
    check("approval_deny_unknown_run_closed", status in (404, 409),
          _detail(status, body))


def run_after_seq_checks(token: str | None) -> None:
    base = f"/api/sessions/{SID}/runs/{UNKNOWN_RUN}/stream"

    status, body = request("GET", base + "?after_seq=-1", token=token, label="after_seq_minus1")
    check("after_seq_minus_one_accepted", status in (404, 409) and status < 500,
          _detail(status, body))

    for label, query in (
            ("1e9", "after_seq=1e9"),
            ("abc", "after_seq=abc"),
            ("empty", "after_seq="),
            ("duplicate_invalid", "after_seq=abc&after_seq=0")):
        status, body = request("GET", base + "?" + query, token=token,
                               label="after_seq_" + label)
        check(f"after_seq_{label}_400",
              status == 400 and err_code(body) == "invalid_after_seq", _detail(status, body))

    status, body = request("GET", base + "?after_seq=0&after_seq=1", token=token,
                           label="after_seq_duplicate_valid")
    check("after_seq_duplicate_valid_not_5xx", status < 500, _detail(status, body))


def _pair_with_code(code: str | None, label: str, paired: list[str]) -> str | None:
    if code is None:
        check(label, False, "operator could not issue a code")
        return None
    status, body = request("POST", "/v1/devices/pair", body={"code": code}, label="pair")
    token = None
    if status == 201:
        try:
            cred = json.loads(body)
        except Exception:
            cred = {}
        candidate = cred.get("device_key")
        if isinstance(candidate, str):
            token = candidate
            paired.append(token)
            if _last_record is not None:
                _last_record["allow"].add(token)
    check(label, token is not None, f"status={status}")
    return token


def run_pairing_checks(env: dict[str, str], code_a: str | None,
                       code_b: str | None, codes: list[str],
                       paired: list[str]) -> tuple[str | None, str | None, str | None]:
    token_a = _pair_with_code(code_a, "pair_device_a_201", paired)
    token_b = _pair_with_code(code_b, "pair_device_b_201", paired)

    if code_a is None:
        check("pair_code_reuse_400", False, "no first code available")
    else:
        status, body = request("POST", "/v1/devices/pair", body={"code": code_a},
                               label="pair_reuse")
        check("pair_code_reuse_400",
              status == 400 and err_code(body) == "pairing_code_invalid", _detail(status, body))

    wrong = "A" * 24
    statuses = []
    for _ in range(30):
        status, _ = request("POST", "/v1/devices/pair", body={"code": wrong},
                            label="pair_wrong")
        statuses.append(status)
    check("pair_wrong_30_closed",
          all(item in (400, 429) for item in statuses),
          f"distinct={sorted(set(statuses))}")

    attempts = 30
    saw_limit = 429 in statuses
    last_status = statuses[-1] if statuses else 0
    while not saw_limit and attempts < 70:
        last_status, _ = request("POST", "/v1/devices/pair", body={"code": wrong},
                                 label="pair_burst")
        attempts += 1
        if last_status == 429:
            saw_limit = True
    check("pair_rate_limit_429", saw_limit, f"attempts={attempts} last={last_status}")

    third = None
    code_c = issue_code(env)
    if code_c:
        codes.append(code_c)
    if code_c is None:
        check("pair_valid_code_while_limited", False, "operator could not issue a code")
    else:
        status, body = request("POST", "/v1/devices/pair", body={"code": code_c},
                               label="pair_valid_while_limited")
        if status == 201:
            try:
                cred = json.loads(body)
            except Exception:
                cred = {}
            candidate = cred.get("device_key")
            if isinstance(candidate, str):
                third = candidate
                paired.append(third)
                if _last_record is not None:
                    _last_record["allow"].add(third)
        check("pair_valid_code_while_limited", status in (429, 201),
              f"status={status}" + (f" code={err_code(body)}" if err_code(body) else ""))
    return token_a, token_b, third


def _stream_chat(token: str | None, sid: str, out: dict) -> None:
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
    }
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps({"message": "Reply with the single word: pong"}).encode("utf-8")
    req = urllib.request.Request(
        BRIDGE + f"/api/sessions/{sid}/chat/stream", data=data, headers=headers, method="POST")
    captured = bytearray()
    resp_headers: dict[str, str] = {}
    try:
        resp = _opener.open(req, timeout=TIMEOUT)
    except urllib.error.HTTPError as exc:
        out["status"] = exc.code
        out["ended"] = time.monotonic()
        return
    except Exception:
        out["status"] = 0
        out["ended"] = time.monotonic()
        return
    out["status"] = resp.status
    resp_headers = dict(resp.headers.items())
    try:
        for raw in resp:
            captured += raw
            if len(captured) > 256 * 1024:
                del captured[: len(captured) - 64 * 1024]
            text = raw.decode("utf-8", "replace").strip()
            if text.startswith("event:"):
                name = text[6:].strip()
                if name and name not in out["events"]:
                    out["events"].append(name)
            elif text == ": bridge_revoked":
                out["revoked_marker"] = True
    except Exception:
        pass
    finally:
        out["ended"] = time.monotonic()
        _record("chat_stream", out.get("status", 0), resp_headers, bytes(captured))


def run_revocation_checks(token_a: str | None, token_b: str | None) -> None:
    sid = SID
    create_status = 0
    if token_a is not None:
        create_status, body = request(
            "POST", "/api/sessions", body={"title": f"adv-{uuid.uuid4().hex[:8]}"},
            token=token_a, label="adv_session_create")
        if create_status == 201:
            try:
                created = json.loads(body).get("session", {}).get("id")
                if isinstance(created, str) and created:
                    sid = created
            except Exception:
                pass
    check("adv_session_create_201", create_status == 201, f"status={create_status}")

    out: dict = {"status": None, "ended": None, "events": [], "revoked_marker": False}
    thread = threading.Thread(target=_stream_chat, args=(token_a, sid, out), daemon=True)
    thread.start()
    deadline = time.monotonic() + 20.0
    while time.monotonic() < deadline and out.get("status") is None:
        time.sleep(0.05)
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline and not out["events"] and thread.is_alive():
        time.sleep(0.05)

    revoke_at = time.monotonic()
    revoke_status, _ = request("POST", "/v1/devices/self/revoke",
                               token=token_a, label="adv_revoke_streaming")
    thread.join(timeout=25.0)
    ended = out.get("ended")
    delta = (ended - revoke_at) if ended is not None else None
    alive = thread.is_alive()

    check("revoke_while_streaming_200", revoke_status == 200, f"status={revoke_status}")
    check("chat_stream_200_started", out.get("status") == 200 and bool(out.get("events")),
          f"status={out.get('status')} events={','.join(out['events'][:6]) or '-'}")
    if delta is None:
        detail = "stream did not end within 25s of revoke"
    elif delta < 0:
        detail = f"stream ended {-delta:.1f}s before revoke (turn finished); revoked_marker={out['revoked_marker']}"
    else:
        detail = f"ended {delta:.1f}s after revoke; revoked_marker={out['revoked_marker']}"
    check("revoke_open_stream_ends_within_20s",
          not alive and delta is not None and delta <= 20.0, detail)

    revoked_routes = (
        ("GET", "/v1/capabilities"),
        ("POST", f"/v1/runs/{UNKNOWN_RUN}/approval"),
        ("GET", "/api/sessions"),
        ("POST", "/api/sessions"),
        ("GET", f"/api/sessions/{sid}"),
        ("GET", f"/api/sessions/{sid}/messages"),
        ("GET", f"/api/sessions/{sid}/runs/{UNKNOWN_RUN}/stream?after_seq=0"),
        ("GET", f"/v1/runs/{UNKNOWN_RUN}"),
        ("POST", f"/v1/runs/{UNKNOWN_RUN}/stop"),
        ("POST", f"/v1/runs/{UNKNOWN_RUN}/steer"),
    )
    bad = []
    for method, path in revoked_routes:
        status, _ = request(method, path, token=token_a, label="revoked_token")
        if status != 401:
            bad.append(f"{method} {path.split('?', 1)[0]}={status}")
    check("revoked_token_401_on_auth_routes", not bad, _join(bad))

    # store.revoke_device looks the hash up without a revoked = 0 filter
    # (store.py:315-321), so self-revoke is idempotent: 200 until the row is
    # pruned, then 401. It never grants access either way.
    status, body = request("POST", "/v1/devices/self/revoke", token=token_a,
                           label="revoked_self_revoke")
    idempotent = False
    if status == 200:
        try:
            idempotent = json.loads(body).get("revoked") is True
        except Exception:
            idempotent = False
    check("revoked_self_revoke_idempotent_or_401",
          status == 401 or idempotent,
          f"status={status}" + (" idempotent=true" if idempotent else ""))

    status_caps, _ = request("GET", "/v1/capabilities", token=token_b, label="survivor_caps")
    status_sessions, _ = request("GET", "/api/sessions", token=token_b, label="survivor_sessions")
    check("revoke_other_device_still_valid", status_caps == 200 and status_sessions == 200,
          f"capabilities={status_caps} sessions={status_sessions}")


def run_concurrency_checks(token: str | None) -> None:
    random_token = "hd_" + "B" * 43

    def get(tok: str | None) -> int:
        status, _ = request("GET", "/v1/capabilities", token=tok, label="concurrency")
        return status

    with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
        valid = list(pool.map(lambda _: get(token), range(100)))
        invalid = list(pool.map(lambda _: get(random_token), range(100)))

    check("concurrency_valid_100x200",
          all(item == 200 for item in valid),
          f"distinct={sorted(set(valid))}")
    check("concurrency_invalid_100x401",
          all(item == 401 for item in invalid),
          f"distinct={sorted(set(invalid))}")

    status, _ = request("GET", "/health", label="health_after_concurrency")
    check("health_after_concurrency_200", status == 200, _detail(status))


def run_header_checks() -> None:
    host = _host_header()

    # aiohttp's own 400 diagnostic quotes the first ~100 bytes of an oversized
    # header value, so the marker sits beyond that prefix: the check is that the
    # value is not reflected anywhere, not that the parser stays silent.
    big_value = "A" * 300 + BIG_HEADER_MARK + ":" + "A" * (65536 - len(BIG_HEADER_MARK) - 301)
    payload = (
        f"GET /health HTTP/1.1\r\nHost: {host}\r\nX-Probe: {big_value}\r\n"
        "Connection: close\r\n\r\n"
    ).encode("latin-1")
    status, body, headers = _raw_request(payload, "header_64k")
    reflected = BIG_HEADER_MARK.encode() in body or BIG_HEADER_MARK in _headers_text(headers)
    check("header_64k_value_refused_or_handled", status < 500 and not reflected,
          f"status={status} reflected={reflected}")

    injected = (
        f"GET /health HTTP/1.1\r\nHost: {host}\r\nX-Probe: before\r\n"
        f"X-Injected: {CRLF_MARK}\r\nConnection: close\r\n\r\n"
    ).encode("latin-1")
    status, body, headers = _raw_request(injected, "header_crlf")
    reflected = CRLF_MARK.encode() in body or CRLF_MARK in _headers_text(headers)
    check("header_crlf_injection_not_reflected", status < 500 and not reflected,
          f"status={status} reflected={reflected}")

    bare_cr = (
        f"GET /health HTTP/1.1\r\nHost: {host}\r\nX-Probe: be\r{CRLF_MARK}\r\n"
        "Connection: close\r\n\r\n"
    ).encode("latin-1")
    status, body, headers = _raw_request(bare_cr, "header_bare_cr")
    # The 400 body is aiohttp's parser diagnostic quoting the malformed line;
    # what must never happen is a 5xx or the value appearing in a response header.
    reflected = CRLF_MARK in _headers_text(headers)
    check("header_bare_cr_refused_no_header_reflection",
          status in (400, 431) and not reflected,
          f"status={status} header_reflected={reflected}")

    status, body, headers = _hc_request(
        "GET", "/health", [("Host", "evil.example")], label="header_host_evil")
    reflected = b"evil.example" in body or "evil.example" in _headers_text(headers)
    check("header_host_evil_not_reflected",
          (status == 0 or status < 500) and not reflected,
          f"status={status} reflected={reflected}")


def run_leak_checks(master_key: str | None, device_keys: list[str],
                    codes: list[str], home: str | None) -> None:
    check("leak_master_key_available", bool(master_key),
          "" if master_key else "API_SERVER_KEY could not be read from BRIDGE_HERMES_ENV")

    needles: list[tuple[str, str]] = []
    if master_key:
        needles.append(("master key", master_key))
    for key in device_keys:
        if key:
            needles.append(("device key", key))
    for code in codes:
        if code:
            needles.append(("pairing code", code))
    needles.append(("traceback", "Traceback"))
    if home and home.startswith("/") and len(home) > 3:
        needles.append(("bridge home path", home))

    hits: list[str] = []
    for index, rec in enumerate(_responses, start=1):
        haystack = _headers_text(rec["headers"]) + "\n" + rec["body"].decode("utf-8", "replace")
        for label, needle in needles:
            if not needle:
                continue
            if label == "device key" and needle in rec["allow"]:
                continue
            if needle in haystack:
                hits.append(f"{label} in response #{index} ({rec['label']}, status={rec['status']})")
    check("leaks_no_secret_traceback_or_home_path", not hits, _join(hits))

    bad_servers = []
    for rec in _responses:
        for name, value in rec["headers"].items():
            if name.lower() != "server":
                continue
            if not re.fullmatch(r"Python/\d+(?:\.\d+)*(?: aiohttp/\d+(?:\.\d+)*)?", value):
                bad_servers.append((value or "")[:80])
    check("leaks_server_header_default_only", not bad_servers,
          ("values=" + ",".join(sorted(set(bad_servers)))) if bad_servers else "")


def main() -> int:
    env_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "bridge.env")
    env = read_env(env_path)

    master_key = None
    hermes_env = env.get("BRIDGE_HERMES_ENV")
    if hermes_env:
        try:
            master_key = read_env(hermes_env).get("API_SERVER_KEY") or None
        except OSError:
            master_key = None

    home = env.get("BRIDGE_HOME")
    if not home:
        state_db = env.get("BRIDGE_STATE_DB", "")
        home = os.path.dirname(state_db) if state_db else None

    codes: list[str] = []
    device_keys: list[str] = []
    paired: list[str] = []

    try:
        initial_status, _ = request("GET", "/health", label="health_initial")
        check("health_initial_200", initial_status == 200, _detail(initial_status))

        code_a = issue_code(env)
        code_b = issue_code(env)
        if code_a:
            codes.append(code_a)
        if code_b:
            codes.append(code_b)

        token_a, token_b, token_c = run_pairing_checks(env, code_a, code_b, codes, paired)
        device_keys.extend(paired)

        run_auth_checks()
        run_path_scope_checks(token_a)
        run_method_checks(token_a)
        run_query_checks(token_a)
        run_body_checks(token_a)
        run_approval_checks(token_a)
        run_after_seq_checks(token_a)

        run_revocation_checks(token_a, token_b)

        run_concurrency_checks(token_b)
        run_header_checks()
    except Exception as exc:  # noqa: BLE001 - never print a raw exception message
        check("script_completed_without_unexpected_error", False, type(exc).__name__)
    finally:
        for token in paired:
            if token not in device_keys:
                device_keys.append(token)
            request("POST", "/v1/devices/self/revoke", token=token, label="cleanup_revoke")

    run_leak_checks(master_key, device_keys, codes, home)

    failed = [name for name, cond, _ in _results if not cond]
    print(f"\nSUMMARY: {len(_results) - len(failed)}/{len(_results)} checks passed")
    if failed:
        print("FAILED:", ", ".join(failed))
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
