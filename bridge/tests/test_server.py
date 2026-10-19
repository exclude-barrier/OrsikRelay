"""HTTP-layer tests for the bridge (aiohttp), against a stub upstream.

These lock the security invariants the docs assert: device-token auth on every
proxied/soft route, a closed proxy allowlist, strict approval validation with
zero upstream POSTs on invalid input, no following of upstream redirects,
query allowlisting, admin-only code issuance with rate limits, true store caps,
and the exact pairing/self-revoke/credential contract.
"""

import asyncio
import json
import unittest
from unittest import mock
import uuid

from aiohttp import web
from aiohttp import ClientTimeout
from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import core
from orsik_mobile_bridge import runhub
from orsik_mobile_bridge import server as bridge_server

FRAMES = (
    b"event: run.started\ndata: {\"run_id\": \"run_1\", \"seq\": 1}\n\n",
    b"event: approval.request\ndata: {\"run_id\": \"run_1\", \"seq\": 2, \"request_id\": \"ap1\", \"choices\": [\"once\", \"deny\"]}\n\n",
    b"event: done\ndata: {\"seq\": 3}\n\n",
)

ADMIN_TOKEN = "admin_test_token"

# Mutable stub counters/flags, reset per test.
UPSTREAM: dict = {}


def _upstream_app():
    async def health(_):
        UPSTREAM["health_gets"] = int(UPSTREAM.get("health_gets", 0)) + 1
        return web.json_response({"status": "ok", "platform": "hermes-agent", "version": "t"})

    async def sessions_get(request):
        UPSTREAM["sessions_gets"] = int(UPSTREAM.get("sessions_gets", 0)) + 1
        UPSTREAM["seen_auth"] = request.headers.get("Authorization")
        if UPSTREAM.get("redirect"):
            return web.Response(status=302, headers={"Location": "http://evil.example/steal"})
        return web.json_response({"data": [], "auth": request.headers.get("Authorization")})

    async def sessions_post(_):
        return web.json_response({"object": "hermes.session", "session": {"id": "s1"}}, status=201)

    async def messages(_):
        return web.json_response(UPSTREAM.get("messages_page", {"data": []}))

    async def chat(request):
        UPSTREAM["chat_posts"] = int(UPSTREAM.get("chat_posts", 0)) + 1
        resp = web.StreamResponse()
        resp.content_type = "text/event-stream"
        await resp.prepare(request)
        if UPSTREAM.get("empty_stream"):
            await resp.write_eof()
            return resp
        for frame in FRAMES:
            await resp.write(frame)
        await resp.write_eof()
        return resp

    async def run(_):
        if UPSTREAM.get("run_404"):
            return web.json_response({"error": {"code": "run_not_found"}}, status=404)
        return web.json_response({"status": "completed"})

    async def approval(request):
        UPSTREAM["approval_posts"] = int(UPSTREAM.get("approval_posts", 0)) + 1
        return web.json_response(
            {"object": "hermes.run.approval_response", "run_id": "run_1", "choice": "once", "resolved": 1}
        )

    async def run_stop(_):
        return web.json_response({"object": "hermes.run", "status": "stopping"})

    app = web.Application()
    app.router.add_get("/health", health)
    app.router.add_get("/api/sessions", sessions_get)
    app.router.add_post("/api/sessions", sessions_post)
    app.router.add_get("/api/sessions/{sid}/messages", messages)
    app.router.add_post("/api/sessions/{sid}/chat/stream", chat)
    app.router.add_get("/v1/runs/{rid}", run)
    app.router.add_post("/v1/runs/{rid}/approval", approval)
    app.router.add_post("/v1/runs/{rid}/stop", run_stop)
    return app


class BridgeHttpTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        UPSTREAM.clear()
        self.upstream = TestServer(_upstream_app())
        await self.upstream.start_server()
        upstream_url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(upstream=upstream_url, master_key="master_key_test")
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()
        self.state.store.close()

    async def _admin_code(self):
        # The operator issues codes locally (no HTTP admin surface).
        issued = self.state.codes.issue()
        self.assertIsNotNone(issued)
        return issued[0]

    async def _pair(self):
        resp = await self.client.post("/v1/devices/pair", json={"code": await self._admin_code()})
        self.assertEqual(resp.status, 201)
        return await resp.json()

    def _bearer(self, token):
        return {"Authorization": "Bearer " + token}

    # --- baseline behaviour -------------------------------------------------
    async def test_health_is_unauthenticated_proxy(self):
        resp = await self.client.get("/health")
        self.assertEqual(resp.status, 200)
        self.assertEqual((await resp.json())["status"], "ok")

    async def test_health_with_body_is_refused_and_not_proxied(self):
        before = int(UPSTREAM.get("health_gets", 0))
        resp = await self.client.get("/health", data=b"payload")
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "body_not_allowed")
        self.assertEqual(int(UPSTREAM.get("health_gets", 0)), before)

    async def test_history_page_drops_inline_image_data(self):
        image = "data:image/png;base64," + "Q" * (20 * 1024 * 1024)
        UPSTREAM["messages_page"] = {
            "object": "list", "session_id": "s1",
            "data": [
                {"id": 1, "role": "user", "content": [
                    {"type": "text", "text": "Was ist das?"},
                    {"type": "image_url", "image_url": {"url": image}},
                ]},
                {"id": 2, "role": "assistant", "content": "Ein Baum."},
            ],
            "pagination": {"limit": 200, "offset": 0, "order": "latest", "returned": 2},
        }
        try:
            token = (await self._pair())["device_key"]
            resp = await self.client.get(
                "/api/sessions/s1/messages", headers=self._bearer(token))
            self.assertEqual(resp.status, 200)
            body = await resp.read()
        finally:
            UPSTREAM.pop("messages_page", None)
        self.assertLess(len(body), 4096)
        page = json.loads(body)
        self.assertEqual(
            page["data"][0]["content"],
            [{"type": "text", "text": "Was ist das?"}, {"type": "image_url"}],
        )
        self.assertEqual(page["data"][1]["content"], "Ein Baum.")
        self.assertEqual(page["pagination"]["returned"], 2)

    async def test_capabilities_requires_device_token(self):
        self.assertEqual((await self.client.get("/v1/capabilities")).status, 401)
        cred = await self._pair()
        resp = await self.client.get("/v1/capabilities", headers=self._bearer(cred["device_key"]))
        self.assertEqual(resp.status, 200)
        caps = await resp.json()
        self.assertEqual(caps["object"], "hermes.api_server.capabilities")
        self.assertEqual(caps["runtime"]["mode"], "server_agent")
        # The official core supports session interactive approvals, so the
        # bridge advertises the exact Mobile-v1 contract with once/deny only.
        approvals = caps["features"]["session_chat_interactive_approvals"]
        self.assertEqual(
            approvals,
            {
                "enabled": True,
                "protocol_version": 1,
                "event": "approval.request",
                "choices": ["once", "deny"],
                "response_endpoint": "run_approval",
            },
        )
        self.assertTrue(caps["features"]["run_approval_response"])

    async def test_pair_contract_exact(self):
        cred = await self._pair()
        self.assertEqual(
            set(cred),
            {"object", "device_id", "device_key", "profile", "label", "permissions", "issued_at"},
        )
        uuid.UUID(cred["device_id"])
        self.assertTrue(cred["device_key"].startswith("hd_"))
        self.assertEqual(len(cred["device_key"]), 46)
        self.assertEqual(cred["permissions"], ["approvals", "chat", "runs"])

    async def test_pair_bad_code_is_closed_400(self):
        resp = await self.client.post("/v1/devices/pair", json={"code": "BAD-CODE"})
        self.assertEqual(resp.status, 400)
        self.assertEqual(set((await resp.json())["error"]), {"code"})

    async def test_proxy_requires_token(self):
        self.assertEqual((await self.client.get("/api/sessions")).status, 401)

    async def test_proxy_allowed_with_token(self):
        cred = await self._pair()
        resp = await self.client.get("/api/sessions", headers=self._bearer(cred["device_key"]))
        self.assertEqual(resp.status, 200)

    async def test_proxy_forwards_master_key_not_device_token(self):
        cred = await self._pair()
        resp = await self.client.get("/api/sessions", headers=self._bearer(cred["device_key"]))
        self.assertEqual(resp.status, 200)
        # Upstream sees the master key, never the device token.
        self.assertEqual(UPSTREAM.get("seen_auth"), "Bearer master_key_test")
        # A reflected Authorization header is redacted before it reaches the device.
        self.assertEqual((await resp.json())["auth"], "Bearer [redacted]")

    async def test_proxy_body_never_contains_master_key(self):
        cred = await self._pair()
        resp = await self.client.get("/api/sessions", headers=self._bearer(cred["device_key"]))
        text = await resp.text()
        self.assertNotIn("master_key_test", text)
        self.assertIn("[redacted]", text)

    async def test_sse_stream_proxied(self):
        cred = await self._pair()
        resp = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(cred["device_key"]), json={"message": "hi"}
        )
        self.assertEqual(resp.status, 200)
        text = await resp.text()
        self.assertIn("run.started", text)
        self.assertIn("done", text)

    async def test_out_of_scope_refused(self):
        cred = await self._pair()
        for path in ("/api/jobs", "/api/cron", "/api/memory", "/api/skills", "/api/toolsets", "/v1/admin"):
            resp = await self.client.get(path, headers=self._bearer(cred["device_key"]))
            self.assertEqual(resp.status, 403, path)
            self.assertEqual(set((await resp.json())["error"]), {"code"})

    async def test_admin_route_not_exposed(self):
        # No HTTP admin surface; pairing codes are issued only by the local
        # operator CLI. The old route must be out of scope for any client.
        self.assertEqual((await self.client.post("/admin/pairing-code")).status, 401)
        cred = await self._pair()
        resp = await self.client.post("/admin/pairing-code", headers=self._bearer(cred["device_key"]))
        self.assertEqual(resp.status, 403)
        self.assertEqual((await resp.json())["error"]["code"], "endpoint_out_of_scope")

    async def test_self_revoke_then_denied(self):
        cred = await self._pair()
        token = cred["device_key"]
        resp = await self.client.post("/v1/devices/self/revoke", headers=self._bearer(token))
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(set(body), {"revoked", "device_id"})
        self.assertTrue(body["revoked"])
        uuid.UUID(body["device_id"])
        self.assertEqual((await self.client.get("/api/sessions", headers=self._bearer(token))).status, 401)

    async def test_capabilities_never_leak_secret(self):
        cred = await self._pair()
        text = await (await self.client.get("/v1/capabilities", headers=self._bearer(cred["device_key"]))).text()
        for needle in ("master_key_test", cred["device_key"], ADMIN_TOKEN):
            self.assertNotIn(needle, text)

    # --- Phase 2: approval authorization -----------------------------------
    async def _post_approval(self, token, body, run_id="run_1"):
        return await self.client.post(
            f"/v1/runs/{run_id}/approval", headers=self._bearer(token), json=body
        )

    async def test_approval_once_and_deny_allowed(self):
        cred = await self._pair()
        for choice in ("once", "deny"):
            resp = await self._post_approval(cred["device_key"], {"choice": choice, "request_id": "r1"})
            self.assertEqual(resp.status, 200, choice)
        self.assertEqual(UPSTREAM.get("approval_posts"), 2)

    async def test_mutations_are_audited_per_device_without_secrets(self):
        # Every forwarded approval/stop is attributable to one device;
        # neither the device token nor the master key ever reaches the log.
        cred = await self._pair()
        token = cred["device_key"]
        with self.assertLogs("orsik_mobile_bridge.audit", "INFO") as logs:
            await self._post_approval(token, {"choice": "once", "request_id": "r1"}, run_id="run_a")
            await self._post_approval(token, {"choice": "always", "request_id": "r1"}, run_id="run_a")
            await self.client.post("/v1/runs/run_a/stop", headers=self._bearer(token))
        lines = [record.getMessage() for record in logs.records]
        device = cred["device_id"]
        self.assertIn(f"audit action=approval device={device} run=run_a choice=once status=200", lines)
        self.assertIn(f"audit action=approval device={device} run=run_a choice=rejected status=400", lines)
        self.assertIn(f"audit action=stop device={device} run=run_a status=200", lines)
        for line in lines:
            self.assertNotIn(token, line)
            self.assertNotIn("master_key_test", line)

    async def test_pair_session_create_and_revoke_are_audited(self):
        with self.assertLogs("orsik_mobile_bridge.audit", "INFO") as logs:
            cred = await self._pair()
            token = cred["device_key"]
            created = await self.client.post(
                "/api/sessions", headers=self._bearer(token), json={"title": "x"})
            self.assertEqual(created.status, 201)
            revoked = await self.client.post(
                "/v1/devices/self/revoke", headers=self._bearer(token))
            self.assertEqual(revoked.status, 200)
            failed = await self.client.post(
                "/v1/devices/pair", json={"code": "BAD-CODE"})
            self.assertEqual(failed.status, 400)
        lines = [record.getMessage() for record in logs.records]
        device = cred["device_id"]
        self.assertIn(f"audit action=pair device={device} status=201", lines)
        self.assertIn(f"audit action=session_create device={device} status=201", lines)
        self.assertIn(f"audit action=revoke device={device} status=200", lines)
        self.assertIn("audit action=pair device=- status=400", lines)
        for line in lines:
            self.assertNotIn(token, line)
            self.assertNotIn("master_key_test", line)
            self.assertNotIn("BAD-CODE", line)

    async def test_approval_escalation_and_malformed_never_reach_upstream(self):
        cred = await self._pair()
        before = int(UPSTREAM.get("approval_posts", 0))
        bad_bodies = [
            {"choice": "session", "request_id": "r1"},
            {"choice": "always", "request_id": "r1"},
            {"choice": "allow", "request_id": "r1"},
            {"choice": "once"},  # missing request_id
            {"request_id": "r1"},  # missing choice
            {"choice": "once", "request_id": "r1", "scope": "all"},  # extra field
            {"choice": 1, "request_id": "r1"},  # wrong type
            {"choice": "once", "request_id": 7},  # wrong type
            {"choice": "once", "request_id": "  "},  # blank
        ]
        for body in bad_bodies:
            resp = await self._post_approval(cred["device_key"], body)
            self.assertEqual(resp.status, 400, body)
            self.assertEqual(set((await resp.json())["error"]), {"code"})
        self.assertEqual(int(UPSTREAM.get("approval_posts", 0)), before)  # zero upstream POSTs

    async def test_approval_invalid_json_never_reaches_upstream(self):
        cred = await self._pair()
        resp = await self.client.post(
            "/v1/runs/run_1/approval",
            headers={**self._bearer(cred["device_key"]), "Content-Type": "application/json"},
            data=b"{not json",
        )
        self.assertEqual(resp.status, 400)
        self.assertEqual(int(UPSTREAM.get("approval_posts", 0)), 0)

    async def test_approval_invalid_run_id_refused(self):
        cred = await self._pair()
        resp = await self.client.post(
            "/v1/runs/%2e%2e/approval", headers=self._bearer(cred["device_key"]),
            json={"choice": "once", "request_id": "r1"},
        )
        # An encoded dot-segment is refused (400 by the handler or 403 by the
        # router); either way nothing reaches upstream.
        self.assertIn(resp.status, (400, 403))
        self.assertEqual(int(UPSTREAM.get("approval_posts", 0)), 0)

    async def test_approval_requires_device_token(self):
        resp = await self.client.post("/v1/runs/run_1/approval", json={"choice": "once", "request_id": "r1"})
        self.assertEqual(resp.status, 401)

    # --- Phase 4: redirect, query, body ------------------------------------
    async def test_upstream_redirect_fails_closed_without_following(self):
        UPSTREAM["redirect"] = True
        cred = await self._pair()
        resp = await self.client.get("/api/sessions", headers=self._bearer(cred["device_key"]))
        self.assertEqual(resp.status, 502)
        self.assertEqual((await resp.json())["error"]["code"], "upstream_redirect")
        self.assertEqual(int(UPSTREAM.get("sessions_gets", 0)), 1)  # not followed

    async def test_query_allowlist(self):
        cred = await self._pair()
        ok = await self.client.get("/api/sessions?limit=5&offset=0&order=oldest", headers=self._bearer(cred["device_key"]))
        self.assertEqual(ok.status, 200)
        bad = await self.client.get("/api/sessions?evil=1", headers=self._bearer(cred["device_key"]))
        self.assertEqual(bad.status, 403)
        self.assertEqual((await bad.json())["error"]["code"], "query_not_allowed")
        run_q = await self.client.get("/v1/runs/run_1?limit=1", headers=self._bearer(cred["device_key"]))
        self.assertEqual(run_q.status, 403)

    # --- Phase 5: caps and rate limits -------------------------------------
    async def test_pair_rate_limited(self):
        self.state.pair_limiter = core.RateLimiter(2, 60.0)
        for _ in range(2):
            self.assertEqual((await self.client.post("/v1/devices/pair", json={"code": "BAD"})).status, 400)
        resp = await self.client.post("/v1/devices/pair", json={"code": "BAD"})
        self.assertEqual(resp.status, 429)

    async def test_token_cap_enforced(self):
        self.state.tokens = core.TokenStore(max_tokens=1)
        self.assertEqual((await self.client.post("/v1/devices/pair", json={"code": await self._admin_code()})).status, 201)
        resp = await self.client.post("/v1/devices/pair", json={"code": await self._admin_code()})
        self.assertEqual(resp.status, 503)
        self.assertEqual((await resp.json())["error"]["code"], "device_capacity_reached")

    async def test_code_cap_enforced(self):
        self.state.codes = core.CodeStore(ttl_seconds=300, max_codes=1)
        self.assertIsNotNone(self.state.codes.issue())
        self.assertIsNone(self.state.codes.issue())

    # --- review regressions ------------------------------------------------
    async def test_approval_trailing_slash_bypass_refused(self):
        # A non-canonical approval path must not fall through to the generic
        # proxy and forward an unvalidated body (e.g. "session"/"always").
        cred = await self._pair()
        for path in ("/v1/runs/run_1/approval/", "/v1/runs/run_1/approval//"):
            for choice in ("session", "always", "once"):
                resp = await self.client.post(
                    path, headers=self._bearer(cred["device_key"]),
                    json={"choice": choice, "request_id": "r1"},
                )
                self.assertIn(resp.status, (400, 403), (path, choice))
        self.assertEqual(int(UPSTREAM.get("approval_posts", 0)), 0)

    async def test_oversize_body_is_413_not_400(self):
        self.state.tokens = core.TokenStore()
        original = bridge_server._MAX_BODY_BYTES
        bridge_server._MAX_BODY_BYTES = 200
        client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await client.start_server()
        try:
            resp = await client.post("/v1/devices/pair", json={"code": await self._admin_code()})
            self.assertEqual(resp.status, 201)
            token = (await resp.json())["device_key"]
            resp = await client.post(
                "/v1/runs/run_1/approval",
                headers={"Authorization": "Bearer " + token},
                json={"choice": "once", "request_id": "x" * 400},
            )
            self.assertEqual(resp.status, 413)
        finally:
            bridge_server._MAX_BODY_BYTES = original
            await client.close()

    # --- Phase 2: SSE reconnect / after_seq replay --------------------------
    async def test_reattach_replays_after_seq_without_second_post(self):
        cred = await self._pair()
        resp = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(cred["device_key"]), json={"message": "hi"}
        )
        text = await resp.text()
        self.assertIn("run.started", text)
        self.assertIn("done", text)
        self.assertEqual(int(UPSTREAM.get("chat_posts", 0)), 1)

        # Reconnect on the same run_id: exclusive replay (seq > 1) of the retained tail.
        again = await self.client.get(
            "/api/sessions/s1/runs/run_1/stream?after_seq=1", headers=self._bearer(cred["device_key"])
        )
        self.assertEqual(again.status, 200)
        replay_text = await again.text()
        self.assertNotIn("run.started", replay_text)  # seq 1 is excluded
        self.assertIn("done", replay_text)            # seq 2 is replayed
        self.assertEqual(int(UPSTREAM.get("chat_posts", 0)), 1)  # no second POST

    async def test_chat_stream_empty_upstream_does_not_hang(self):
        # Regression (review M1): a 200 text/event-stream with no frames must not
        # block the client handler forever.
        UPSTREAM["empty_stream"] = True
        cred = await self._pair()
        async with asyncio.timeout(5):
            resp = await self.client.post(
                "/api/sessions/s1/chat/stream", headers=self._bearer(cred["device_key"]), json={}
            )
            await resp.text()
        self.assertEqual(resp.status, 200)

    async def test_approval_request_event_relayed_on_session_stream(self):
        # Phase 3: the session stream carries approval.request with the exact
        # request_id a client answers via POST /v1/runs/{run_id}/approval.
        cred = await self._pair()
        resp = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(cred["device_key"]), json={"message": "hi"}
        )
        text = await resp.text()
        self.assertIn("event: approval.request", text)
        self.assertIn('"request_id": "ap1"', text)

    async def test_reattach_requires_device_token(self):
        resp = await self.client.get("/api/sessions/s1/runs/run_1/stream?after_seq=0")
        self.assertEqual(resp.status, 401)

    async def test_reattach_bad_after_seq_is_400(self):
        cred = await self._pair()
        resp = await self.client.get(
            "/api/sessions/s1/runs/run_1/stream?after_seq=abc", headers=self._bearer(cred["device_key"])
        )
        self.assertEqual(resp.status, 400)

    async def test_reattach_unknown_run_is_404(self):
        UPSTREAM["run_404"] = True
        cred = await self._pair()
        resp = await self.client.get(
            "/api/sessions/s1/runs/run_unknown/stream?after_seq=0", headers=self._bearer(cred["device_key"])
        )
        self.assertEqual(resp.status, 404)

    async def test_reattach_known_but_unbuffered_run_is_indeterminate_409(self):
        # Upstream knows the run (stub returns 200) but the bridge has no buffer
        # (e.g. it restarted): fail closed, never fabricate events.
        cred = await self._pair()
        resp = await self.client.get(
            "/api/sessions/s1/runs/run_orphan/stream?after_seq=0", headers=self._bearer(cred["device_key"])
        )
        self.assertEqual(resp.status, 409)
        self.assertEqual((await resp.json())["error"]["code"], "resume_gap")

    # --- Phase 8: security regressions --------------------------------------
    async def test_chat_stream_forwards_master_key_not_device_token(self):
        seen = {}

        async def chat(request):
            seen["auth"] = request.headers.get("Authorization")
            resp = web.StreamResponse()
            resp.content_type = "text/event-stream"
            await resp.prepare(request)
            await resp.write(b'event: run.started\ndata: {"run_id": "run_1", "seq": 1}\n\n')
            await resp.write(b'event: done\ndata: {"seq": 2}\n\n')
            await resp.write_eof()
            return resp

        upstream = TestServer(web.Application())
        upstream.app.router.add_post("/api/sessions/{sid}/chat/stream", chat)
        await upstream.start_server()
        try:
            url = str(upstream.make_url("/")).rstrip("/")
            state = bridge_server.BridgeState(url, "master_key_test")
            client = TestClient(TestServer(bridge_server.create_app(state)))
            await client.start_server()
            try:
                code = state.codes.issue()[0]
                token = (await (await client.post("/v1/devices/pair", json={"code": code})).json())["device_key"]
                await client.post("/api/sessions/s1/chat/stream", headers=self._bearer(token), json={})
            finally:
                await client.close()
                state.store.close()
        finally:
            await upstream.close()
        self.assertEqual(seen["auth"], "Bearer master_key_test")

    async def test_store_failure_fails_closed_503(self):
        class _Boom:
            def lookup(self, token):
                raise bridge_server.store_mod.StoreUnavailable("boom")

            def mint(self, *a, **k):
                raise bridge_server.store_mod.StoreUnavailable("boom")

            def revoke(self, *a, **k):
                raise bridge_server.store_mod.StoreUnavailable("boom")

        self.state.tokens = _Boom()
        resp = await self.client.get("/api/sessions", headers=self._bearer("hd_x"))
        self.assertEqual(resp.status, 503)
        self.assertEqual((await resp.json())["error"]["code"], "store_unavailable")

    async def test_reattach_path_traversal_refused(self):
        cred = await self._pair()
        for path in (
            "/api/sessions/%2e%2e/runs/run_1/stream?after_seq=0",
            "/api/sessions/s1/runs/%2e%2e/stream?after_seq=0",
        ):
            resp = await self.client.get(path, headers=self._bearer(cred["device_key"]))
            self.assertIn(resp.status, (400, 403, 404), path)

    # --- Phase 4: durable device credentials --------------------------------
    async def test_device_survives_bridge_restart_and_revoke_is_durable(self):
        import os
        import tempfile

        from orsik_mobile_bridge import store as store_mod

        upstream_url = str(self.upstream.make_url("/")).rstrip("/")

        async def _state_client():
            state = bridge_server.BridgeState(
                upstream_url, "master_key_test", store=store_mod.BridgeStore(db_path)
            )
            client = TestClient(TestServer(bridge_server.create_app(state)))
            await client.start_server()
            return state, client

        with tempfile.TemporaryDirectory() as d:
            db_path = os.path.join(d, "state.db")
            state1, c1 = await _state_client()
            code = state1.codes.issue()[0]
            token = (await (await c1.post("/v1/devices/pair", json={"code": code})).json())["device_key"]
            await c1.close()
            state1.store.close()

            # Restart: the same on-disk store must still authenticate the device.
            state2, c2 = await _state_client()
            resp = await c2.get("/api/sessions", headers=self._bearer(token))
            self.assertEqual(resp.status, 200)
            self.assertEqual((await c2.post("/v1/devices/self/revoke", headers=self._bearer(token))).status, 200)
            await c2.close()
            state2.store.close()

            # Restart again: the revoke persists.
            state3, c3 = await _state_client()
            resp = await c3.get("/api/sessions", headers=self._bearer(token))
            self.assertEqual(resp.status, 401)
            await c3.close()
            state3.store.close()


