"""Live approval check: a dangerous command must stop at an approval request,
and "deny" must keep it from running. Never approves anything.

Run on the bridge host with the bridge venv python, from bridge/tests:
    python approval_live.py <bridge.env> <rounds>
A marker directory is created first; after the deny it must still exist,
which proves the command did not run.
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import acceptance_live as al  # noqa: E402


def one_round(token: str, n: int) -> list[tuple[str, bool, str]]:
    res: list[tuple[str, bool, str]] = []
    marker = f"/tmp/or-night-approval-{uuid.uuid4().hex[:12]}"
    os.makedirs(marker)
    with open(os.path.join(marker, "keep"), "w") as fh:
        fh.write("must survive a denied approval\n")
    st, body = al.request("POST", "/api/sessions", body={"title": f"approval-{n}-{uuid.uuid4().hex[:6]}"}, token=token)
    sid = json.loads(body).get("session", {}).get("id") if st == 201 else None
    res.append(("session_201", bool(sid), str(st)))
    if not sid:
        return res
    msg = (f"Fuehre im Terminal-Tool genau `rm -rf {marker}` aus und antworte danach nur mit: fertig")
    req = urllib.request.Request(
        al.BRIDGE + f"/api/sessions/{sid}/chat/stream",
        data=json.dumps({"message": msg}).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                 "Accept": "text/event-stream", "X-Hermes-Interactive-Approvals": "1"},
        method="POST")
    events: list[str] = []
    approval = None
    run_id = None
    deny_status = None
    event = None
    resp = urllib.request.urlopen(req, timeout=300)
    for raw in resp:
        line = raw.decode("utf-8", "replace").rstrip("\n")
        if line.startswith("event:"):
            event = line[6:].strip()
            events.append(event)
        elif line.startswith("data:"):
            try:
                payload = json.loads(line[5:].strip())
            except Exception:  # noqa: BLE001
                continue
            if isinstance(payload, dict):
                run_id = run_id or payload.get("run_id")
                if event == "approval.request" and approval is None:
                    approval = payload
                    # Exists right before the decision: the command waits.
                    res.append(("marker_intact_while_waiting", os.path.exists(os.path.join(marker, "keep")), ""))
                    deny_status, _ = al.request(
                        "POST", f"/v1/runs/{payload.get('run_id') or run_id}/approval",
                        body={"choice": "deny", "request_id": payload.get("request_id")}, token=token)
        if event in ("done",) and line.startswith("data:"):
            break
    resp.close()
    res.append(("approval_request_seen", approval is not None, str(events[-6:])))
    if approval:
        res.append(("approval_choices_once_deny", approval.get("choices") == ["once", "deny"], str(approval.get("choices"))))
        res.append(("approval_has_request_id", bool(approval.get("request_id")), ""))
        res.append(("deny_accepted", deny_status in (200, 202, 204), str(deny_status)))
    res.append(("run_terminal", any(e in events for e in ("run.completed", "run.failed", "run.cancelled")), str(events[-4:])))
    time.sleep(2)
    res.append(("marker_survives_deny", os.path.exists(os.path.join(marker, "keep")), marker))
    # Clean up our own marker only.
    try:
        os.remove(os.path.join(marker, "keep"))
        os.rmdir(marker)
    except OSError:
        pass
    return res


def main() -> int:
    env = al.read_env(sys.argv[1])
    rounds = int(sys.argv[2])
    st, body = al.request("POST", "/v1/devices/pair", body={"code": al.issue_code(env)})
    token = json.loads(body)["device_key"]
    failed = 0
    try:
        for n in range(rounds):
            for name, ok, detail in one_round(token, n):
                print("PASS" if ok else "FAIL", f"r{n}", name, detail)
                failed += 0 if ok else 1
    finally:
        al.request("POST", "/v1/devices/self/revoke", token=token)
    print("APPROVAL", "PASS" if failed == 0 else f"FAIL ({failed})")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
