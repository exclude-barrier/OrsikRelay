"""Unit tests for the bridge SSE decoder and run buffer/registry (P13 Phase 2)."""

import asyncio
import json
import time
import unittest

from orsik_mobile_bridge.runhub import RunBuffer, RunHub
from orsik_mobile_bridge.sse import Frame, SSEDecoder, SSEFrameTooLarge


def _frame(seq, run_id="run_1", event="assistant.delta"):
    payload = {"session_id": "s1", "run_id": run_id, "seq": seq, "ts": 1.0}
    return f"event: {event}\ndata: {json.dumps(payload)}\n\n".encode()


def _append(buf, seq, run_id="run_1"):
    data = {"session_id": buf.session_id, "run_id": run_id, "seq": seq}
    raw = json.dumps(data).encode()
    buf.append(Frame(event="assistant.delta", data=raw.decode(), json=data, raw=raw))


class SSEDecoderTest(unittest.TestCase):
    def test_decodes_frame_and_extracts_event(self):
        dec = SSEDecoder()
        frames = dec.feed(_frame(1))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].event, "assistant.delta")
        self.assertEqual(frames[0].json["seq"], 1)
        self.assertEqual(frames[0].raw, _frame(1))

    def test_split_chunks_reassemble(self):
        dec = SSEDecoder()
        raw = _frame(1)
        out = []
        for i in range(0, len(raw), 3):
            out += dec.feed(raw[i:i + 3])
        self.assertEqual(len(out), 1)
        self.assertEqual(out[0].json["run_id"], "run_1")

    def test_comments_and_keepalive_ignored(self):
        dec = SSEDecoder()
        frames = dec.feed(b": keepalive\n\n" + _frame(2))
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].json["seq"], 2)

    def test_malformed_json_is_relayed_not_raised(self):
        dec = SSEDecoder()
        frames = dec.feed(b"event: done\ndata: not json\n\n")
        self.assertEqual(len(frames), 1)
        self.assertIsNone(frames[0].json)
        self.assertEqual(frames[0].event, "done")

    def test_crlf_normalised(self):
        dec = SSEDecoder()
        frames = dec.feed(b"event: done\r\ndata: {\"seq\": 1}\r\n\r\n")
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].json["seq"], 1)

    def test_byte_at_a_time_matches_single_chunk(self):
        # The decoder only rescans new bytes; mixed LF/CRLF/CR line endings fed
        # one byte at a time must decode to exactly the frames of a single feed.
        stream = (_frame(1) + _frame(2).replace(b"\n", b"\r\n")
                  + b": keepalive\r\r" + _frame(3).replace(b"\n", b"\r")
                  + b"data: tail\n\n\n")
        whole = SSEDecoder().feed(stream)
        dec = SSEDecoder()
        split = []
        for i in range(len(stream)):
            split += dec.feed(stream[i:i + 1])
        self.assertEqual([f.json and f.json.get("seq") for f in whole], [1, 2, 3, None])
        self.assertEqual(split, whole)

    def test_exact_limit_frame_accepted_regardless_of_chunking(self):
        # A frame body of exactly max_frame_bytes must be accepted
        # whether it arrives whole or split across the terminating blank line.
        limit = 32
        block = b"data: " + b"x" * (limit - 6)
        self.assertEqual(len(block), limit)
        # one chunk
        self.assertEqual(len(SSEDecoder(max_frame_bytes=limit).feed(block + b"\n\n")), 1)
        # split between the two LFs
        dec = SSEDecoder(max_frame_bytes=limit)
        self.assertEqual(dec.feed(block + b"\n"), [])
        self.assertEqual(len(dec.feed(b"\n")), 1)
        # CRLF, split before the final LF (the CR sits in _hold)
        dec = SSEDecoder(max_frame_bytes=limit)
        self.assertEqual(dec.feed(block + b"\r\n\r"), [])
        self.assertEqual(len(dec.feed(b"\n")), 1)

    def test_over_limit_frame_still_rejected_and_stays_closed(self):
        limit = 32
        block = b"data: " + b"x" * (limit + 1 - 6)
        self.assertEqual(len(block), limit + 1)
        dec = SSEDecoder(max_frame_bytes=limit)
        with self.assertRaises(SSEFrameTooLarge):
            dec.feed(block + b"\n\n")
        # an incomplete body already past the limit also raises, with no frames
        dec = SSEDecoder(max_frame_bytes=limit)
        with self.assertRaises(SSEFrameTooLarge) as ctx:
            dec.feed(b"x" * (limit + 1))
        self.assertEqual(ctx.exception.frames, [])
        with self.assertRaises(SSEFrameTooLarge) as ctx:
            dec.feed(b"more")
        self.assertEqual(ctx.exception.frames, [])

    def test_frames_before_overflow_are_returned(self):
        limit = 32
        good = b"data: ok\n\n"
        oversized = b"data: " + b"x" * limit + b"\n\n"
        with self.assertRaises(SSEFrameTooLarge) as ctx:
            SSEDecoder(max_frame_bytes=limit).feed(good + oversized)
        self.assertEqual(len(ctx.exception.frames), 1)