class ReconnectStreamTest(unittest.IsolatedAsyncioTestCase):
    """A real mid-stream disconnect: the run must continue server-side and be
    re-attachable on the same run_id with no second chat POST."""

    async def asyncSetUp(self):
        self.release = asyncio.Event()
        self.posts = 0
        app = web.Application()

        async def chat(request):
            self.posts += 1
            resp = web.StreamResponse()
            resp.content_type = "text/event-stream"
            await resp.prepare(request)
            await resp.write(b'event: run.started\ndata: {"run_id": "run_z", "seq": 1}\n\n')
            await self.release.wait()
            await resp.write(b'event: assistant.delta\ndata: {"run_id": "run_z", "seq": 2}\n\n')
            await resp.write(b'event: done\ndata: {"run_id": "run_z", "seq": 3}\n\n')
            await resp.write_eof()
            return resp

        async def run_status(_r):
            return web.json_response({"status": "completed"})

        app.router.add_get("/v1/runs/{rid}", run_status)
        app.router.add_post("/api/sessions/{sid}/chat/stream", chat)
        self.upstream = TestServer(app)
        await self.upstream.start_server()
        upstream_url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(upstream_url, "master_key_test")
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        code = self.state.codes.issue()[0]
        self.token = (await (await self.client.post("/v1/devices/pair", json={"code": code})).json())["device_key"]

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()
        self.state.store.close()

    def _bearer(self):
        return {"Authorization": "Bearer " + self.token}

    async def test_disconnect_then_reattach_same_run(self):
        post = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
        )
        # Read only the first frame, then drop the downstream connection.
        buf = b""
        while b"\n\n" not in buf:
            buf += await post.content.read(64)
        self.assertIn(b"run.started", buf)
        post.close()

        # The upstream keeps producing; the bridge must buffer it with no client.
        self.release.set()
        await asyncio.sleep(0.2)

        again = await self.client.get(
            "/api/sessions/s1/runs/run_z/stream?after_seq=1", headers=self._bearer()
        )
        self.assertEqual(again.status, 200)
        body = await again.text()
        self.assertNotIn("run.started", body)      # seq 1 excluded
        self.assertIn("assistant.delta", body)     # seq 2 replayed
        self.assertIn("done", body)                # seq 3 replayed
        self.assertEqual(self.posts, 1)            # never re-POSTed


    async def test_revoked_device_loses_an_open_stream(self):
        # Revoking (here directly in the store, like `admin revoke-device` in
        # another process) must end an already open stream, not only new ones.
        with mock.patch.object(bridge_server, "_REVOKE_CHECK_INTERVAL", 0.05), \
                mock.patch.object(bridge_server, "_RUN_STREAM_KEEPALIVE", 0.05):
            post = await self.client.post(
                "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
            )
            buf = b""
            while b"\n\n" not in buf:
                buf += await post.content.read(64)
            self.assertIn(b"run.started", buf)
            self.assertIsNotNone(self.state.tokens.revoke(self.token))
            rest = await asyncio.wait_for(post.content.read(), timeout=5)
            self.assertIn(b": bridge_revoked", rest)
            self.assertNotIn(b"assistant.delta", rest)
            self.release.set()

