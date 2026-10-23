"""Adversarial tests for the stream relay and the pairing/revoke surface (T6).

These attack the two places a hostile or broken peer can push bytes into the
bridge:

* ``POST /api/sessions/{id}/chat/stream`` and the re-attach
  ``GET /api/sessions/{id}/runs/{run_id}/stream?after_seq=N`` — malformed
  ``after_seq`` values, foreign devices/sessions, split, malformed, unsequenced,
  decreasing and oversized upstream frames, an upstream that never ends, a
  downstream that disconnects mid-stream, many concurrent re-attaches and the
  ``user_message`` echo redaction.
* ``POST /v1/devices/pair`` and ``POST /v1/devices/self/revoke`` — code
  normalisation, lookalikes, reuse/expiry, brute-force limiting, body shapes,
  store caps and repeated/revoked revokes.

Tests marked ``@unittest.expectedFailure`` pin a behaviour the bridge documents
(docs/mobile-wire-contract.md or bridge/README.md) but does not implement; each
carries a ``# BUG:`` line naming the deviation. All other tests assert the
current, correct fail-closed behaviour.
"""

import asyncio
import json
import time
import unittest
from unittest import mock

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import core
from orsik_mobile_bridge import pairing as pairing_mod
from orsik_mobile_bridge import runhub
from orsik_mobile_bridge import server as bridge_server
from orsik_mobile_bridge import store as store_mod

MASTER_KEY = "master_key_test"


# --- small helpers --------------------------------------------------------


def _sse(event, payload, *, frame_id=None):
    """Build one SSE frame; ``payload`` may be a dict or a raw (bad) string."""
    data = payload if isinstance(payload, str) else json.dumps(payload)
    lines = []
    if frame_id is not None:
        lines.append(f"id: {frame_id}")
    lines.append(f"event: {event}")
    lines.append("data: " + data)
    return ("\n".join(lines) + "\n\n").encode()


def _frame(event, run_id, seq, **fields):
    payload = {"session_id": "s1", "run_id": run_id, **fields}
    if seq is not None:
        payload["seq"] = seq
    return _sse(event, payload)


def _blocks(body):
    return [block for block in body.split(b"\n\n") if block.strip()]


def _data(block):
    for line in block.split(b"\n"):
        if line.startswith(b"data:"):
            return json.loads(line[len(b"data:"):])
    raise AssertionError("frame has no data line")


def _grouped(code, separator="-"):
    return separator.join(code[i:i + 4] for i in range(0, len(code), 4))


class _BridgeCase(unittest.IsolatedAsyncioTestCase):
    """Stub upstream + bridge client with one paired device."""

    run_status = 200

    async def asyncSetUp(self):
        self.chunks = []
        self.tail = []
        self.hold = False
        self.release = asyncio.Event()
        self.started_writing = asyncio.Event()
        self.finished = asyncio.Event()
        self.posts = 0

        app = web.Application()

        async def chat(request):
            self.posts += 1
            resp = web.StreamResponse()
            resp.content_type = "text/event-stream"
            await resp.prepare(request)
            for chunk in self.chunks:
                await resp.write(chunk)
            self.started_writing.set()
            if self.hold:
                await self.release.wait()
            for chunk in self.tail:
                await resp.write(chunk)
            await resp.write_eof()
            self.finished.set()
            return resp

        async def run(_):
            return web.Response(status=self.run_status)

        app.router.add_post("/api/sessions/{sid}/chat/stream", chat)
        app.router.add_get("/v1/runs/{rid}", run)
        self.upstream = TestServer(app)
        await self.upstream.start_server()
        url = str(self.upstream.make_url("/")).rstrip("/")
        self.state = bridge_server.BridgeState(url, MASTER_KEY)
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        self.token = await self._pair()

    async def asyncTearDown(self):
        self.release.set()
        await asyncio.sleep(0)
        await self.client.close()
        await self.upstream.close()
        self.state.store.close()

    def _bearer(self, token=None):
        return {"Authorization": "Bearer " + (self.token if token is None else token)}

    async def _pair(self):
        issued = self.state.codes.issue()
        self.assertIsNotNone(issued)
        resp = await self.client.post("/v1/devices/pair", json={"code": issued[0]})
        self.assertEqual(resp.status, 201)
        return (await resp.json())["device_key"]

    async def _start_run(self, chunks, tail=()):
        self.chunks = list(chunks)
        self.tail = list(tail)
        resp = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={"message": "hi"}
        )
        return resp

    async def _reattach(self, after_seq=None, *, session="s1", run_id="run_x", token=None):
        suffix = "" if after_seq is None else f"?after_seq={after_seq}"
        return await self.client.get(
            f"/api/sessions/{session}/runs/{run_id}/stream{suffix}",
            headers=self._bearer(token),
        )


