"""Adversarial HTTP tests for the bridge's least-privilege routes.

These attack the four routes that accept a device credential and validate a
body locally:

    PATCH  /api/sessions/{id}              exactly {"archived": <bool>}
    DELETE /api/sessions/{id}              no body
    POST   /v1/runs/{id}/resolve-unknown   exactly {admission_id, execution_generation}
    GET    /v1/host/metrics                bridge-generated, no query, no body

The invariants under attack are the ones the README asserts: a valid device
token on every route, a closed error body, no upstream request on invalid
input, no query/body surface where none is advertised, no credential in the
audit log, and never a 5xx for adversarial-but-well-formed HTTP.

A test marked ``@unittest.expectedFailure`` documents a real bug that this
ticket did not fix in production code; the comment above it names the bug and
the report describes observed vs. expected behaviour.
"""

import asyncio
import contextlib
import json
import threading
import time
import unittest
from unittest import mock

from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import server as bridge_server

# Reuse the stub upstream of test_server.py. Under `unittest discover -s tests`
# it is importable as the top-level module; from the repository root it is a
# namespace-package module.
try:
    from test_server import UPSTREAM, _upstream_app
except ImportError:  # pragma: no cover - only the discovery layout differs
    from tests.test_server import UPSTREAM, _upstream_app

MASTER_KEY = "master_key_adversarial"
METRICS_SNAPSHOT = {
    "object": "orsik.host.metrics",
    "cpu": {"percent": 1.0, "cores": 2},
    "memory": None,
    "gpu": None,
    "services": [],
    "collected_at": 1.0,
}
# (label, method, path, valid JSON body or None for "no body")
ROUTES = (
    ("session_archive", "PATCH", "/api/sessions/s1", {"archived": True}),
    ("session_delete", "DELETE", "/api/sessions/s1", None),
    ("resolve_unknown", "POST", "/v1/runs/run_1/resolve-unknown",
     {"admission_id": "adm_1", "execution_generation": 1}),
    ("host_metrics", "GET", "/v1/host/metrics", None),
)
UPSTREAM_COUNTERS = (
    "sessions_gets", "session_patches", "session_deletes", "resolve_posts",
    "approval_posts", "run_gets", "health_gets", "chat_posts",
)


class AdversarialRoutesTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        UPSTREAM.clear()
        self.upstream = TestServer(_upstream_app())
        await self.upstream.start_server()
        upstream_url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(upstream=upstream_url, master_key=MASTER_KEY)
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()
        self.state.store.close()

    # --- helpers -----------------------------------------------------------
    async def _pair(self):
        code = self.state.codes.issue()[0]
        resp = await self.client.post("/v1/devices/pair", json={"code": code})
        self.assertEqual(resp.status, 201)
        return await resp.json()

    def _bearer(self, token):
        return {"Authorization": "Bearer " + token}

    def _counters(self):
        return {name: int(UPSTREAM.get(name, 0)) for name in UPSTREAM_COUNTERS}

    async def _send(self, method, path, token=None, body=None, headers=None):
        all_headers = dict(headers or {})
        if token is not None:
            all_headers.update(self._bearer(token))
        kwargs = {"json": body} if body is not None else {}
        return await self.client.request(method, path, headers=all_headers, **kwargs)

    async def _assert_closed(self, resp, status, code):
        self.assertEqual(resp.status, status, resp.request_info.url)
        body = await resp.json()
        self.assertEqual(set(body), {"error"}, body)
        self.assertEqual(set(body["error"]), {"code"}, body)
        self.assertEqual(body["error"]["code"], code, body)

    async def _assert_refused(self, resp, allowed=(400, 403)):
        self.assertIn(resp.status, allowed, resp.request_info.url)
        self.assertLess(resp.status, 500)
        body = await resp.json()
        self.assertEqual(set(body), {"error"})
        self.assertEqual(set(body["error"]), {"code"})

    async def _raw(self, method, path, token=None, body=b"", content_type=None):
        """Send a raw request so paths/headers aiohttp's client normalises survive."""
        lines = ["Host: bridge", "Connection: close"]
        if token is not None:
            lines.append("Authorization: Bearer " + token)
        if content_type:
            lines.append("Content-Type: " + content_type)
        if body:
            lines.append("Content-Length: " + str(len(body)))
        request = (f"{method} {path} HTTP/1.1\r\n" + "\r\n".join(lines) + "\r\n\r\n").encode()
        reader, writer = await asyncio.open_connection(
            self.client.server.host, self.client.server.port)
        try:
            writer.write(request + body)
            await writer.drain()
            return await asyncio.wait_for(reader.read(), 10)
        finally:
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()

    async def _raw_json(self, method, path, token=None, body=b"", content_type=None):
        raw = await self._raw(method, path, token, body, content_type)
        head, _, payload = raw.partition(b"\r\n\r\n")
        status = int(head.split(b" ")[1])
        chunked = b"transfer-encoding: chunked" in head.lower()
        if chunked:
            size_line, _, rest = payload.partition(b"\r\n")
            payload = rest[: int(size_line, 16)]
        return status, payload

    # --- device-token attacks ---------------------------------------------
    async def test_all_four_routes_refuse_missing_or_unknown_tokens(self):
        before = self._counters()
        unknown = "hd_" + "A" * 43
        malformed_headers = (
            {},
            {"Authorization": ""},
            {"Authorization": "Bearer"},
            {"Authorization": "Bearer    "},
            {"Authorization": unknown},              # no bearer scheme
            {"Authorization": "Basic " + unknown},
            {"X-Device-Token": unknown},             # not an auth header
        )
        for label, method, path, body in ROUTES:
            for headers in malformed_headers:
                resp = await self._send(method, path, None, body, headers)
                self.assertEqual(resp.status, 401, (label, headers))
                self.assertEqual((await resp.json())["error"]["code"], "invalid_credentials")
            resp = await self._send(method, path, unknown, body)
            self.assertEqual(resp.status, 401, label)
        self.assertEqual(self._counters(), before)

    async def test_all_four_routes_refuse_the_master_key_as_a_device_token(self):
        before = self._counters()
        for label, method, path, body in ROUTES:
            resp = await self._send(method, path, MASTER_KEY, body)
            self.assertEqual(resp.status, 401, label)
            text = await resp.text()
            self.assertNotIn(MASTER_KEY, text)
            self.assertNotIn("master_key", text)
        self.assertEqual(self._counters(), before)

    async def test_revoked_device_token_is_refused_on_all_four_routes(self):
        cred = await self._pair()
        token = cred["device_key"]
        revoked = await self.client.post(
            "/v1/devices/self/revoke", headers=self._bearer(token))
        self.assertEqual(revoked.status, 200)
        before = self._counters()
        for label, method, path, body in ROUTES:
            resp = await self._send(method, path, token, body)
            self.assertEqual(resp.status, 401, label)
        self.assertEqual(self._counters(), before)

    async def test_query_token_is_not_an_authentication_bypass(self):
        cred = await self._pair()
        token = cred["device_key"]
        # Without a bearer header, a token in the query is not authentication.
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            resp = await self.client.get("/v1/host/metrics?token=" + token)
            self.assertEqual(resp.status, 401)
            collect.assert_not_called()
        # With a valid bearer, the query is still refused.
        resp = await self.client.get(
            "/v1/host/metrics?token=" + token, headers=self._bearer(token))
        self.assertEqual(resp.status, 403)
        self.assertEqual((await resp.json())["error"]["code"], "query_not_allowed")

    # --- PATCH /api/sessions/{id} ------------------------------------------
    async def test_patch_accepts_only_the_archived_flag(self):
        cred = await self._pair()
        token = cred["device_key"]
        for flag in (True, False):
            resp = await self._send("PATCH", "/api/sessions/s1", token, {"archived": flag})
            self.assertEqual(resp.status, 200)
            self.assertEqual(json.loads(UPSTREAM["session_patch_body"]), {"archived": flag})

    async def test_patch_refuses_extra_fields_and_wrong_types(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        bad_bodies = [
            {"title": ""},
            {"title": "x" * 513},
            {"title": "x\u0085y"},
            {"pinned": True},
            {"hidden": True},
            {"unread": True},
            {"end_reason": "x"},
            {"archived": True, "title": "x"},
            {"archived": True, "archived_extra": True},
            {"archived": 0},
            {"archived": 1},
            {"archived": "true"},
            {"archived": None},
            {"archived": []},
            {"archived": {}},
        ]
        for body in bad_bodies:
            resp = await self._send("PATCH", "/api/sessions/s1", token, body)
            self.assertEqual(resp.status, 400, body)
            self.assertEqual((await resp.json())["error"]["code"] in {
                "invalid_fields", "invalid_archived", "invalid_title", "invalid_json"}, True, body)
        # JSON numbers that overflow to infinity, and JSON's bare NaN, never coerce.
        for raw in (b'{"archived":1e309}', b'{"archived":-1e309}', b'{"archived":NaN}',
                    b'{"archived":Infinity}'):
            resp = await self.client.patch(
                "/api/sessions/s1", headers=self._bearer(token), data=raw)
            self.assertEqual(resp.status, 400, raw)
        self.assertEqual(self._counters(), before)

    async def test_patch_refuses_unicode_lookalike_field_names(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        # U+0430 CYRILLIC SMALL LETTER A, U+200B ZERO WIDTH SPACE, case variant.
        raw_bodies = (
            '{"archived":true,"\u0430rchived":true}',
            '{"archived\u200b":true}',
            '{"Archived":true}',
            '{"ARCHIVED":true}',
            '{" archived":true}',
            '{"archived ":true}',
        )
        for raw in raw_bodies:
            resp = await self.client.patch(
                "/api/sessions/s1", headers=self._bearer(token), data=raw.encode())
            self.assertEqual(resp.status, 400, raw)
        self.assertEqual(self._counters(), before)

    async def test_patch_duplicate_keys_collapse_and_cannot_smuggle_a_field(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        # An extra field hidden between duplicate keys is still an extra field.
        resp = await self.client.patch(
            "/api/sessions/s1", headers=self._bearer(token),
            data=b'{"archived": true, "title": "owned", "archived": true}')
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "invalid_fields")
        self.assertEqual(self._counters(), before)
        # Duplicates of the one allowed key collapse to a canonical body.
        resp = await self.client.patch(
            "/api/sessions/s1", headers=self._bearer(token),
            data=b'{"archived": true, "archived": false}')
        self.assertEqual(resp.status, 200)
        forwarded = json.loads(UPSTREAM["session_patch_body"])
        self.assertEqual(set(forwarded), {"archived"})
        self.assertIsInstance(forwarded["archived"], bool)

    async def test_patch_non_utf8_body_is_a_closed_400(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        for raw in (b'\xff{"archived":true}', b'{"archived":true}\xff',
                    b'{"archived":true,"x":"\x80"}'):
            resp = await self.client.patch(
                "/api/sessions/s1", headers=self._bearer(token), data=raw)
            self.assertEqual(resp.status, 400, raw)
            self.assertEqual((await resp.json())["error"]["code"], "invalid_json")
        self.assertEqual(self._counters(), before)

    async def test_patch_utf8_bom_is_tolerated_and_canonicalised(self):
        cred = await self._pair()
        token = cred["device_key"]
        resp = await self.client.patch(
            "/api/sessions/s1", headers=self._bearer(token),
            data=b'\xef\xbb\xbf{"archived":true}')
        self.assertEqual(resp.status, 200)
        self.assertEqual(json.loads(UPSTREAM["session_patch_body"]), {"archived": True})

    async def test_patch_malformed_json_is_always_a_closed_400(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        huge_int = b'{"archived":' + b"9" * 5000 + b"}"
        raw_bodies = (
            b"", b" ", b"{", b'{"archived": tru', b'{not json',
            b'{"archived": true} trailing', b'{"archived": true}\x00',
            b"null", b"[]", b'"archived"', b"1", b"true", huge_int,
        )
        for raw in raw_bodies:
            resp = await self.client.patch(
                "/api/sessions/s1", headers=self._bearer(token), data=raw)
            self.assertEqual(resp.status, 400, raw[:40])
            self.assertEqual((await resp.json())["error"]["code"], "invalid_json", raw[:40])
        self.assertEqual(self._counters(), before)

    async def test_patch_content_type_does_not_bypass_validation(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        headers = {**self._bearer(token), "Content-Type": "text/plain"}
        resp = await self.client.patch(
            "/api/sessions/s1", headers=headers,
            data=b'{"archived": true, "title": "owned"}')
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "invalid_fields")
        resp = await self.client.patch(
            "/api/sessions/s1", headers=headers, data=b"archived=true")
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "invalid_json")
        self.assertEqual(self._counters(), before)
        # A valid JSON body is validated on its content, not its Content-Type.
        resp = await self.client.patch(
            "/api/sessions/s1", headers=headers, data=b'{"archived": false}')
        self.assertEqual(resp.status, 200)
        self.assertEqual(json.loads(UPSTREAM["session_patch_body"]), {"archived": False})

    async def test_patch_deeply_nested_json_is_a_closed_400(self):
        # Was a bug (fixed): a deeply nested PATCH body raises RecursionError (only ValueError
        # is caught) and answers 500 text/plain instead of a closed 400.
        cred = await self._pair()
        before = self._counters()
        resp = await self.client.patch(
            "/api/sessions/s1", headers=self._bearer(cred["device_key"]),
            data=b"[" * 100_000)
        await self._assert_closed(resp, 400, "invalid_json")
        self.assertEqual(self._counters(), before)

    # --- DELETE /api/sessions/{id} -----------------------------------------
    async def test_delete_refuses_every_non_empty_body(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        for raw in (b'{"x":1}', b"x", b"   ", b"null", b"\xef\xbb\xbf"):
            resp = await self.client.delete(
                "/api/sessions/s1", headers=self._bearer(token), data=raw)
            self.assertEqual(resp.status, 400, raw)
            self.assertEqual((await resp.json())["error"]["code"], "body_not_allowed")
        self.assertEqual(self._counters(), before)

    async def test_delete_without_a_body_is_relayed(self):
        cred = await self._pair()
        resp = await self.client.delete(
            "/api/sessions/s1", headers=self._bearer(cred["device_key"]))
        self.assertEqual(resp.status, 200)
        self.assertEqual(UPSTREAM["session_delete_body"], b"")
        resp = await self.client.delete(
            "/api/sessions/s1", headers={
                **self._bearer(cred["device_key"]), "Content-Length": "0"})
        self.assertEqual(resp.status, 200)

    # --- POST /v1/runs/{id}/resolve-unknown --------------------------------
    async def test_resolve_unknown_refuses_every_non_exact_body(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        raw_bodies = (
            b"{}",
            b'{"admission_id":"a"}',
            b'{"execution_generation":1}',
            b'{"admission_id":"a","execution_generation":1,"outcome":"completed"}',
            b'{"admission_id":"a","execution_generation":"1"}',
            b'{"admission_id":"a","execution_generation":1.0}',
            b'{"admission_id":"a","execution_generation":true}',
            b'{"admission_id":"a","execution_generation":null}',
            b'{"admission_id":"a","execution_generation":-1}',
            b'{"admission_id":"a","execution_generation":1e309}',
            b'{"admission_id":"a","execution_generation":NaN}',
            b'{"admission_id":"a","execution_generation":' + b"9" * 5000 + b"}",
            b'{"admission_id":"   ","execution_generation":1}',
            b'{"admission_id":1,"execution_generation":1}',
            b'{"admission_id":["a"],"execution_generation":1}',
            b'{"admission_id":"a\\nb","execution_generation":1}',
            b'{"admission_id":"' + b"a" * 257 + b'","execution_generation":1}',
            b'{"\\u0430dmission_id":"a","execution_generation":1}',
            b'{"admission_id\\u200b":"a","execution_generation":1}',
            b"null",
            b"[]",
            b"",
        )
        for raw in raw_bodies:
            resp = await self.client.post(
                "/v1/runs/run_1/resolve-unknown",
                headers=self._bearer(token), data=raw)
            self.assertEqual(resp.status, 400, raw[:60])
            self.assertEqual(
                (await resp.json())["error"]["code"] in {
                    "invalid_json", "invalid_fields", "invalid_admission_id",
                    "invalid_generation"}, True, raw[:60])
        self.assertEqual(self._counters(), before)

    async def test_resolve_unknown_duplicate_keys_collapse_to_the_two_fields(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        resp = await self.client.post(
            "/v1/runs/run_1/resolve-unknown", headers=self._bearer(token),
            data=b'{"admission_id":"a","execution_generation":1,'
                 b'"extra":"x","admission_id":"a"}')
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "invalid_fields")
        self.assertEqual(self._counters(), before)
        resp = await self.client.post(
            "/v1/runs/run_1/resolve-unknown", headers=self._bearer(token),
            data=b'{"admission_id":"a","execution_generation":1,"admission_id":"b"}')
        self.assertEqual(resp.status, 200)
        forwarded = json.loads(UPSTREAM["resolve_body"])
        self.assertEqual(set(forwarded), {"admission_id", "execution_generation"})
        self.assertIsInstance(forwarded["admission_id"], str)
        self.assertIs(type(forwarded["execution_generation"]), int)

    async def test_resolve_unknown_non_utf8_and_bom(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        resp = await self.client.post(
            "/v1/runs/run_1/resolve-unknown", headers=self._bearer(token),
            data=b'\xff{"admission_id":"a","execution_generation":1}')
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "invalid_json")
        self.assertEqual(self._counters(), before)
        resp = await self.client.post(
            "/v1/runs/run_1/resolve-unknown", headers=self._bearer(token),
            data=b'\xef\xbb\xbf{"admission_id":"a","execution_generation":0}')
        self.assertEqual(resp.status, 200)
        self.assertEqual(
            json.loads(UPSTREAM["resolve_body"]),
            {"admission_id": "a", "execution_generation": 0})

    async def test_resolve_unknown_deeply_nested_json_is_a_closed_400(self):
        # Was a bug (fixed): a deeply nested resolve-unknown body raises RecursionError (only
        # ValueError is caught) and answers 500 text/plain instead of a closed 400.
        cred = await self._pair()
        before = self._counters()
        resp = await self.client.post(
            "/v1/runs/run_1/resolve-unknown", headers=self._bearer(cred["device_key"]),
            data=b"[" * 100_000)
        await self._assert_closed(resp, 400, "invalid_json")
        self.assertEqual(self._counters(), before)

    # --- path, method and query surface ------------------------------------
    async def test_path_traversal_trailing_and_double_slashes_are_refused(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        cases = (
            ("PATCH", "/api/sessions/..", {"archived": True}),
            ("PATCH", "/api/sessions/%2e%2e", {"archived": True}),
            ("PATCH", "/api/sessions/a%2Fb", {"archived": True}),
            ("PATCH", "/api/sessions/s1%2F", {"archived": True}),
            ("PATCH", "/api/sessions/s1/", {"archived": True}),
            ("PATCH", "/api/sessions//s1", {"archived": True}),
            ("PATCH", "/api/sessions/s1%00", {"archived": True}),
            ("DELETE", "/api/sessions/%2e%2e", None),
            ("DELETE", "/api/sessions/a%2Fb", None),
            ("DELETE", "/api/sessions/s1/", None),
            ("POST", "/v1/runs/%2e%2e/resolve-unknown",
             {"admission_id": "a", "execution_generation": 1}),
            ("POST", "/v1/runs/a%2Fb/resolve-unknown",
             {"admission_id": "a", "execution_generation": 1}),
            ("POST", "/v1/runs/run_1/resolve-unknown/",
             {"admission_id": "a", "execution_generation": 1}),
            ("GET", "/v1/host/metrics/", None),
            ("GET", "/v1/host%2Fmetrics", None),
            ("GET", "/v1/host/metrics%2F", None),
        )
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            for method, path, body in cases:
                resp = await self._send(method, path, token, body)
                await self._assert_refused(resp)
            collect.assert_not_called()
        self.assertEqual(self._counters(), before)

    async def test_raw_leading_and_inner_double_slash_paths_are_refused(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        cases = (
            ("PATCH", "//api/sessions/s1", b'{"archived":true}', "application/json"),
            ("PATCH", "/api//sessions/s1", b'{"archived":true}', "application/json"),
            ("DELETE", "//api/sessions/s1", b"", None),
            ("POST", "//v1/runs/run_1/resolve-unknown",
             b'{"admission_id":"a","execution_generation":1}', "application/json"),
            ("POST", "/v1//runs/run_1/resolve-unknown",
             b'{"admission_id":"a","execution_generation":1}', "application/json"),
            ("GET", "//v1/host/metrics", b"", None),
            ("GET", "/v1//host/metrics", b"", None),
        )
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            for method, path, body, content_type in cases:
                status, payload = await self._raw_json(method, path, token, body, content_type)
                self.assertEqual(status, 403, (method, path, payload[:80]))
                self.assertEqual(json.loads(payload)["error"]["code"], "endpoint_out_of_scope")
            collect.assert_not_called()
        self.assertEqual(self._counters(), before)

    async def test_query_strings_are_refused_on_every_write_route(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        cases = (
            ("PATCH", "/api/sessions/s1?archived=true", {"archived": True}),
            ("PATCH", "/api/sessions/s1?limit=1", {"archived": True}),
            ("DELETE", "/api/sessions/s1?x=1", None),
            ("POST", "/v1/runs/run_1/resolve-unknown?after_seq=0",
             {"admission_id": "a", "execution_generation": 1}),
        )
        for method, path, body in cases:
            resp = await self._send(method, path, token, body)
            self.assertEqual(resp.status, 403, path)
            self.assertEqual((await resp.json())["error"]["code"], "query_not_allowed")
        self.assertEqual(self._counters(), before)

    async def test_head_options_and_put_never_reach_upstream(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            for method in ("HEAD", "OPTIONS", "PUT"):
                for path in ("/api/sessions/s1", "/v1/runs/run_1/resolve-unknown"):
                    resp = await self._send(method, path, token)
                    self.assertIn(resp.status, (403, 405), (method, path))
            # HEAD on host/metrics is aiohttp's implicit HEAD for the GET route
            # and is covered (authenticated, bodyless) separately; every other
            # extra method is refused before any collection.
            for method in ("OPTIONS", "PUT", "POST", "PATCH", "DELETE"):
                resp = await self._send(method, "/v1/host/metrics", token)
                self.assertIn(resp.status, (403, 405), method)
            collect.assert_not_called()
        self.assertEqual(self._counters(), before)

    async def test_chunked_bodies_are_reassembled_and_validated(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()

        async def chunks(*parts):
            for part in parts:
                yield part

        # A chunked PATCH is validated and canonicalised like a sized one.
        resp = await self.client.patch(
            "/api/sessions/s1", headers=self._bearer(token),
            data=chunks(b'{"archiv', b'ed":true}'))
        self.assertEqual(resp.status, 200)
        self.assertEqual(json.loads(UPSTREAM["session_patch_body"]), {"archived": True})
        # Extra fields in a chunked body are still refused.
        resp = await self.client.patch(
            "/api/sessions/s1", headers=self._bearer(token),
            data=chunks(b'{"archived":true,', b'"title":"x"}'))
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "invalid_fields")
        # DELETE with a chunked body is a body.
        resp = await self.client.delete(
            "/api/sessions/s1", headers=self._bearer(token),
            data=chunks(b'{"x":', b"1}"))
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "body_not_allowed")
        # A chunked resolve body is reassembled into exactly the two fields.
        resp = await self.client.post(
            "/v1/runs/run_1/resolve-unknown", headers=self._bearer(token),
            data=chunks(b'{"admission_id":"a",', b'"execution_generation":1}'))
        self.assertEqual(resp.status, 200)
        self.assertEqual(
            json.loads(UPSTREAM["resolve_body"]),
            {"admission_id": "a", "execution_generation": 1})
        # A chunked body on host metrics is refused before any collection.
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            resp = await self.client.get(
                "/v1/host/metrics", headers=self._bearer(token), data=chunks(b"x"))
            self.assertEqual(resp.status, 400)
            self.assertEqual((await resp.json())["error"]["code"], "body_not_allowed")
            collect.assert_not_called()

    async def test_oversized_bodies_on_these_routes_are_closed_413(self):
        # Was a bug (fixed): a body over the 10 MB bound raises HTTPRequestEntityTooLarge
        # outside the ValueError handling and aiohttp answers 413 text/plain,
        # not the closed {"error":{"code":"body_too_large"}} body that the
        # pair/approval handlers return.
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        payload = b"x" * (10_000_000 + 1)
        cases = (
            ("PATCH", "/api/sessions/s1"),
            ("DELETE", "/api/sessions/s1"),
            ("POST", "/v1/runs/run_1/resolve-unknown"),
        )
        for method, path in cases:
            resp = await self.client.request(
                method, path, headers=self._bearer(token), data=payload)
            self.assertEqual(resp.status, 413, (method, path))
            raw = await resp.read()
            parsed = None
            with contextlib.suppress(Exception):
                parsed = json.loads(raw)
            self.assertEqual(parsed, {"error": {"code": "body_too_large"}},
                             f"{method} {path}: {raw[:120]!r}")
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            resp = await self.client.get(
                "/v1/host/metrics", headers=self._bearer(token), data=payload)
            self.assertEqual(resp.status, 413)
            collect.assert_not_called()
            raw = await resp.read()
            parsed = None
            with contextlib.suppress(Exception):
                parsed = json.loads(raw)
            self.assertEqual(parsed, {"error": {"code": "body_too_large"}},
                             f"GET /v1/host/metrics: {raw[:120]!r}")
        self.assertEqual(self._counters(), before)

    # --- GET /v1/host/metrics ----------------------------------------------
    async def test_metrics_refuses_any_body_without_collecting(self):
        cred = await self._pair()
        token = cred["device_key"]
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            for raw in (b"x", b"{}", b"   ", b"\x00", b"[1,2]"):
                resp = await self.client.get(
                    "/v1/host/metrics", headers=self._bearer(token), data=raw)
                self.assertEqual(resp.status, 400, raw)
                self.assertEqual((await resp.json())["error"]["code"], "body_not_allowed")
            collect.assert_not_called()

    async def test_metrics_refuses_query_strings_without_collecting(self):
        cred = await self._pair()
        token = cred["device_key"]
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            for query in ("x=1", "token=" + token, "access_token=" + token,
                          "limit=5", "a=1&b=2"):
                resp = await self.client.get(
                    "/v1/host/metrics?" + query, headers=self._bearer(token))
                self.assertEqual(resp.status, 403, query)
                self.assertEqual((await resp.json())["error"]["code"], "query_not_allowed")
            collect.assert_not_called()

    async def test_metrics_head_is_authenticated_and_bodyless(self):
        cred = await self._pair()
        token = cred["device_key"]
        with mock.patch.object(bridge_server.hostmetrics, "collect",
                               return_value=METRICS_SNAPSHOT) as collect:
            self.assertEqual((await self.client.head("/v1/host/metrics")).status, 401)
            collect.assert_not_called()
            resp = await self.client.head(
                "/v1/host/metrics", headers=self._bearer(token))
            self.assertEqual(resp.status, 200)
            self.assertEqual(await resp.read(), b"")

    async def test_metrics_concurrent_requests_never_collect_in_parallel(self):
        cred = await self._pair()
        token = cred["device_key"]
        before = self._counters()
        active = 0
        peak = 0
        calls = 0
        guard = threading.Lock()

        def collect(_services):
            nonlocal active, peak, calls
            with guard:
                active += 1
                calls += 1
                peak = max(peak, active)
            time.sleep(0.05)
            with guard:
                active -= 1
            return dict(METRICS_SNAPSHOT)

        with mock.patch.object(bridge_server.hostmetrics, "collect", side_effect=collect):
            responses = await asyncio.gather(*(
                self.client.get("/v1/host/metrics", headers=self._bearer(token))
                for _ in range(6)))
        self.assertEqual([resp.status for resp in responses], [200] * 6)
        for resp in responses:
            self.assertEqual(await resp.json(), METRICS_SNAPSHOT)
        self.assertEqual(peak, 1)       # the lock serialises collection
        self.assertEqual(calls, 1)      # one fresh snapshot serves the burst
        self.assertEqual(self._counters(), before)  # metrics never proxies upstream

    # --- audit log ---------------------------------------------------------
    async def test_audit_lines_for_these_routes_never_contain_credentials(self):
        cred = await self._pair()
        token = cred["device_key"]
        device = cred["device_id"]
        with self.assertLogs("orsik_mobile_bridge.audit", "INFO") as logs:
            await self._send("PATCH", "/api/sessions/s1", token, {"archived": True})
            await self._send("PATCH", "/api/sessions/s1", token, {"title": "x"})
            await self._send("DELETE", "/api/sessions/s2", token)
            await self._send("POST", "/v1/runs/run_1/resolve-unknown", token,
                             {"admission_id": "a", "execution_generation": 1})
            with mock.patch.object(bridge_server.hostmetrics, "collect",
                                   return_value=METRICS_SNAPSHOT):
                await self._send("GET", "/v1/host/metrics", token)
            await self.client.patch(
                "/api/sessions/s1", headers=self._bearer(token), data=b"{not json")
        lines = [record.getMessage() for record in logs.records]
        self.assertIn(
            f"audit action=session_archive device={device} session=s1 status=200", lines)
        self.assertIn(
            f"audit action=session_delete device={device} session=s2 status=200", lines)
        self.assertIn(
            f"audit action=resolve_unknown device={device} run=run_1 status=200", lines)
        for line in lines:
            self.assertNotIn(token, line)
            self.assertNotIn(MASTER_KEY, line)
            self.assertNotIn("\n", line)

    async def test_audit_never_logs_the_device_token_used_as_a_session_or_run_id(self):
        # Was a bug (fixed): the audited path parameter is not redacted, so a device that uses
        # its own token as the session/run id gets the live credential written
        # to the audit log (`session=<token>` / `run=<token>`).
        cred = await self._pair()
        token = cred["device_key"]
        with self.assertLogs("orsik_mobile_bridge.audit", "INFO") as logs:
            patch = await self.client.patch(
                "/api/sessions/" + token, headers=self._bearer(token),
                json={"archived": True})
            resolve = await self.client.post(
                "/v1/runs/" + token + "/resolve-unknown", headers=self._bearer(token),
                json={"admission_id": "a", "execution_generation": 1})
        self.assertEqual(patch.status, 200)
        self.assertEqual(resolve.status, 200)
        lines = "\n".join(record.getMessage() for record in logs.records)
        self.assertNotIn(token, lines)


if __name__ == "__main__":
    unittest.main()