class IndeterminateRunTest(unittest.IsolatedAsyncioTestCase):
    """A re-attach with no bridge buffer must fail closed from the
    authoritative upstream status — never a false 404, never fabricated events."""

    async def asyncSetUp(self):
        self.upstream_status = 200
        self.upstream_headers = {}
        self.upstream_hits = 0
        self.upstream_delay = 0.0
        app = web.Application()

        async def run(_):
            self.upstream_hits += 1
            if self.upstream_delay:
                await asyncio.sleep(self.upstream_delay)
            return web.Response(status=self.upstream_status, headers=self.upstream_headers)

        app.router.add_get("/v1/runs/{rid}", run)
        self.upstream = TestServer(app)
        await self.upstream.start_server()
        url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(url, "master_key_test")
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        code = self.state.codes.issue()[0]
        self.token = (await (await self.client.post("/v1/devices/pair", json={"code": code})).json())["device_key"]

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()
        self.state.store.close()

    def _bearer(self):
        return {"Authorization": "Bearer " + self.token}

    async def _reattach(self):
        return await self.client.get(
            "/api/sessions/s1/runs/run_x/stream?after_seq=0", headers=self._bearer()
        )

    async def test_upstream_200_is_resume_gap_409(self):
        self.upstream_status = 200
        resp = await self._reattach()
        self.assertEqual(resp.status, 409)
        self.assertEqual((await resp.json())["error"]["code"], "resume_gap")

    async def test_confirmed_404_is_run_not_found(self):
        self.upstream_status = 404
        self.upstream_hits = 0
        resp = await self._reattach()
        self.assertEqual(resp.status, 404)
        self.assertEqual((await resp.json())["error"]["code"], "run_not_found")
        self.assertEqual(self.upstream_hits, 1)  # a *confirmed* upstream 404

    async def test_upstream_401_403_5xx_never_false_404(self):
        for status in (201, 204, 401, 403, 418, 429, 500, 503):
            self.upstream_status = status
            self.upstream_hits = 0
            resp = await self._reattach()
            self.assertEqual(resp.status, 502, status)
            self.assertEqual((await resp.json())["error"]["code"], "run_state_indeterminate", status)
            self.assertEqual(self.upstream_hits, 1, status)

    async def test_upstream_timeout_is_indeterminate_not_404(self):
        self.upstream_delay = 2.0
        original = bridge_server._INDETERMINATE_TIMEOUT
        bridge_server._INDETERMINATE_TIMEOUT = ClientTimeout(total=0.3, sock_connect=0.3)
        try:
            resp = await self._reattach()
        finally:
            bridge_server._INDETERMINATE_TIMEOUT = original
        self.assertEqual(resp.status, 502)
        self.assertEqual((await resp.json())["error"]["code"], "run_state_indeterminate")

    async def test_upstream_redirect_fails_closed_not_404(self):
        self.upstream_status = 302
        self.upstream_headers = {"Location": "http://evil.example/steal"}
        self.upstream_hits = 0
        resp = await self._reattach()
        self.assertEqual(resp.status, 502)
        self.assertEqual((await resp.json())["error"]["code"], "upstream_redirect")
        self.assertNotIn("Location", resp.headers)
        self.assertEqual(self.upstream_hits, 1)  # served once, never followed

    async def test_network_error_is_indeterminate_not_404(self):
        dead = bridge_server.BridgeState("http://127.0.0.1:1", "master_key_test")
        client = TestClient(TestServer(bridge_server.create_app(dead)))
        await client.start_server()
        try:
            code = dead.codes.issue()[0]
            token = (await (await client.post("/v1/devices/pair", json={"code": code})).json())["device_key"]
            resp = await client.get(
                "/api/sessions/s1/runs/run_x/stream?after_seq=0",
                headers={"Authorization": "Bearer " + token},
            )
            self.assertEqual(resp.status, 502)
            self.assertEqual((await resp.json())["error"]["code"], "run_state_indeterminate")
        finally:
            await client.close()
            dead.store.close()