class RunBufferTest(unittest.TestCase):
    def test_exclusive_replay(self):
        buf = RunBuffer("s1")
        for seq in (1, 2, 3):
            _append(buf, seq)
        queue, replay = buf.attach(1)
        self.assertEqual([s for s, _ in replay], [2, 3])
        self.assertIsNotNone(queue)

    def test_no_gap_when_contiguous(self):
        buf = RunBuffer("s1")
        for seq in (1, 2, 3):
            _append(buf, seq)
        attached = buf.attach(0)
        self.assertIsNotNone(attached)
        self.assertEqual([s for s, _ in attached[1]], [1, 2, 3])

    def test_gap_detected_when_backlog_evicts(self):
        buf = RunBuffer("s1")
        for seq in range(1, 1002):
            _append(buf, seq)
        self.assertIsNone(buf.attach(0))  # oldest is now 2, client expected 1

    def test_fresh_attach_after_eviction_is_gap(self):
        # Once frames were evicted, a replay with the default after_seq=-1
        # would silently leave a hole; fail closed with a gap instead.
        buf = RunBuffer("s1")
        for seq in range(1, 1002):
            _append(buf, seq)
        self.assertEqual(buf.evicted_through, 1)
        self.assertIsNone(buf.attach(-1))
        attached = buf.attach(1)
        self.assertIsNotNone(attached)
        self.assertEqual(attached[1][0][0], 2)
        self.assertEqual(len(attached[1]), 1000)

    def test_terminal_finish_fans_out(self):
        buf = RunBuffer("s1")
        queue, _ = buf.attach(-1)
        _append(buf, 1)
        buf.finish()
        self.assertTrue(buf.terminal)
        self.assertEqual(queue.get_nowait()[0], 1)
        self.assertIsNone(queue.get_nowait()[0])

    def test_run_id_learned_from_first_frame(self):
        buf = RunBuffer("s1")
        self.assertIsNone(buf.run_id)
        _append(buf, 1, run_id="run_42")
        self.assertEqual(buf.run_id, "run_42")


class RunHubTest(unittest.TestCase):
    def test_register_lookup_and_session_isolation(self):
        hub = RunHub()
        buf = hub.create_pending("s1")
        _append(buf, 1, run_id="run_1")
        hub.register(buf)
        self.assertIs(hub.lookup("s1", "run_1"), buf)
        self.assertIsNone(hub.lookup("s2", "run_1"))
        self.assertIsNone(hub.lookup("s1", "run_999"))

    def test_pending_pruned_when_never_registered(self):
        hub = RunHub(retention_seconds=0.0)
        hub.create_pending("s1")
        hub.create_pending("s2")
        hub._prune(now=time.time() + 1)
        self.assertEqual(len(hub._pending), 0)

    def test_data_only_frame_is_not_terminal(self):
        # A frame with no `event:` line is a regular SSE message, not a terminal.
        buf = RunBuffer("s1")
        raw = b'{"run_id": "run_1", "seq": 1}'
        buf.append(Frame(event=None, data=raw.decode(), json={"run_id": "run_1", "seq": 1}, raw=raw))
        self.assertFalse(buf.terminal)


class RunBufferAsyncTest(unittest.IsolatedAsyncioTestCase):
    async def test_finish_without_frames_sets_ready_no_hang(self):
        # Regression (review M1): an empty 200 text/event-stream must not leave
        # the client handler blocked on ready forever.
        buf = RunBuffer("s1")
        buf.finish()
        await asyncio.wait_for(buf.ready.wait(), timeout=0.5)  # must not hang
        self.assertTrue(buf.terminal)


if __name__ == "__main__":
    unittest.main()
