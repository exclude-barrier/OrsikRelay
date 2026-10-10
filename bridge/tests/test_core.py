"""Unit tests for the dependency-free bridge core (stdlib unittest)."""

import unittest
import uuid

from orsik_mobile_bridge import core


class NormalizeCodeTest(unittest.TestCase):
    def test_accepts_clean_24_char_code(self):
        code = "ABCDEFGHJKMNPQRSTVWXYZ12"
        self.assertEqual(core.normalize_code(code), code)

    def test_normalizes_dashes_underscores_and_spaces(self):
        grouped = "ABCD-EFGH-JKMN-PQRS-TVWX-YZ12"
        self.assertEqual(core.normalize_code(grouped), "ABCDEFGHJKMNPQRSTVWXYZ12")
        self.assertEqual(core.normalize_code(" abcd efgh jkmn pqrs tvwx yz12 "), "ABCDEFGHJKMNPQRSTVWXYZ12")

    def test_rejects_wrong_length_or_alphabet(self):
        self.assertIsNone(core.normalize_code("short"))
        self.assertIsNone(core.normalize_code("ABCDEFGHJKMNPQRSTVWXYO12"))  # contains O
        self.assertIsNone(core.normalize_code("ABCDEFGHJKMNPQRSTVWXYZ10"))  # contains 0
        self.assertIsNone(core.normalize_code(None))
        self.assertIsNone(core.normalize_code(12345))


class CodeStoreTest(unittest.TestCase):
    def test_issue_and_single_use(self):
        store = core.CodeStore(ttl_seconds=300)
        issued = store.issue(now=1000.0)
        assert issued is not None
        code, ttl = issued
        self.assertEqual(len(code), core.CODE_LENGTH)
        self.assertEqual(ttl, 300)
        self.assertTrue(store.redeem(code, now=1001.0))
        self.assertFalse(store.redeem(code, now=1002.0))  # single use

    def test_expiry(self):
        store = core.CodeStore(ttl_seconds=10)
        issued = store.issue(now=1000.0)
        assert issued is not None
        code, _ = issued
        self.assertFalse(store.redeem(code, now=1011.0))

    def test_malformed_rejected(self):
        store = core.CodeStore()
        self.assertFalse(store.redeem("not-a-code"))

    def test_prunes_used_and_expired(self):
        store = core.CodeStore(ttl_seconds=10)
        issued = store.issue(now=1000.0)
        assert issued is not None
        code, _ = issued
        store.redeem(code, now=1001.0)
        store.issue(now=1002.0)  # triggers prune of the used entry
        self.assertEqual(len(store._entries), 1)


class TokenStoreTest(unittest.TestCase):
    def test_mint_lookup_revoke(self):
        store = core.TokenStore()
        minted = store.mint("label")
        assert minted is not None
        token, record = minted
        self.assertTrue(token.startswith("hd_"))
        self.assertEqual(len(token), 46)
        self.assertEqual(record.permissions, core.DEVICE_PERMISSIONS)
        # device_id must be a canonical UUID (the client rejects anything else).
        uuid.UUID(record.device_id)
        looked_up = store.lookup(token)
        assert looked_up is not None
        self.assertEqual(looked_up.device_id, record.device_id)
        store.revoke(token)
        self.assertIsNone(store.lookup(token))

    def test_unknown_token(self):
        self.assertIsNone(core.TokenStore().lookup("nope"))