# --- 1. after_seq and re-attach addressing ---------------------------------


class AfterSeqTest(_BridgeCase):
    """``after_seq`` parsing, replay boundaries and re-attach authorization."""

    async def _completed_run(self, run_id="run_x"):
        frames = [
            _frame("run.started", run_id, 1),
            _frame("assistant.delta", run_id, 2, delta="a"),
            _frame("done", run_id, 3),
        ]
        resp = await self._start_run(frames)
        self.assertEqual(resp.status, 200)
        return frames, await resp.read()

    async def test_missing_after_seq_replays_the_whole_buffer(self):
        frames, body = await self._completed_run()
        self.assertEqual(body, b"".join(frames))
        again = await self._reattach()
        self.assertEqual(again.status, 200)
        self.assertEqual(await again.read(), body)

    async def test_after_seq_minus_one_replays_the_whole_buffer(self):
        frames, body = await self._completed_run()
        again = await self._reattach(-1)
        self.assertEqual(again.status, 200)
        self.assertEqual(await again.read(), body)

    async def test_after_seq_zero_replays_the_whole_buffer(self):
        _, body = await self._completed_run()
        again = await self._reattach(0)
        self.assertEqual(await again.read(), body)

    async def test_after_seq_is_strictly_exclusive(self):
        frames, _ = await self._completed_run()
        again = await self._reattach(1)
        self.assertEqual(again.status, 200)
        self.assertEqual(await again.read(), b"".join(frames[1:]))

    async def test_after_seq_at_the_newest_seq_replays_nothing(self):
        await self._completed_run()
        again = await self._reattach(3)
        self.assertEqual(again.status, 200)
        self.assertEqual(await again.read(), b"")

    async def test_after_seq_above_the_newest_seq_is_accepted_with_empty_replay(self):
        # Observation, not a bug: the bridge trusts the client's claim about what
        # it already has and simply follows live (this buffer is terminal).
        await self._completed_run()
        for value in (4, 10 ** 30):
            resp = await self._reattach(value)
            self.assertEqual(resp.status, 200, value)
            self.assertEqual(await resp.read(), b"", value)

    async def test_after_seq_below_minus_one_is_refused(self):
        await self._completed_run()
        for value in (-2, -1000):
            resp = await self._reattach(value)
            self.assertEqual(resp.status, 400, value)
            self.assertEqual((await resp.json())["error"]["code"], "invalid_after_seq")

    async def test_non_numeric_after_seq_is_refused(self):
        await self._completed_run()
        for value in ("abc", "1.5", "1e3", "0x1", "NaN", "", "%20"):
            resp = await self._reattach(value)
            self.assertEqual(resp.status, 400, value)
            self.assertEqual((await resp.json())["error"]["code"], "invalid_after_seq")

    async def test_python_int_spellings_are_quietly_accepted(self):
        # Observation: int() also accepts "+1", " 1", "1_0" and non-ASCII
        # decimal digits, so a non-canonical value is interpreted rather than
        # refused. None of these reach upstream (the re-attach path never
        # forwards the query string).
        frames, body = await self._completed_run()
        self.assertEqual(await (await self._reattach("+1")).read(), b"".join(frames[1:]))
        self.assertEqual(await (await self._reattach("%201")).read(), b"".join(frames[1:]))
        self.assertEqual(await (await self._reattach("%D9%A1")).read(), b"".join(frames[1:]))
        # "1_0" is int 10: above the newest seq -> empty replay.
        self.assertEqual(await (await self._reattach("1_0")).read(), b"")
        self.assertEqual(body, b"".join(frames))

    async def test_repeated_after_seq_uses_the_first_value(self):
        # Observation: aiohttp's MultiDict.get() returns the first duplicate, so
        # "?after_seq=1&after_seq=0" replays after 1, not after 0.
        frames, _ = await self._completed_run()
        resp = await self.client.get(
            "/api/sessions/s1/runs/run_x/stream?after_seq=1&after_seq=0",
            headers=self._bearer(),
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), b"".join(frames[1:]))

    async def test_unknown_query_keys_are_ignored_on_reattach(self):
        # Observation: unlike chat/proxy, the re-attach path has no query
        # allowlist. The value is never forwarded upstream, so this is laxness
        # rather than a proxy escape.
        frames, _ = await self._completed_run()
        resp = await self.client.get(
            "/api/sessions/s1/runs/run_x/stream?after_seq=1&evil=1",
            headers=self._bearer(),
        )
        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), b"".join(frames[1:]))

    async def test_another_devices_token_can_reattach(self):
        # Documented single-owner delegation (bridge/README.md): every paired
        # device is a delegate of the same owner, so a second device sees the
        # same runs. It is not faked as per-device isolation.
        frames, body = await self._completed_run()
        other = await self._pair()
        resp = await self._reattach(0, token=other)
        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), b"".join(frames))

    async def test_a_revoked_token_cannot_reattach(self):
        await self._completed_run()
        revoked = await self.client.post("/v1/devices/self/revoke", headers=self._bearer())
        self.assertEqual(revoked.status, 200)
        resp = await self._reattach(0)
        self.assertEqual(resp.status, 401)
        self.assertEqual((await resp.json())["error"]["code"], "invalid_credentials")

    async def test_reattach_to_another_session_is_404_not_409(self):
        # Was a bug (fixed): docs/mobile-wire-contract.md §8.2 says a session mismatch is
        # "404". The bridge ignores the session when it has no buffer keyed by
        # that pair and asks upstream about the run_id, so a run it *does* hold
        # under s1 is reported as 409 resume_gap under s2 (upstream says 200).
        # Expected: 404 run_not_found; observed: 409 resume_gap.
        await self._completed_run()
        resp = await self._reattach(0, session="s2")
        self.assertEqual(resp.status, 404)

    async def test_unbuffered_run_known_upstream_is_409(self):
        # Contrast case (existing contract, README): a run the upstream still
        # knows but the bridge cannot reconstruct is indeterminate, not 404.
        resp = await self._reattach(0, run_id="run_orphan")
        self.assertEqual(resp.status, 409)
        self.assertEqual((await resp.json())["error"]["code"], "resume_gap")

    async def test_after_seq_below_the_evicted_boundary_is_409(self):
        frames = [
            _frame("run.started", "run_x", 1),
            _frame("assistant.delta", "run_x", 2),
            _frame("assistant.delta", "run_x", 3),
            _frame("done", "run_x", 4),
        ]
        with mock.patch.object(runhub.RunBuffer, "BACKLOG_LIMIT", 2):
            # Hold after the first frame so the originating client attaches with
            # only seq 1 buffered; the later frames then fill and evict.
            self.chunks = [frames[0]]
            self.tail = frames[1:]
            self.hold = True
            post = await self.client.post(
                "/api/sessions/s1/chat/stream", headers=self._bearer(), json={})
            self.assertEqual(post.status, 200)
            self.assertEqual(
                await asyncio.wait_for(post.content.readuntil(b"\n\n"), 2), frames[0])
            self.release.set()
            self.assertEqual(
                await asyncio.wait_for(post.read(), 5), b"".join(frames[1:]))

            gap = await self._reattach(0)  # seq 1 and 2 were evicted
            self.assertEqual(gap.status, 409)
            self.assertEqual((await gap.json())["error"]["code"], "resume_gap")

            exact = await self._reattach(2)  # seq 3 and 4 are retained
            self.assertEqual(exact.status, 200)
            self.assertEqual(await exact.read(), b"".join(frames[2:]))

    async def test_decreasing_upstream_seq_must_not_lower_the_evicted_boundary(self):
        # Was a bug (fixed): runhub.evicted_through is documented as the *highest* seq evicted,
        # but it is overwritten with whatever seq is popped. With a decreasing
        # upstream sequence it falls (10 -> 9 -> 8), so a replay that should be
        # refused as a hole is accepted and silently omits retained frames.
        # Input: seq 10,9,8,7,6 with BACKLOG_LIMIT=2. Expected: after_seq=9 is
        # refused (409) because seq 10 was evicted; observed: 200 with an empty
        # body (and after_seq=8 is also accepted though it should not be).
        with mock.patch.object(runhub.RunBuffer, "BACKLOG_LIMIT", 2):
            frames = [
                _frame("run.started", "run_x", 10),
                _frame("assistant.delta", "run_x", 9),
                _frame("assistant.delta", "run_x", 8),
                _frame("assistant.delta", "run_x", 7),
                _frame("done", "run_x", 6),
            ]
            self.chunks = [frames[0]]
            self.tail = frames[1:]
            self.hold = True
            post = await self.client.post(
                "/api/sessions/s1/chat/stream", headers=self._bearer(), json={})
            self.assertEqual(
                await asyncio.wait_for(post.content.readuntil(b"\n\n"), 2), frames[0])
            self.release.set()
            self.assertEqual(
                await asyncio.wait_for(post.read(), 5), b"".join(frames[1:]))
            for after_seq in (9, 8):
                replay = await self._reattach(after_seq)
                self.assertEqual(replay.status, 409, after_seq)