class BridgeStreamLimitsHttpTest(unittest.IsolatedAsyncioTestCase):
    """HTTP failure paths for the memory/backpressure bounds.

    Each path asserts the exact status/code the bridge promises: a 503 + Retry
    with *no* upstream POST on admission control; a 502 when the upstream error
    body exceeds the read cap; and a 502 / trailing abort comment for an
    oversized SSE frame -- without ever fabricating a terminal ``done``."""

    async def asyncSetUp(self):
        UPSTREAM.clear()
        self.mode = "normal"
        self.error_status = 500
        self.error_body = b"{}"
        app = web.Application()

        async def chat(request):
            UPSTREAM["chat_posts"] = int(UPSTREAM.get("chat_posts", 0)) + 1
            if self.mode == "error":
                return web.Response(
                    status=self.error_status, body=self.error_body, content_type="application/json"
                )
            resp = web.StreamResponse()
            resp.content_type = "text/event-stream"
            await resp.prepare(request)
            if self.mode == "big_frame_only":
                await resp.write(b"data: " + b"x" * (2 * 1024 * 1024) + b"\n\n")
            elif self.mode == "frame_after_started":
                await resp.write(b'event: run.started\ndata: {"run_id": "run_big", "seq": 1}\n\n')
                await resp.write(b"data: " + b"x" * (2 * 1024 * 1024) + b"\n\n")
            else:
                for frame in FRAMES:
                    await resp.write(frame)
            await resp.write_eof()
            return resp

        app.router.add_post("/api/sessions/{sid}/chat/stream", chat)
        self.upstream = TestServer(app)
        await self.upstream.start_server()
        url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(url, "master_key_test")
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        code = self.state.codes.issue()[0]
        self.token = (await (await self.client.post("/v1/devices/pair", json={"code": code})).json())["device_key"]

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()
        self.state.store.close()

    def _bearer(self):
        return {"Authorization": "Bearer " + self.token}

    async def test_busy_run_refused_before_any_upstream_post(self):
        # Admission control: the active-run cap is reached, so the request is
        # refused with 503 + Retry-After and the upstream is never contacted.
        self.state.runhub = runhub.RunHub(max_active_runs=1)
        self.state.runhub.create_pending("s1")
        resp = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
        )
        self.assertEqual(resp.status, 503)
        self.assertEqual(resp.headers.get("Retry-After"), "5")
        self.assertEqual((await resp.json())["error"]["code"], "bridge_busy")
        self.assertEqual(int(UPSTREAM.get("chat_posts", 0)), 0)

    async def test_oversized_upstream_error_body_is_502(self):
        self.mode = "error"
        self.error_status = 500
        self.error_body = b"x" * 200
        original = bridge_server._MAX_UPSTREAM_ERROR_BODY_BYTES
        bridge_server._MAX_UPSTREAM_ERROR_BODY_BYTES = 32
        try:
            resp = await self.client.post(
                "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
            )
        finally:
            bridge_server._MAX_UPSTREAM_ERROR_BODY_BYTES = original
        self.assertEqual(resp.status, 502)
        self.assertEqual((await resp.json())["error"]["code"], "upstream_error_too_large")

    async def test_upstream_error_body_never_reflects_master_key(self):
        self.mode = "error"
        self.error_status = 500
        self.error_body = b'{"auth": "Bearer master_key_test"}'
        resp = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
        )
        self.assertEqual(resp.status, 500)
        text = await resp.text()
        self.assertNotIn("master_key_test", text)
        self.assertIn("[redacted]", text)

    async def test_oversized_first_frame_is_502_with_no_fabricated_done(self):
        self.mode = "big_frame_only"
        resp = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
        )
        self.assertEqual(resp.status, 502)
        body = (await resp.json())["error"]["code"]
        self.assertEqual(body, "upstream_frame_too_large")

    async def test_oversized_frame_after_start_relays_abort_without_done(self):
        self.mode = "frame_after_started"
        post = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
        )
        self.assertEqual(post.status, 200)
        text = await post.text()
        self.assertIn("run.started", text)
        self.assertIn(": bridge_upstream_frame_too_large", text)
        self.assertNotIn("event: done", text)
        self.assertEqual(int(UPSTREAM.get("chat_posts", 0)), 1)

        # A re-attach to the terminal buffer replays the retained frames and the
        # same abort comment -- still no invented ``done``.
        again = await self.client.get(
            "/api/sessions/s1/runs/run_big/stream?after_seq=0", headers=self._bearer()
        )
        self.assertEqual(again.status, 200)
        replay = await again.text()
        self.assertIn("run.started", replay)
        self.assertIn(": bridge_upstream_frame_too_large", replay)
        self.assertNotIn("event: done", replay)


