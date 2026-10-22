"""Soak test for the live stand: parallel devices, many turns, disconnects.

Run on the bridge host with the bridge venv python, from bridge/tests:
    python soak_live.py <bridge.env> <devices> <turns_per_device>
Never prints keys or codes. Revokes every paired device at the end.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import acceptance_live as al  # noqa: E402

lock = threading.Lock()
stats = {"turns": 0, "completed": 0, "failed": 0, "5xx": 0, "reattach_ok": 0,
         "reattach_bad": 0, "cut_recovered": 0, "cut_lost": 0, "errors": []}


def bump(key, detail=None):
    with lock:
        stats[key] += 1
        if detail and len(stats["errors"]) < 40:
            stats["errors"].append(detail)


def rss_kb(unit: str) -> int:
    pid = subprocess.run(["systemctl", "--user", "show", "-p", "MainPID", "--value", unit],
                         capture_output=True, text=True).stdout.strip()
    try:
        with open(f"/proc/{pid}/status") as fh:
            for line in fh:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1])
    except OSError:
        pass
    return -1


def cut_stream(token: str, sid: str, message: str):
    """Start a turn, read until the run id is known, then drop the connection."""
    req = urllib.request.Request(
        al.BRIDGE + f"/api/sessions/{sid}/chat/stream",
        data=json.dumps({"message": message}).encode(),
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                 "Accept": "text/event-stream"}, method="POST")
    resp = urllib.request.urlopen(req, timeout=180)
    run_id = None
    for raw in resp:
        line = raw.decode("utf-8", "replace")
        if line.startswith("data:"):
            try:
                payload = json.loads(line[5:].strip())
            except Exception:  # noqa: BLE001
                continue
            if isinstance(payload, dict) and payload.get("run_id"):
                run_id = payload["run_id"]
                break
    resp.close()
    return run_id


def wait_terminal(token: str, run_id: str, timeout: float = 180) -> str:
    end = time.time() + timeout
    while time.time() < end:
        status, body = al.request("GET", f"/v1/runs/{run_id}", token=token)
        if status >= 500:
            bump("5xx", f"status {status}")
        if status == 200:
            try:
                st = json.loads(body).get("status")
            except Exception:  # noqa: BLE001
                st = None
            if st in ("completed", "failed", "cancelled", "interrupted"):
                return st
        time.sleep(2)
    return "timeout"


def worker(idx: int, token: str, turns: int):
    status, body = al.request("POST", "/api/sessions", body={"title": f"soak-{idx}-{int(time.time()*1000)}"}, token=token)
    if status != 200 and status != 201:
        bump("failed", f"dev{idx} create session {status}")
        return
    payload = json.loads(body)
    sid = payload.get("id") or payload.get("session_id") or (payload.get("session") or {}).get("id")
    for t in range(turns):
        msg = f"Antworte nur mit der Zahl {idx * 100 + t}."
        bump("turns")
        try:
            if t % 5 == 4:
                run_id = cut_stream(token, sid, msg)
                if not run_id:
                    bump("cut_lost", f"dev{idx} t{t} no run id")
                    continue
                st, rbody = al.request("GET", f"/api/sessions/{sid}/runs/{run_id}/stream?after_seq=0",
                                       token=token, accept="text/event-stream")
                if st >= 500:
                    bump("5xx", f"reattach {st}")
                final = wait_terminal(token, run_id)
                if final == "completed":
                    bump("cut_recovered")
                else:
                    bump("cut_lost", f"dev{idx} t{t} {final}")
                continue
            status, events, run_id = al.stream_chat(token, sid, msg)
            if status >= 500:
                bump("5xx", f"stream {status}")
            if "run.completed" in events and "done" in events:
                bump("completed")
            else:
                bump("failed", f"dev{idx} t{t} {status} {events[-4:]}")
            if run_id and t % 3 == 2:
                st, rbody = al.request("GET", f"/api/sessions/{sid}/runs/{run_id}/stream?after_seq=1",
                                       token=token, accept="text/event-stream")
                # A finished run may be replayed (200) or already expired (404/410).
                if st == 200 or st in (404, 410):
                    bump("reattach_ok")
                else:
                    bump("reattach_bad", f"reattach {st}")
        except Exception as exc:  # noqa: BLE001
            bump("failed", f"dev{idx} t{t} {type(exc).__name__}")


def main() -> int:
    env = al.read_env(sys.argv[1])
    devices, turns = int(sys.argv[2]), int(sys.argv[3])
    tokens = []
    for _ in range(devices):
        code = al.issue_code(env)
        status, body = al.request("POST", "/v1/devices/pair", body={"code": code})
        if status != 201:
            print("pairing failed", status)
            return 1
        tokens.append(json.loads(body)["device_key"])
    rss0 = rss_kb(os.environ.get("BRIDGE_UNIT", "or-live-bridge"))
    t0 = time.time()
    threads = [threading.Thread(target=worker, args=(i, tok, turns)) for i, tok in enumerate(tokens)]
    try:
        for th in threads:
            th.start()
        for th in threads:
            th.join()
    finally:
        for tok in tokens:
            al.request("POST", "/v1/devices/self/revoke", token=tok)
    rss1 = rss_kb(os.environ.get("BRIDGE_UNIT", "or-live-bridge"))
    health, _ = al.request("GET", "/health")
    out = dict(stats)
    out.update(seconds=round(time.time() - t0), bridge_rss_kb_before=rss0,
               bridge_rss_kb_after=rss1, health_after=health)
    print(json.dumps(out, indent=1, ensure_ascii=False))
    ok = (stats["failed"] == 0 and stats["5xx"] == 0 and stats["cut_lost"] == 0
          and stats["reattach_bad"] == 0 and health == 200)
    print("SOAK", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