# --- 2. adversarial upstream frames and clients ----------------------------


class AdversarialStreamTest(_BridgeCase):
    """Split/malformed/unsequenced/oversized frames, hangs and disconnects."""

    async def test_frames_split_byte_by_byte_are_reassembled(self):
        frames = [
            _frame("run.started", "run_x", 1),
            _frame("assistant.delta", "run_x", 2, delta="a"),
            _frame("done", "run_x", 3),
        ]
        stream = b"".join(frames)
        resp = await self._start_run([stream[i:i + 1] for i in range(len(stream))])
        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), stream)
        again = await self._reattach(0)
        self.assertEqual(await again.read(), stream)

    async def test_malformed_json_is_relayed_unchanged(self):
        bad = _frame("assistant.delta", "run_x", 2).replace(
            b'"seq": 2', b'"seq": 2, "pad": }')
        self.assertNotEqual(bad, _frame("assistant.delta", "run_x", 2))
        frames = [_frame("run.started", "run_x", 1), bad, _frame("done", "run_x", 3)]
        resp = await self._start_run(frames)
        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), b"".join(frames))

    async def test_frames_without_seq_get_synthetic_seq_for_replay(self):
        frames = [
            _frame("run.started", "run_x", None),
            _frame("assistant.delta", "run_x", None),
            _frame("done", "run_x", None),
        ]
        resp = await self._start_run(frames)
        self.assertEqual(await resp.read(), b"".join(frames))
        # Synthetic seqs are 0,1,2: after 0 the delta and done replay.
        again = await self._reattach(0)
        self.assertEqual(again.status, 200)
        self.assertEqual(await again.read(), b"".join(frames[1:]))

    async def test_decreasing_seq_is_relayed_in_arrival_order(self):
        frames = [
            _frame("run.started", "run_x", 5),
            _frame("assistant.delta", "run_x", 3),
            _frame("done", "run_x", 1),
        ]
        resp = await self._start_run(frames)
        self.assertEqual(await resp.read(), b"".join(frames))
        # Replay filters by seq but preserves arrival order.
        replay = await self._reattach(3)
        self.assertEqual(await replay.read(), frames[0])

    async def test_oversized_first_frame_is_502(self):
        huge = _frame("assistant.delta", "run_x", 1, pad="x" * (2 * 1024 * 1024))
        resp = await self._start_run([huge])
        self.assertEqual(resp.status, 502)
        self.assertEqual((await resp.json())["error"]["code"], "upstream_frame_too_large")

    async def test_oversized_frame_after_start_aborts_without_fake_done(self):
        started = _frame("run.started", "run_x", 1)
        huge = _frame("assistant.delta", "run_x", 2, pad="x" * (2 * 1024 * 1024))
        resp = await self._start_run([started, huge])
        self.assertEqual(resp.status, 200)
        body = await resp.read()
        self.assertEqual(body, started + b": bridge_upstream_frame_too_large\n\n")
        self.assertNotIn(b"event: done", body)
        again = await self._reattach(0)
        self.assertEqual(await again.read(), body)

    async def test_upstream_that_never_ends_keeps_the_client_and_run_alive(self):
        started = _frame("run.started", "run_live", 1)
        delta = _frame("assistant.delta", "run_live", 2, delta="a")
        done = _frame("done", "run_live", 3)
        self.chunks = [started]
        self.tail = [delta, done]
        self.hold = True
        with mock.patch.object(bridge_server, "_RUN_STREAM_KEEPALIVE", 0.1):
            post = await self.client.post(
                "/api/sessions/s1/chat/stream", headers=self._bearer(), json={})
            self.assertEqual(post.status, 200)
            self.assertEqual(await asyncio.wait_for(
                post.content.readuntil(b"\n\n"), 2), started)
            # The upstream produces nothing: the bridge must not hang its
            # downstream silently, and the run must remain re-attachable.
            keepalive = await asyncio.wait_for(post.content.readuntil(b"\n\n"), 2)
            self.assertEqual(keepalive, b": keepalive\n\n")
            buf = self.state.runhub.lookup("s1", "run_live")
            self.assertIsNotNone(buf)
            assert buf is not None
            self.assertFalse(buf.terminal)
            again = await self._reattach(1, run_id="run_live")
            self.assertEqual(again.status, 200)
            self.release.set()
            self.assertEqual(await asyncio.wait_for(again.read(), 5), delta + done)
            self.assertEqual(await asyncio.wait_for(post.read(), 5), delta + done)
        self.assertEqual(self.posts, 1)

    async def test_downstream_disconnect_does_not_stop_the_run(self):
        started = _frame("run.started", "run_x", 1)
        delta = _frame("assistant.delta", "run_x", 2, delta="a")
        done = _frame("done", "run_x", 3)
        self.chunks = [started]
        self.tail = [delta, done]
        self.hold = True
        post = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={})
        self.assertEqual(await asyncio.wait_for(post.content.readuntil(b"\n\n"), 2), started)
        post.close()
        buf = self.state.runhub.lookup("s1", "run_x")
        self.assertIsNotNone(buf)
        assert buf is not None
        self.assertFalse(buf.terminal)
        # The run is still re-attachable while it keeps running upstream.
        again = await self._reattach(1)
        self.assertEqual(again.status, 200)
        self.release.set()
        await asyncio.wait_for(self.finished.wait(), 5)
        self.assertEqual(await asyncio.wait_for(again.read(), 5), delta + done)
        self.assertEqual(self.posts, 1)

    async def test_many_concurrent_reattaches_are_bounded_at_four(self):
        started = _frame("run.started", "run_x", 1)
        delta = _frame("assistant.delta", "run_x", 2, delta="a")
        done = _frame("done", "run_x", 3)
        self.chunks = [started]
        self.tail = [delta, done]
        self.hold = True
        post = await self.client.post(
            "/api/sessions/s1/chat/stream", headers=self._bearer(), json={})
        self.assertEqual(await asyncio.wait_for(post.content.readuntil(b"\n\n"), 2), started)
        buf = self.state.runhub.lookup("s1", "run_x")
        self.assertIsNotNone(buf)
        assert buf is not None

        tasks = [asyncio.create_task(self._reattach(1)) for _ in range(6)]
        responses = await asyncio.wait_for(asyncio.gather(*tasks), 5)
        self.assertEqual({resp.status for resp in responses}, {200})
        # The originating client plus six re-attaches exceed MAX_SUBSCRIBERS.
        self.assertEqual(len(buf.subscribers), runhub.RunBuffer.MAX_SUBSCRIBERS_PER_RUN)
        self.release.set()
        bodies = await asyncio.wait_for(asyncio.gather(*(r.read() for r in responses)), 5)
        self.assertEqual(self.posts, 1)
        complete = [body for body in bodies if body == delta + done]
        overflow = [body for body in bodies if b": bridge_overflow" in body]
        self.assertEqual(len(complete), 4)
        self.assertEqual(len(overflow), 2)
        self.assertEqual(b"".join(overflow).count(b": bridge_overflow"), 2)
        # The oldest subscriber (the chat POST) was evicted first.
        self.assertIn(b": bridge_overflow", await asyncio.wait_for(post.read(), 5))

    async def test_user_message_echo_is_redacted_across_chunk_boundaries(self):
        image = "data:image/png;base64," + "A" * (2 * 1024 * 1024)
        started = _sse(
            "run.started",
            {
                "session_id": "s1", "run_id": "run_echo", "seq": 1, "ts": 1.0,
                "user_message": {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "was ist das?"},
                        {"type": "image_url", "image_url": {"url": image}},
                    ],
                },
            },
            frame_id=7,
        )
        delta = _frame("assistant.delta", "run_echo", 2, delta="a")
        done = _frame("done", "run_echo", 3)
        third = len(started) // 3
        resp = await self._start_run([started[:100], started[100:third], started[third:], delta, done])
        self.assertEqual(resp.status, 200)
        body = await resp.read()
        self.assertNotIn(b"base64", body)
        self.assertNotIn(b"user_message", body)
        first = _blocks(body)[0]
        self.assertLess(len(first), 1024 * 1024)
        self.assertTrue(first.startswith(b"id: 7\nevent: run.started\ndata: "))
        payload = _data(first)
        self.assertNotIn("user_message", payload)
        self.assertEqual((payload["seq"], payload["run_id"], payload["ts"]), (1, "run_echo", 1.0))
        # The buffered/replayed frame is the redacted one, not the original.
        again = await self._reattach(0, run_id="run_echo")
        replay = await again.read()
        self.assertNotIn(b"base64", replay)
        self.assertEqual(_blocks(replay)[0], first)