class ProxyRouteTest(unittest.TestCase):
    def test_allowed_routes_mapped_identically(self):
        m = core.match_proxy_route("GET", "/api/sessions")
        assert m is not None
        self.assertEqual(m.upstream_path, "/api/sessions")
        m = core.match_proxy_route("POST", "/api/sessions/api_1/chat/stream")
        assert m is not None
        self.assertEqual(m.upstream_path, "/api/sessions/api_1/chat/stream")
        m = core.match_proxy_route("GET", "/v1/runs/run_9")
        assert m is not None
        self.assertEqual(m.upstream_path, "/v1/runs/run_9")

    def test_out_of_scope_paths_refused(self):
        for method, path in (
            ("GET", "/api/jobs"),
            ("GET", "/api/cron"),
            ("GET", "/api/memory"),
            ("GET", "/api/skills"),
            ("GET", "/api/toolsets"),
            ("GET", "/v1/admin"),
            ("POST", "/admin/pairing-code"),
            ("POST", "/v1/runs/run_9/delete"),
            ("GET", "/api/sessions/../secret"),
            ("GET", "/api/sessions/x/../messages"),
        ):
            self.assertIsNone(core.match_proxy_route(method, path), path)

    def test_segment_traversal_refused(self):
        # These DO match a route template and must be rejected by _safe_segment.
        self.assertIsNone(core.match_proxy_route("GET", "/v1/runs/.."))
        self.assertIsNone(core.match_proxy_route("GET", "/v1/runs/."))
        self.assertIsNone(core.match_proxy_route("GET", "/api/sessions/.."))

    def test_query_not_part_of_match(self):
        # Query strings are appended by the server, not part of route matching.
        self.assertIsNotNone(core.match_proxy_route("GET", "/api/sessions"))


class CapabilitiesTest(unittest.TestCase):
    def test_contract_envelope_exact(self):
        caps = core.build_capabilities()
        self.assertEqual(caps["object"], "hermes.api_server.capabilities")
        self.assertEqual(caps["platform"], "hermes-agent")
        self.assertEqual(caps["auth"], {"type": "bearer", "required": True})
        self.assertEqual(caps["runtime"]["mode"], "server_agent")
        self.assertEqual(caps["runtime"]["tool_execution"], "server")
        self.assertTrue(caps["features"]["session_resources"])
        self.assertTrue(caps["features"]["session_chat_streaming"])

    def test_device_credentials_contract_exact_four_fields(self):
        contract = core.build_capabilities()["features"]["device_credentials"]
        self.assertEqual(
            set(contract), {"enabled", "protocol_version", "pair_endpoint", "self_revoke_endpoint"}
        )
        self.assertTrue(contract["enabled"])
        self.assertEqual(contract["protocol_version"], 1)
        endpoints = core.build_capabilities()["endpoints"]
        self.assertEqual(endpoints["device_pair"], {"method": "POST", "path": "/v1/devices/pair"})
        self.assertEqual(
            endpoints["device_self_revoke"], {"method": "POST", "path": "/v1/devices/self/revoke"}
        )

    def test_interactive_approvals_omitted_fail_closed(self):
        features = core.build_capabilities()["features"]
        self.assertNotIn("session_chat_interactive_approvals", features)

    def test_core_endpoints_paths_exact(self):
        endpoints = core.build_capabilities()["endpoints"]
        self.assertEqual(endpoints["health"], {"method": "GET", "path": "/health"})
        self.assertEqual(endpoints["sessions"], {"method": "GET", "path": "/api/sessions"})
        self.assertEqual(
            endpoints["session_chat_stream"],
            {"method": "POST", "path": "/api/sessions/{session_id}/chat/stream"},
        )


class CredentialTest(unittest.TestCase):
    def test_exact_seven_fields_and_uuid(self):
        store = core.TokenStore()
        minted = store.mint("label")
        assert minted is not None
        token, record = minted
        cred = core.build_credential(record, token, issued_at=123.0)
        self.assertEqual(
            set(cred),
            {"object", "device_id", "device_key", "profile", "label", "permissions", "issued_at"},
        )
        self.assertEqual(cred["object"], "hermes.device.credential")
        uuid.UUID(cred["device_id"])
        self.assertTrue(cred["device_key"].startswith("hd_"))
        self.assertEqual(cred["permissions"], ["approvals", "chat", "runs"])
        self.assertEqual(cred["issued_at"], 123.0)


