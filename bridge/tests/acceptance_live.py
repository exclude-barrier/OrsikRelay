"""Live acceptance test for the Orsik Mobile Bridge PoC.

Requires a running bridge (loopback) in front of a live official Hermes API
server. Not collected by unittest discover (name does not start with test_).

Exit code 0 iff every check passes.

Usage:
    python tests/acceptance_live.py [bridge.env]
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

BRIDGE = os.environ.get("BRIDGE_URL", "http://127.0.0.1:9842")

_results: list[tuple[str, bool, str]] = []


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


def request(method, path, body=None, token=None, admin=None, accept=None):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    if admin:
        headers["X-Admin-Token"] = admin
    if accept:
        headers["Accept"] = accept
    req = urllib.request.Request(BRIDGE + path, data=data, headers=headers, method=method)
    try:
        resp = urllib.request.urlopen(req, timeout=120)
        return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return 0, f"<{type(exc).__name__}>"


def stream_chat(token: str, sid: str, message: str):
    """POST the chat stream and return (status, event_names, run_id)."""
    headers = {
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
    }
    data = json.dumps({"message": message}).encode("utf-8")
    req = urllib.request.Request(
        BRIDGE + f"/api/sessions/{sid}/chat/stream", data=data, headers=headers, method="POST"
    )
    events: list[str] = []
    run_id = None
    try:
        resp = urllib.request.urlopen(req, timeout=180)
    except urllib.error.HTTPError as exc:
        return exc.code, events, run_id
    for raw in resp:
        line = raw.decode("utf-8", "replace").rstrip("\n")
        if line.startswith("event:"):
            events.append(line[6:].strip())
        elif line.startswith("data:") and run_id is None:
            try:
                payload = json.loads(line[5:].strip())
            except Exception:  # noqa: BLE001
                payload = {}
            if isinstance(payload, dict) and payload.get("run_id"):
                run_id = payload["run_id"]
    return resp.status, events, run_id


def main() -> int:
    env_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "bridge.env"
    )
    env = read_env(env_path)

    status, body = request("GET", "/health")
    health = json.loads(body) if status == 200 else {}
    check("health_200_ok", status == 200 and health.get("status") == "ok")

    code = issue_code(env)
    check("operator_issues_code", bool(code) and len(code) == 24)

    status, body = request("POST", "/v1/devices/pair", body={"code": code})
    cred = json.loads(body) if status == 201 else {}
    check("pair_201", status == 201)
    check("pair_permissions", cred.get("permissions") == ["approvals", "chat", "runs"])
    check("pair_key_shape", str(cred.get("device_key", "")).startswith("hd_")
          and len(str(cred.get("device_key", ""))) == 46)
    try:
        uuid.UUID(str(cred.get("device_id")))
        check("pair_device_id_uuid", True)
    except Exception:  # noqa: BLE001
        check("pair_device_id_uuid", False, str(cred.get("device_id")))
    device_key = cred.get("device_key")
    assert device_key is not None

    status, _ = request("POST", "/v1/devices/pair", body={"code": code})
    check("pair_code_single_use", status == 400)

    status, body = request("GET", "/v1/capabilities", token=device_key)
    caps = json.loads(body) if status == 200 else {}
    check("capabilities_200", status == 200)
    check("caps_object", caps.get("object") == "hermes.api_server.capabilities")
    check("caps_runtime", caps.get("runtime", {}).get("mode") == "server_agent")
    check("caps_device_credentials", caps.get("features", {}).get("device_credentials", {}).get("enabled") is True)
    check(
        "caps_interactive_approvals_exact",
        caps.get("features", {}).get("session_chat_interactive_approvals") == {
            "enabled": True, "protocol_version": 1, "event": "approval.request",
            "choices": ["once", "deny"], "response_endpoint": "run_approval"},
    )

    # Hardening probes: escalation refused locally, once reaches the core,
    # unknown query keys refused.
    status, _ = request(
        "POST", "/v1/runs/run_p12_1_probe/approval",
        body={"choice": "always", "request_id": "probe"}, token=device_key,
    )
    check("approval_always_rejected_400", status == 400)
    status, _ = request(
        "POST", "/v1/runs/run_p12_1_probe/approval",
        body={"choice": "once", "request_id": "probe"}, token=device_key,
    )
    check("approval_once_reaches_core", status in (404, 409), str(status))
    status, _ = request("GET", "/api/sessions?evil=1", token=device_key)
    check("query_evil_rejected_403", status == 403)

    status, _ = request("GET", "/api/sessions", token=device_key)
    check("proxied_sessions_200", status == 200)

    status, body = request("POST", "/api/sessions", body={"title": f"p13-{uuid.uuid4().hex[:8]}"}, token=device_key)
    sid = json.loads(body).get("session", {}).get("id") if status == 201 else None
    check("create_session_201", status == 201 and bool(sid), str(status))
    assert sid is not None

    status, _ = request("GET", f"/api/sessions/{sid}/messages", token=device_key)
    check("session_messages_200", status == 200)

    for path in ("/api/jobs", "/api/cron", "/api/memory", "/api/skills", "/api/toolsets"):
        status, _ = request("GET", path, token=device_key)
        check("out_of_scope_403" + path, status == 403)

    status, _ = request("GET", "/api/sessions")
    check("missing_token_401", status == 401)

    status, events, run_id = stream_chat(device_key, sid, "Bitte kurz antworten.")
    check("chat_stream_200", status == 200)
    for name in ("run.started", "message.started", "assistant.completed", "done"):
        check("chat_event_" + name, name in events, str(events))
    # Terminal transport event: the run reaches a terminal state (completed or failed
    # without a configured provider); never a fabricated success.
    check("chat_terminal_event", any(n in events for n in ("run.completed", "run.failed", "error")), str(events))
    check("chat_stream_run_id", bool(run_id))

    if run_id:
        status, _ = request("GET", f"/v1/runs/{run_id}", token=device_key)
        check("run_status_200", status == 200)
        # Re-attach the session run on the same run_id with exclusive replay.
        status, stream_body = request(
            "GET", f"/api/sessions/{sid}/runs/{run_id}/stream?after_seq=1", token=device_key,
            accept="text/event-stream",
        )
        check("reattach_same_run_200", status == 200, str(status))
        check("reattach_replays_events", "data:" in stream_body or "event:" in stream_body)

    # An unknown run on reconnect is a clean 404 (no fabricated events).
    status, _ = request(
        "GET", f"/api/sessions/{sid}/runs/run_p13_unknown/stream?after_seq=0", token=device_key
    )
    check("reattach_unknown_run_404", status == 404, str(status))

    status, body = request("POST", "/v1/devices/self/revoke", token=device_key)
    check("self_revoke_200", status == 200 and json.loads(body).get("revoked") is True)
    status, _ = request("GET", "/api/sessions", token=device_key)
    check("revoked_token_401", status == 401)

    failed = [name for name, cond, _ in _results if not cond]
    print(f"\nSUMMARY: {len(_results) - len(failed)}/{len(_results)} checks passed")
    if failed:
        print("FAILED:", ", ".join(failed))
        return 1
    print("ALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