# --- 3. pairing: normalisation, replay, rate limit, bodies, caps -----------


class PairingAdversarialTest(unittest.IsolatedAsyncioTestCase):
    """``POST /v1/devices/pair`` adversarial inputs."""

    async def asyncSetUp(self):
        self.state = bridge_server.BridgeState("http://127.0.0.1:1", MASTER_KEY)
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.state.store.close()

    async def _code(self):
        issued = self.state.codes.issue()
        self.assertIsNotNone(issued)
        return issued[0]

    async def _pair(self, body, *, headers=None):
        return await self.client.post("/v1/devices/pair", json=body, headers=headers)

    async def test_normalisation_accepts_case_dashes_spaces_underscores(self):
        transforms = (
            lambda code: code.lower(),
            lambda code: _grouped(code),
            lambda code: _grouped(code, " "),
            lambda code: _grouped(code, "_"),
            lambda code: " \t" + _grouped(code.lower(), " - _") + "\r\n",
        )
        for transform in transforms:
            code = await self._code()
            resp = await self._pair({"code": transform(code)})
            self.assertEqual(resp.status, 201, transform)
            cred = await resp.json()
            self.assertTrue(cred["device_key"].startswith("hd_"))
            self.assertEqual(set(cred["permissions"]), set(core.DEVICE_PERMISSIONS))

    async def test_lookalikes_and_wrong_apostrophes_are_rejected(self):
        homoglyphs = {"A": "\u0410", "B": "\u0412", "C": "\u0421", "E": "\u0415",
                      "H": "\u041d", "K": "\u041a", "M": "\u041c", "P": "\u0420",
                      "T": "\u0422", "X": "\u0425", "Y": "\u0423"}
        code = await self._code()
        match = None
        for _ in range(50):
            match = next((ch for ch in code if ch in homoglyphs), None)
            if match:
                break
            code = await self._code()
        self.assertIsNotNone(match)
        assert match is not None
        homoglyph = code.replace(match, homoglyphs[match], 1)
        resp = await self._pair({"code": _grouped(homoglyph)})
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "pairing_code_invalid")

        for dash in ("\u2013", "\u2014", "\u2010"):
            code = await self._code()
            resp = await self._pair({"code": _grouped(code, dash)})
            self.assertEqual(resp.status, 400, dash)

        code = await self._code()
        digit = next(ch for ch in code if ch.isdigit())
        fullwidth = chr(ord(digit) + 0xFEE0)
        resp = await self._pair({"code": code.replace(digit, fullwidth, 1)})
        self.assertEqual(resp.status, 400)

    async def test_non_ascii_whitespace_is_normalised(self):
        # Was a bug (fixed): the contract says the code is normalised by removing "-",
        # "_" and whitespace; core.normalize_code only removes ASCII
        # space/TAB/CR/LF. A code pasted with a non-breaking space or a thin
        # space is rejected. Expected: 201; observed: 400.
        for space in ("\u00a0", "\u2009", "\v", "\f"):
            code = await self._code()
            resp = await self._pair({"code": _grouped(code, space)})
            self.assertEqual(resp.status, 201, repr(space))

    async def test_too_short_and_too_long_codes_are_rejected(self):
        code = await self._code()
        for bad in (code[:-1], code + "A", "", "A" * 23, "A" * 25):
            resp = await self._pair({"code": bad})
            self.assertEqual(resp.status, 400, bad)
            self.assertEqual((await resp.json())["error"]["code"], "pairing_code_invalid")

    async def test_wrong_code_types_are_rejected(self):
        for bad in (123, 0, 1.5, True, None, [], {}, ["code"], {"code": "x"}):
            resp = await self._pair({"code": bad})
            self.assertEqual(resp.status, 400, bad)
            self.assertEqual((await resp.json())["error"]["code"], "pairing_code_invalid")

    async def test_non_object_and_malformed_bodies_are_rejected(self):
        for body in ([], ["x"], "text", 42):
            resp = await self._pair(body)
            self.assertEqual(resp.status, 400, body)
            self.assertEqual((await resp.json())["error"]["code"], "pairing_code_invalid")
        # aiohttp's ``json=None`` means "no payload"; send JSON null explicitly.
        for raw in (b"null", b"", b"not json", b"{", b'{"code":}'):
            resp = await self.client.post(
                "/v1/devices/pair", data=raw, headers={"Content-Type": "application/json"})
            self.assertEqual(resp.status, 400, raw)
            expected = "pairing_code_invalid" if raw == b"null" else "invalid_json"
            self.assertEqual((await resp.json())["error"]["code"], expected, raw)

    async def test_extra_body_fields_are_rejected(self):
        # Was a bug (fixed): docs/mobile-wire-contract.md §2.3 says the body is *exactly*
        # {"code": "<24-character code>"}, and every other validating handler
        # rejects unknown fields. The pair handler only reads body["code"], so
        # extra fields (here a permissions escalation attempt) are ignored and
        # a credential is minted. Expected: 400 invalid_fields; observed: 201.
        code = await self._code()
        resp = await self._pair({"code": code, "permissions": ["admin"], "extra": 1})
        self.assertEqual(resp.status, 400)

    async def test_non_json_content_type_is_rejected(self):
        # Was a bug (fixed): §2.3 requires Content-Type: application/json, but aiohttp's
        # request.json() parses the body regardless, so the same JSON sent as
        # text/plain mints a credential. Expected: closed refusal (400/415);
        # observed: 201.
        code = await self._code()
        resp = await self.client.post(
            "/v1/devices/pair",
            data=json.dumps({"code": code}),
            headers={"Content-Type": "text/plain"},
        )
        self.assertEqual(resp.status, 400)

    async def test_used_code_cannot_be_redeemed_twice(self):
        code = await self._code()
        self.assertEqual((await self._pair({"code": code})).status, 201)
        second = await self._pair({"code": _grouped(code.lower())})
        self.assertEqual(second.status, 400)
        self.assertEqual((await second.json())["error"]["code"], "pairing_code_invalid")

    async def test_expired_code_is_rejected(self):
        issued = self.state.codes.issue(now=time.time() - core.CODE_TTL_SECONDS - 1)
        self.assertIsNotNone(issued)
        resp = await self._pair({"code": issued[0]})
        self.assertEqual(resp.status, 400)
        self.assertEqual((await resp.json())["error"]["code"], "pairing_code_invalid")

    async def test_brute_force_is_rate_limited_before_the_code_is_checked(self):
        self.state.pair_limiter = core.RateLimiter(3, 60.0)
        for _ in range(3):
            self.assertEqual((await self._pair({"code": "A" * 24})).status, 400)
        code = await self._code()
        limited = await self._pair({"code": code})
        self.assertEqual(limited.status, 429)
        self.assertEqual((await limited.json())["error"]["code"], "rate_limited")
        # A rate-limited attempt does not consume the valid code.
        self.state.pair_limiter = core.RateLimiter(3, 60.0)
        self.assertEqual((await self._pair({"code": code})).status, 201)

    async def test_huge_pair_body_is_413(self):
        with mock.patch.object(bridge_server, "_MAX_BODY_BYTES", 200):
            state = bridge_server.BridgeState("http://127.0.0.1:1", MASTER_KEY)
            client = TestClient(TestServer(bridge_server.create_app(state)))
            await client.start_server()
            try:
                resp = await client.post(
                    "/v1/devices/pair", json={"code": "A" * 400})
                self.assertEqual(resp.status, 413)
                self.assertEqual((await resp.json())["error"]["code"], "body_too_large")
            finally:
                await client.close()
                state.store.close()

    async def test_device_capacity_is_enforced_and_freed_by_revoke(self):
        store = store_mod.BridgeStore(":memory:", max_devices=1)
        state = bridge_server.BridgeState("http://127.0.0.1:1", MASTER_KEY, store=store)
        client = TestClient(TestServer(bridge_server.create_app(state)))
        await client.start_server()
        try:
            first_code = state.codes.issue()[0]
            first = await client.post("/v1/devices/pair", json={"code": first_code})
            self.assertEqual(first.status, 201)
            token = (await first.json())["device_key"]

            second_code = state.codes.issue()[0]
            full = await client.post("/v1/devices/pair", json={"code": second_code})
            self.assertEqual(full.status, 503)
            self.assertEqual((await full.json())["error"]["code"], "device_capacity_reached")
            self.assertNotIn("device_key", await full.json())

            self.assertEqual(
                (await client.post("/v1/devices/self/revoke",
                                   headers={"Authorization": "Bearer " + token})).status, 200)
            third_code = state.codes.issue()[0]
            third = await client.post("/v1/devices/pair", json={"code": third_code})
            self.assertEqual(third.status, 201)
        finally:
            await client.close()
            state.store.close()

    def test_code_cap_is_enforced_and_released_by_redemption(self):
        store = store_mod.BridgeStore(":memory:", max_codes=1)
        try:
            first = store.issue_code()
            self.assertIsNotNone(first)
            assert first is not None
            self.assertIsNone(store.issue_code())
            self.assertTrue(store.redeem_code(first[0]))
            # The used code is pruned, so a new one fits the cap again.
            self.assertIsNotNone(store.issue_code())
        finally:
            store.close()