class StoreExecutorHttpTest(unittest.IsolatedAsyncioTestCase):
    """A blocked store call must not stall the aiohttp event loop.

    The device store is synchronous (sqlite3 + a lock + a 5 s busy timeout), so
    running it inline in a handler freezes every stream and request for as long
    as a call blocks. The fixture mirrors BridgeHttpTest: stub upstream plus an
    in-memory store injected into BridgeState.
    """

    async def asyncSetUp(self):
        UPSTREAM.clear()
        self.upstream = TestServer(_upstream_app())
        await self.upstream.start_server()
        upstream_url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(upstream=upstream_url, master_key="master_key_test")
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()
        self.state.store.close()

    def _bearer(self, token):
        return {"Authorization": "Bearer " + token}

    async def test_blocked_store_lookup_does_not_stall_the_event_loop(self):
        import threading

        issued = self.state.codes.issue()
        self.assertIsNotNone(issued)
        pair = await self.client.post("/v1/devices/pair", json={"code": issued[0]})
        self.assertEqual(pair.status, 201)
        token = (await pair.json())["device_key"]

        original_lookup = self.state.tokens.lookup
        entered = threading.Event()
        release = threading.Event()

        def blocking_lookup(value):
            entered.set()
            release.wait(timeout=5)
            return original_lookup(value)

        self.state.tokens.lookup = blocking_lookup

        # An authenticated request held inside the (blocked) store call.
        slow = asyncio.create_task(
            self.client.get("/v1/capabilities", headers=self._bearer(token))
        )
        # Wait (off the loop) until the request really sits in the store call.
        self.assertTrue(await asyncio.to_thread(entered.wait, 2))
        # Meanwhile the unauthenticated liveness probe must still answer in < 1 s.
        health = await asyncio.wait_for(self.client.get("/health"), timeout=1)
        self.assertEqual(health.status, 200)
        self.assertFalse(slow.done())
        release.set()
        slow_resp = await asyncio.wait_for(slow, timeout=5)
        self.assertEqual(slow_resp.status, 200)


