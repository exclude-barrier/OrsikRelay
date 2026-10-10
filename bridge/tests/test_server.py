"""HTTP-layer tests for the bridge (aiohttp), against a stub upstream.

These lock the security invariants the docs assert: device-token auth on every
proxied/soft route, a closed proxy allowlist, strict approval validation with
zero upstream POSTs on invalid input, no following of upstream redirects,
query allowlisting, admin-only code issuance with rate limits, true store caps,
and the exact pairing/self-revoke/credential contract.
"""

import unittest
import uuid

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import core
from orsik_mobile_bridge import server as bridge_server

FRAMES = (
    b"event: run.started\ndata: {\"run_id\": \"run_1\", \"seq\": 1}\n\n",
    b"event: done\ndata: {\"seq\": 2}\n\n",
)

ADMIN_TOKEN = "admin_test_token"

# Mutable stub counters/flags, reset per test.
UPSTREAM: dict = {}


def _upstream_app():
    async def health(_):
        return web.json_response({"status": "ok", "platform": "hermes-agent", "version": "t"})

    async def sessions_get(request):
        UPSTREAM["sessions_gets"] = int(UPSTREAM.get("sessions_gets", 0)) + 1
        if UPSTREAM.get("redirect"):
            return web.Response(status=302, headers={"Location": "http://evil.example/steal"})
        return web.json_response({"data": [], "auth": request.headers.get("Authorization")})

    async def sessions_post(_):
        return web.json_response({"object": "hermes.session", "session": {"id": "s1"}}, status=201)

    async def messages(_):
        return web.json_response({"data": []})

    async def chat(request):
        resp = web.StreamResponse()
        resp.content_type = "text/event-stream"
        await resp.prepare(request)
        for frame in FRAMES:
            await resp.write(frame)
        await resp.write_eof()
        return resp

    async def run(_):
        return web.json_response({"status": "completed"})

    async def approval(request):
        UPSTREAM["approval_posts"] = int(UPSTREAM.get("approval_posts", 0)) + 1
        return web.json_response(
            {"object": "hermes.run.approval_response", "run_id": "run_1", "choice": "once", "resolved": 1}
        )

    app = web.Application()
    app.router.add_get("/health", health)
    app.router.add_get("/api/sessions", sessions_get)
    app.router.add_post("/api/sessions", sessions_post)
    app.router.add_get("/api/sessions/{sid}/messages", messages)
    app.router.add_post("/api/sessions/{sid}/chat/stream", chat)
    app.router.add_get("/v1/runs/{rid}", run)
    app.router.add_post("/v1/runs/{rid}/approval", approval)
    return app


class BridgeHttpTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        UPSTREAM.clear()
        self.upstream = TestServer(_upstream_app())
        await self.upstream.start_server()
        upstream_url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(
            upstream=upstream_url, master_key="master_key_test", admin_token=ADMIN_TOKEN
        )
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()

    async def _admin_code(self):
        resp = await self.client.post("/admin/pairing-code", headers={bridge_server.ADMIN_HEADER: ADMIN_TOKEN})
        self.assertEqual(resp.status, 200)
        return (await resp.json())["code"]

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

    async def test_capabilities_requires_device_token(self):
        self.assertEqual((await self.client.get("/v1/capabilities")).status, 401)
        cred = await self._pair()
        resp = await self.client.get("/v1/capabilities", headers=self._bearer(cred["device_key"]))
        self.assertEqual(resp.status, 200)
        caps = await resp.json()
        self.assertEqual(caps["object"], "hermes.api_server.capabilities")
        self.assertEqual(caps["runtime"]["mode"], "server_agent")
        self.assertNotIn("session_chat_interactive_approvals", caps["features"])

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
        self.assertEqual((await resp.json())["auth"], "Bearer master_key_test")

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

    async def test_admin_token_required(self):
        self.assertEqual((await self.client.post("/admin/pairing-code")).status, 403)
        resp = await self.client.post("/admin/pairing-code", headers={bridge_server.ADMIN_HEADER: "wrong"})
        self.assertEqual(resp.status, 403)

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

    async def test_admin_rate_limited(self):
        self.state.admin_limiter = core.RateLimiter(1, 60.0)
        self.assertEqual((await self._admin_code()) is not None, True)
        resp = await self.client.post("/admin/pairing-code", headers={bridge_server.ADMIN_HEADER: ADMIN_TOKEN})
        self.assertEqual(resp.status, 429)

    async def test_token_cap_enforced(self):
        self.state.tokens = core.TokenStore(max_tokens=1)
        self.assertEqual((await self.client.post("/v1/devices/pair", json={"code": await self._admin_code()})).status, 201)
        resp = await self.client.post("/v1/devices/pair", json={"code": await self._admin_code()})
        self.assertEqual(resp.status, 503)
        self.assertEqual((await resp.json())["error"]["code"], "device_capacity_reached")

    async def test_code_cap_enforced(self):
        self.state.codes = core.CodeStore(ttl_seconds=300, max_codes=1)
        self.assertEqual((await self.client.post("/admin/pairing-code", headers={bridge_server.ADMIN_HEADER: ADMIN_TOKEN})).status, 200)
        resp = await self.client.post("/admin/pairing-code", headers={bridge_server.ADMIN_HEADER: ADMIN_TOKEN})
        self.assertEqual(resp.status, 503)

    # --- P12.1 review regressions ------------------------------------------
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

    async def test_admin_unauthenticated_does_not_consume_limit(self):
        self.state.admin_limiter = core.RateLimiter(1, 60.0)
        for _ in range(3):
            resp = await self.client.post("/admin/pairing-code", headers={bridge_server.ADMIN_HEADER: "wrong"})
            self.assertEqual(resp.status, 403)
        ok = await self.client.post("/admin/pairing-code", headers={bridge_server.ADMIN_HEADER: ADMIN_TOKEN})
        self.assertEqual(ok.status, 200)  # the authenticated operator was not locked out

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


if __name__ == "__main__":
    unittest.main()