# --- 4. self-revoke -------------------------------------------------------


class SelfRevokeAdversarialTest(unittest.IsolatedAsyncioTestCase):
    """``POST /v1/devices/self/revoke`` repeated, revoked, and with a body."""

    async def asyncSetUp(self):
        self.state = bridge_server.BridgeState("http://127.0.0.1:1", MASTER_KEY)
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        issued = self.state.codes.issue()
        assert issued is not None
        self.cred = (await (await self.client.post(
            "/v1/devices/pair", json={"code": issued[0]})).json())
        self.token = self.cred["device_key"]

    async def asyncTearDown(self):
        await self.client.close()
        self.state.store.close()

    def _bearer(self, token=None):
        return {"Authorization": "Bearer " + (self.token if token is None else token)}

    async def _revoke(self, **kwargs):
        return await self.client.post("/v1/devices/self/revoke", headers=self._bearer(), **kwargs)

    async def test_first_revoke_is_200_and_kills_the_token(self):
        resp = await self._revoke()
        self.assertEqual(resp.status, 200)
        body = await resp.json()
        self.assertEqual(set(body), {"revoked", "device_id"})
        self.assertTrue(body["revoked"])
        self.assertEqual(body["device_id"], self.cred["device_id"])
        self.assertEqual(
            (await self.client.get("/v1/capabilities", headers=self._bearer())).status, 401)

    async def test_second_revoke_reports_revoked_false(self):
        # Was a bug (fixed): §2.4 says a repeat revoke succeeds with "revoked": false
        # ("already revoked"). The handler always serialises True.
        # Input: same token twice. Expected: 200 {"revoked": false}; observed:
        # 200 {"revoked": true}.
        self.assertEqual((await self._revoke()).status, 200)
        second = await self._revoke()
        self.assertEqual(second.status, 200)
        self.assertFalse((await second.json())["revoked"])

    async def test_repeat_revoke_after_a_prune_is_an_unknown_key(self):
        # Was a bug (fixed): the repeat revoke is not even stable across unrelated store
        # activity: BridgeStore.prune() deletes revoked rows, after which the
        # same token gets 401 instead of the documented 200 {"revoked": false}.
        # Decided: once the store pruned the revoked row, the key is simply
        # unknown (401), like any other unknown key (documented in the
        # contract); before the prune the repeat is 200 revoked:false.
        self.assertEqual((await self._revoke()).status, 200)
        self.state.store.prune()
        second = await self._revoke()
        self.assertEqual(second.status, 401)

    async def test_a_body_is_rejected(self):
        # Was a bug (fixed): §2.4 says the body is exactly 0 bytes, but the handler never
        # reads or rejects a body. Input: a valid token plus {"why": "..."}.
        # Expected: 400 body_not_allowed; observed: 200 and the device is
        # revoked anyway.
        resp = await self._revoke(json={"why": "not part of the contract"})
        self.assertEqual(resp.status, 400)

    async def test_unknown_token_is_401(self):
        resp = await self.client.post(
            "/v1/devices/self/revoke",
            headers={"Authorization": "Bearer hd_" + "A" * 43},
        )
        self.assertEqual(resp.status, 401)
        self.assertEqual((await resp.json())["error"]["code"], "invalid_credentials")

    async def test_missing_or_malformed_authorization_is_401(self):
        for headers in ({}, {"Authorization": ""}, {"Authorization": "Bearer"},
                        {"Authorization": "Basic abc"}, {"Authorization": "hd_" + "A" * 43}):
            resp = await self.client.post("/v1/devices/self/revoke", headers=headers)
            self.assertEqual(resp.status, 401, headers)
            self.assertEqual((await resp.json())["error"]["code"], "invalid_credentials")


class PairingFormatTest(unittest.TestCase):
    """The displayed grouping always normalises back to the issued code."""

    def test_format_code_round_trips_through_normalisation(self):
        code = "ABCDEFGHJKMNPQRSTVWXYZ12"
        self.assertEqual(pairing_mod.format_code(code), "ABCD-EFGH-JKMN-PQRS-TVWX-YZ12")
        self.assertEqual(core.normalize_code(pairing_mod.format_code(code)), code)
        self.assertEqual(core.normalize_code(code.lower()), code)


if __name__ == "__main__":
    unittest.main()