class EchoRedactionTest(unittest.IsolatedAsyncioTestCase):
    """The core echoes the full image body in the first run.started frame.

    ``ChatAttachmentRules`` accept raw images up to 5 MiB, so the echoed
    ``run.started`` legitimately exceeds the 1 MiB relay bound while every later
    frame stays small. The session chat pump decodes with a frame bound coupled to
    the request body, strips ``user_message`` from that one frame and re-applies
    the 1 MiB relay bound to what is actually relayed.
    """

    ECHO_IMAGE_BYTES = 7 * 1024 * 1024

    async def asyncSetUp(self):
        self.frames: list[bytes] = []
        self.run_events_frame = b""
        app = web.Application()

        async def chat(request):
            resp = web.StreamResponse()
            resp.content_type = "text/event-stream"
            await resp.prepare(request)
            for frame in self.frames:
                await resp.write(frame)
            await resp.write_eof()
            return resp

        async def run_events(request):
            resp = web.StreamResponse()
            resp.content_type = "text/event-stream"
            await resp.prepare(request)
            await resp.write(self.run_events_frame)
            await resp.write_eof()
            return resp

        app.router.add_post("/api/sessions/{sid}/chat/stream", chat)
        app.router.add_get("/v1/runs/{rid}/events", run_events)
        self.upstream = TestServer(app)
        await self.upstream.start_server()
        url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(url, "master_key_test")
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        code = self.state.codes.issue()[0]
        self.token = (
            await (await self.client.post("/v1/devices/pair", json={"code": code})).json()
        )["device_key"]

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()
        self.state.store.close()

    def _bearer(self):
        return {"Authorization": "Bearer " + self.token}

    async def _chat(self):
        return await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
        )

    @staticmethod
    def _blocks(body: bytes) -> list[bytes]:
        return [block for block in body.split(b"\n\n") if block.strip()]

    @staticmethod
    def _data(block: bytes) -> dict:
        for line in block.split(b"\n"):
            if line.startswith(b"data:"):
                return json.loads(line[len(b"data:"):].decode())
        raise AssertionError("frame has no data line")

    async def test_image_echo_is_redacted_and_replays_redacted(self):
        image = "data:image/png;base64," + "A" * self.ECHO_IMAGE_BYTES
        started = {
            "session_id": "s1", "run_id": "run_img", "seq": 1,
            "user_message": {
                "role": "user",
                "content": [
                    {"type": "text", "text": "was ist das?"},
                    {"type": "image_url", "image_url": {"url": image}},
                ],
            },
        }
        self.frames = [
            b"id: 7\nevent: run.started\ndata: " + json.dumps(started).encode() + b"\n\n",
            b'event: message.started\ndata: {"session_id": "s1", "run_id": "run_img", "seq": 2}\n\n',
            b'event: assistant.completed\ndata: {"session_id": "s1", "run_id": "run_img", "seq": 3}\n\n',
            b'event: run.completed\ndata: {"session_id": "s1", "run_id": "run_img", "seq": 4}\n\n',
            b'event: done\ndata: {"session_id": "s1", "run_id": "run_img", "seq": 5}\n\n',
        ]
        resp = await self._chat()
        self.assertEqual(resp.status, 200)
        body = await resp.read()
        events = [line for line in body.split(b"\n") if line.startswith(b"event:")]
        self.assertEqual(
            events,
            [b"event: run.started", b"event: message.started", b"event: assistant.completed",
             b"event: run.completed", b"event: done"],
        )
        self.assertNotIn(b"base64", body)  # the image echo never reaches the client
        first = self._blocks(body)[0]
        # id/event lines and their order survive the reserialisation.
        self.assertTrue(first.startswith(b"id: 7\nevent: run.started\ndata: "))
        payload = self._data(first)
        self.assertNotIn("user_message", payload)
        self.assertEqual(payload["seq"], 1)
        self.assertEqual(payload["run_id"], "run_img")

        # A re-attach replays the same redacted run.started from the buffer.
        again = await self.client.get(
            "/api/sessions/s1/runs/run_img/stream?after_seq=0", headers=self._bearer()
        )
        self.assertEqual(again.status, 200)
        replay = await again.read()
        self.assertNotIn(b"base64", replay)
        self.assertEqual(
            [line for line in replay.split(b"\n") if line.startswith(b"event:")], events
        )
        self.assertEqual(self._blocks(replay)[0], first)

    async def test_transcript_tool_output_and_reasoning_never_reach_the_phone(self):
        # The official core puts the whole turn transcript on run.completed;
        # alone it exceeds the 1 MiB relay bound and used to abort a finished turn.
        transcript = [{"role": "tool", "content": "secret-output " + "x" * (2 * 1024 * 1024)}]
        frames = [
            {"event": "run.started", "data": {"session_id": "s1", "run_id": "run_t", "seq": 1}},
            {"event": "message.started", "data": {"session_id": "s1", "run_id": "run_t", "seq": 2}},
            {"event": "tool.started", "data": {"session_id": "s1", "run_id": "run_t", "seq": 3,
                                               "tool_name": "terminal", "preview": "rm -rf /x",
                                               "args": {"cmd": "rm -rf /x"}}},
            {"event": "tool.progress", "data": {"session_id": "s1", "run_id": "run_t", "seq": 4,
                                                "tool_name": "_thinking", "delta": "secret-thought"}},
            {"event": "assistant.commentary", "data": {"session_id": "s1", "run_id": "run_t", "seq": 5,
                                                       "text": "secret-commentary"}},
            {"event": "assistant.completed", "data": {"session_id": "s1", "run_id": "run_t", "seq": 6,
                                                      "content": "Fertig", "runtime": {"m": 1}}},
            {"event": "run.completed", "data": {"session_id": "s1", "run_id": "run_t", "seq": 7,
                                                "messages": transcript, "usage": {"t": 1},
                                                "runtime": {}, "pending_steer": "weiter"}},
            {"event": "done", "data": {"session_id": "s1", "run_id": "run_t", "seq": 8}},
        ]
        self.frames = [
            b"event: " + f["event"].encode() + b"\ndata: " + json.dumps(f["data"]).encode() + b"\n\n"
            for f in frames
        ]
        resp = await self._chat()
        self.assertEqual(resp.status, 200)
        body = await resp.read()
        self.assertIn(b"event: done", body)
        for secret in (b"secret-output", b"rm -rf", b"secret-thought", b"secret-commentary"):
            self.assertNotIn(secret, body)
        blocks = {b.split(b"\n")[0]: self._data(b) for b in self._blocks(body)}
        self.assertEqual(blocks[b"event: tool.progress"]["tool_name"], "_thinking")
        self.assertEqual(blocks[b"event: tool.started"]["tool_name"], "terminal")
        self.assertEqual(blocks[b"event: assistant.completed"]["content"], "Fertig")
        self.assertEqual(blocks[b"event: run.completed"]["pending_steer"], "weiter")
        self.assertEqual([blocks[k]["seq"] for k in blocks], list(range(1, 9)))

    async def test_core_approval_request_is_narrowed_to_mobile_v1(self):
        # The shape the official core emits (measured live 2026-10-16).
        core_approval = {
            "event": "approval.request", "session_id": "s1", "run_id": "run_ap", "seq": 2,
            "ts": 1.5, "timestamp": 1.5, "message_id": "msg_1", "request_id": "req_1",
            "command": "rm -rf /tmp/marker", "description": "delete in root path",
            "pattern_key": "delete in root path", "pattern_keys": ["delete in root path"],
            "allow_session": True, "allow_permanent": True,
            "choices": ["once", "session", "always", "deny"],
        }
        self.frames = [
            b'event: run.started\ndata: {"session_id": "s1", "run_id": "run_ap", "seq": 1}\n\n',
            b"event: approval.request\ndata: " + json.dumps(core_approval).encode() + b"\n\n",
            b'event: done\ndata: {"session_id": "s1", "run_id": "run_ap", "seq": 3}\n\n',
        ]
        resp = await self._chat()
        self.assertEqual(resp.status, 200)
        body = await resp.read()
        approval = [b for b in self._blocks(body) if b.startswith(b"event: approval.request")]
        self.assertEqual(len(approval), 1)
        payload = self._data(approval[0])
        self.assertEqual(
            payload,
            {"session_id": "s1", "run_id": "run_ap", "seq": 2, "ts": 1.5, "request_id": "req_1",
             "description": "A dangerous action requires your approval.",
             "choices": ["once", "deny"]},
        )
        self.assertNotIn(b"rm -rf", body)
        # A re-attach replays the same narrowed frame from the buffer.
        again = await self.client.get(
            "/api/sessions/s1/runs/run_ap/stream?after_seq=1", headers=self._bearer()
        )
        self.assertEqual(self._data(self._blocks(await again.read())[0]), payload)

    async def test_approval_request_missing_v1_field_is_relayed_unchanged(self):
        frame = (b'event: approval.request\ndata: {"run_id": "run_m", "seq": 1,'
                 b' "request_id": "r", "choices": ["once", "session", "always", "deny"]}\n\n')
        self.frames = [frame]
        resp = await self._chat()
        self.assertEqual(await resp.read(), frame)

    async def test_oversized_non_echo_frame_still_aborts_without_done(self):
        self.frames = [
            b'event: run.started\ndata: {"session_id": "s1", "run_id": "run_big", "seq": 1}\n\n',
            b"event: assistant.delta\ndata: " + b"x" * (2 * 1024 * 1024) + b"\n\n",
        ]
        resp = await self._chat()
        self.assertEqual(resp.status, 200)
        body = await resp.read()
        self.assertIn(b"event: run.started", body)
        self.assertIn(b": bridge_upstream_frame_too_large", body)
        self.assertNotIn(b"event: done", body)

    async def test_run_started_without_echo_is_byte_identical(self):
        started = b'event: run.started\ndata: {"session_id": "s1", "run_id": "run_plain", "seq": 1}\n\n'
        done = b'event: done\ndata: {"session_id": "s1", "run_id": "run_plain", "seq": 2}\n\n'
        self.frames = [started, done]
        resp = await self._chat()
        self.assertEqual(resp.status, 200)
        body = await resp.read()
        self.assertEqual(body, started + done)

    async def test_frame_above_echo_bound_is_still_502(self):
        pad = "A" * (bridge_server._MAX_ECHO_FRAME_BYTES + 4096)
        self.frames = [
            (b'event: run.started\ndata: {"session_id": "s1", "run_id": "run_huge", "seq": 1,'
             b' "user_message": {"image_url": {"url": "' + pad.encode() + b'"}}}\n\n'),
        ]
        resp = await self._chat()
        self.assertEqual(resp.status, 502)
        self.assertEqual((await resp.json())["error"]["code"], "upstream_frame_too_large")

    async def test_raw_run_events_route_is_out_of_scope(self):
        # The app follows runs only through the filtered session stream; the raw
        # core relay would bypass echo/approval/field filtering and the revoke
        # check, so it is not offered.
        self.run_events_frame = b'event: run.started\ndata: {"run_id": "run_raw", "seq": 1}\n\n'
        resp = await self.client.get("/v1/runs/run_raw/events", headers=self._bearer())
        self.assertEqual(resp.status, 403)
        self.assertEqual((await resp.json())["error"]["code"], "endpoint_out_of_scope")


if __name__ == "__main__":
    unittest.main()