class ApprovalValidationTest(unittest.TestCase):
    def test_once_and_deny_allowed(self):
        self.assertEqual(core.validate_approval_body({"choice": "once", "request_id": "r1"}), (True, ""))
        self.assertEqual(core.validate_approval_body({"choice": "deny", "request_id": "r1"}), (True, ""))

    def test_escalating_choices_rejected(self):
        for choice in ("session", "always", "allow", "ALLOW", "", None, 1, ["once"]):
            ok, code = core.validate_approval_body({"choice": choice, "request_id": "r1"})
            self.assertFalse(ok, choice)
            self.assertEqual(code, "invalid_choice")

    def test_missing_fields_rejected(self):
        self.assertEqual(core.validate_approval_body({"choice": "once"})[1], "invalid_fields")
        self.assertEqual(core.validate_approval_body({"request_id": "r1"})[1], "invalid_fields")
        self.assertEqual(core.validate_approval_body({})[1], "invalid_fields")

    def test_extra_fields_rejected(self):
        ok, code = core.validate_approval_body({"choice": "once", "request_id": "r", "scope": "all"})
        self.assertFalse(ok)
        self.assertEqual(code, "invalid_fields")

    def test_request_id_rules(self):
        self.assertEqual(core.validate_approval_body({"choice": "once", "request_id": "  "})[1], "invalid_request_id")
        self.assertEqual(core.validate_approval_body({"choice": "once", "request_id": 7})[1], "invalid_request_id")
        self.assertEqual(
            core.validate_approval_body({"choice": "once", "request_id": "a" * 257})[1], "invalid_request_id"
        )
        self.assertEqual(
            core.validate_approval_body({"choice": "once", "request_id": "a\x00b"})[1], "invalid_request_id"
        )
        # exactly 256 chars is allowed
        self.assertTrue(core.validate_approval_body({"choice": "once", "request_id": "a" * 256})[0])

    def test_non_object_rejected(self):
        for body in (None, [], "once", 3):
            self.assertEqual(core.validate_approval_body(body), (False, "invalid_json"))


class RunIdTest(unittest.TestCase):
    def test_safe_run_ids(self):
        for value in ("run_abc123", "run_9c88525f29e4453ba10de886ad3da165", "a" * 256):
            self.assertTrue(core.is_safe_run_id(value), value)

    def test_unsafe_run_ids(self):
        for value in ("", ".", "..", "a/b", "a" * 257, "run\x01", None, 5):
            self.assertFalse(core.is_safe_run_id(value), value)


class QueryAllowlistTest(unittest.TestCase):
    def test_session_reads_allow_known_keys(self):
        allowed = core.allowed_query_keys("GET", "/api/sessions")
        self.assertEqual(allowed, frozenset({"limit", "offset", "order", "include_children"}))
        self.assertEqual(core.allowed_query_keys("GET", "/api/sessions/x/messages"), allowed)

    def test_non_session_and_writes_have_no_query(self):
        self.assertEqual(core.allowed_query_keys("POST", "/api/sessions"), frozenset())
        self.assertEqual(core.allowed_query_keys("GET", "/v1/runs/run_1"), frozenset())
        self.assertEqual(core.allowed_query_keys("GET", "/v1/runs/run_1/events"), frozenset())
        self.assertEqual(core.allowed_query_keys("POST", "/v1/runs/run_1/steer"), frozenset())


class RateLimiterTest(unittest.TestCase):
    def test_window(self):
        limiter = core.RateLimiter(2, 10.0)
        self.assertTrue(limiter.allow(now=0.0))
        self.assertTrue(limiter.allow(now=1.0))
        self.assertFalse(limiter.allow(now=2.0))
        self.assertTrue(limiter.allow(now=11.0))  # first event out of window


class StoreCapTest(unittest.TestCase):
    def test_code_store_hard_cap(self):
        store = core.CodeStore(ttl_seconds=300, max_codes=2)
        self.assertIsNotNone(store.issue(now=0.0))
        self.assertIsNotNone(store.issue(now=0.0))
        self.assertIsNone(store.issue(now=0.0))  # refuses beyond the cap

    def test_token_store_hard_cap(self):
        store = core.TokenStore(max_tokens=2)
        self.assertIsNotNone(store.mint("a"))
        self.assertIsNotNone(store.mint("b"))
        self.assertIsNone(store.mint("c"))  # hard cap, no unbounded growth
        self.assertEqual(store.count(), 2)
        # Revoking frees a slot on the next mint.
        first = next(iter(store._by_hash.values()))
        first.revoked = True
        self.assertIsNotNone(store.mint("d"))
        self.assertEqual(store.count(), 2)


if __name__ == "__main__":
    unittest.main()
