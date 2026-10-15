"""Byte and backpressure bounds for sse.py/runhub.py (CFA-69).

The tests pin the contract of the bounded bridge buffers: constant names and
defaults, error types, the OVERFLOW sentinel and the memory invariants. They run
headless with stdlib + orsik_mobile_bridge only (no new dependency).

Coverage by class:
- Decoder:   SSEFrameTooLarge, exc.frames, exact limit / +1, closed decoder,
  measurement after CR/LF normalisation.
- RunBuffer: byte eviction incl. the exact boundary and a single frame above the
  byte cap, boundedness over many frames, evicted_through / gap rule,
  subscriber overflow by count and by bytes incl. the exact boundary,
  MAX_SUBSCRIBERS_PER_RUN, done/finish fan-out.
- RunHub:    RunHubBusy admission and max_retained_bytes pruning of terminal
  buffers.

The HTTP behaviour (503 bridge_busy, 502 upstream_error_too_large /
upstream_frame_too_large, trailing abort comment) is covered in test_server.py.

The 1 MiB frame bound is a provisional default; the tests therefore check
invariants and behaviour with overridden limits and only enforce
MAX_FRAME_BYTES >= 1 MiB and BACKLOG_MAX_BYTES >= MAX_FRAME_BYTES.
"""

import json
import unittest

from orsik_mobile_bridge import runhub, sse
from orsik_mobile_bridge.runhub import OVERFLOW, RunBuffer, RunHub
from orsik_mobile_bridge.sse import Frame, SSEDecoder

MiB = 1024 * 1024


def _raw(seq, pad=0, event="assistant.delta", run_id="run_1"):
    """One complete SSE frame block (without the terminating blank line)."""
    payload = {"session_id": "s1", "run_id": run_id, "seq": seq, "pad": "x" * pad}
    return f"event: {event}\ndata: {json.dumps(payload)}".encode()


def _block(seq, pad=0, event="assistant.delta", run_id="run_1"):
    return _raw(seq, pad, event, run_id) + b"\n\n"


def _frame(seq, pad=0, event="assistant.delta", run_id="run_1"):
    payload = {"session_id": "s1", "run_id": run_id, "seq": seq, "pad": "x" * pad}
    return Frame(
        event=event,
        data=json.dumps(payload),
        json=payload,
        raw=_block(seq, pad, event, run_id),
    )


def _append(buf, seq, pad=0, event="assistant.delta", run_id="run_1"):
    buf.append(_frame(seq, pad, event, run_id))


def _drain(sub):
    """Collect everything currently queued on a subscriber (queue or Subscriber)."""
    out = []
    while not sub.empty():
        out.append(sub.get_nowait())
    return out


def _attach(buf, after_seq):
    """attach() for fixture frames that can never hit the gap rule; fails loudly
    if the implementation returns None against the contract."""
    attached = buf.attach(after_seq)
    if attached is None:
        raise AssertionError(f"attach({after_seq}) unexpectedly returned None")
    return attached


# Raw size of a frame with a single-digit seq (1..9); the boundary fixtures use
# single-digit seqs throughout so the byte arithmetic is exact.
FRAME_SIZE = len(_block(1))


class SmallBuffer(RunBuffer):
    """RunBuffer with small, test-friendly limits (class attributes are
    overridable by design)."""

    BACKLOG_LIMIT = 1000
    BACKLOG_MAX_BYTES = 256
    SUBSCRIBER_QUEUE_LIMIT = 2
    SUBSCRIBER_QUEUE_MAX_BYTES = 150
    MAX_SUBSCRIBERS_PER_RUN = 4


class CountOverflowBuffer(RunBuffer):
    """Overflow deliberately by COUNT: the byte cap is so large that it can never
    trigger before the count cap."""

    BACKLOG_LIMIT = 1000
    BACKLOG_MAX_BYTES = 4096
    SUBSCRIBER_QUEUE_LIMIT = 2
    SUBSCRIBER_QUEUE_MAX_BYTES = 4096
    MAX_SUBSCRIBERS_PER_RUN = 4


class BoundaryBuffer(RunBuffer):
    """Limits as a multiple of the frame size so the exact case is testable:
    limit reached exactly -> no error, +1 frame -> defined outcome."""

    BACKLOG_LIMIT = 1000
    BACKLOG_MAX_BYTES = 2 * FRAME_SIZE
    SUBSCRIBER_QUEUE_LIMIT = 100
    SUBSCRIBER_QUEUE_MAX_BYTES = 2 * FRAME_SIZE
    MAX_SUBSCRIBERS_PER_RUN = 4


class ManyFramesBuffer(RunBuffer):
    """Long run over hundreds of frames: backlog and subscriber queue must stay
    bounded after EVERY frame, not just at the end."""

    BACKLOG_LIMIT = 50
    BACKLOG_MAX_BYTES = 4096
    SUBSCRIBER_QUEUE_LIMIT = 8
    SUBSCRIBER_QUEUE_MAX_BYTES = 1024
    MAX_SUBSCRIBERS_PER_RUN = 4


class TinyBytesBuffer(RunBuffer):
    """Byte cap smaller than one frame: the newest frame is still kept and
    delivered (runhub.py: 'a single frame larger than the byte cap still
    streams')."""

    BACKLOG_LIMIT = 1000
    BACKLOG_MAX_BYTES = 100
    SUBSCRIBER_QUEUE_LIMIT = 10
    SUBSCRIBER_QUEUE_MAX_BYTES = 4096
    MAX_SUBSCRIBERS_PER_RUN = 4


class ConstantsContractTest(unittest.TestCase):
    """Contract: constant names, default values and invariants."""

    def test_names_and_defaults_exist(self):
        self.assertGreaterEqual(sse.MAX_FRAME_BYTES, MiB)  # 1 MiB, provisional
        self.assertEqual(RunBuffer.BACKLOG_LIMIT, 1000)  # unchanged
        self.assertEqual(RunBuffer.BACKLOG_MAX_BYTES, 4 * MiB)
        self.assertEqual(RunBuffer.SUBSCRIBER_QUEUE_LIMIT, 256)  # unchanged
        self.assertEqual(RunBuffer.SUBSCRIBER_QUEUE_MAX_BYTES, 2 * MiB)
        self.assertEqual(RunBuffer.MAX_SUBSCRIBERS_PER_RUN, 4)
        self.assertEqual(runhub.MAX_ACTIVE_RUNS, 16)
        self.assertEqual(runhub.HUB_MAX_RETAINED_BYTES, 64 * MiB)

    def test_invariant_backlog_ge_frame(self):
        self.assertGreaterEqual(RunBuffer.BACKLOG_MAX_BYTES, sse.MAX_FRAME_BYTES)

    def test_error_types(self):
        self.assertTrue(issubclass(sse.SSEFrameTooLarge, ValueError))
        self.assertTrue(issubclass(runhub.RunHubBusy, RuntimeError))


class SSEDecoderLimitTest(unittest.TestCase):
    """SSEFrameTooLarge per frame block, measured after CR/LF normalisation."""

    LIMIT = 200

    def _decoder(self):
        return SSEDecoder(max_frame_bytes=self.LIMIT)

    # 1 ------------------------------------------------------------------
    def test_partial_feed_over_limit_raises(self):
        dec = self._decoder()
        head = _raw(1, pad=self.LIMIT - 40)  # block is limit+49 bytes in total
        out = dec.feed(head[:40])  # head only, the rest stays in _buf
        self.assertEqual(out, [])
        with self.assertRaises(sse.SSEFrameTooLarge) as cm:
            dec.feed(head[40:40 + self.LIMIT])  # incomplete remainder > limit
        exc = cm.exception
        self.assertGreater(exc.size, exc.limit)
        self.assertEqual(exc.frames, [])

    # 2 ------------------------------------------------------------------
    def test_complete_frames_before_overflow_are_kept(self):
        dec = self._decoder()
        small = _block(1, pad=10) + _block(2, pad=10)
        tail = _raw(3, pad=self.LIMIT + 50)[: self.LIMIT + 10]  # incomplete, > limit
        with self.assertRaises(sse.SSEFrameTooLarge) as cm:
            dec.feed(small + tail)
        exc = cm.exception
        self.assertEqual([f.json["seq"] for f in exc.frames], [1, 2])
        self.assertGreater(exc.size, exc.limit)

    # 3 ------------------------------------------------------------------
    def test_chunk_larger_than_limit_of_small_frames_is_fine(self):
        dec = self._decoder()
        chunk = b"".join(_block(seq, pad=30) for seq in range(1, 6))
        self.assertGreater(len(chunk), self.LIMIT)
        frames = dec.feed(chunk)  # every single frame is within the limit
        self.assertEqual([f.json["seq"] for f in frames], [1, 2, 3, 4, 5])

    # 4 ------------------------------------------------------------------
    def test_exact_limit_allowed_limit_plus_one_raises(self):
        base = _raw(1)
        pad_exact = self.LIMIT - len(base)
        exact = _block(1, pad=pad_exact)
        self.assertEqual(len(exact) - 2, self.LIMIT)  # block without blank line is exactly the limit
        frames = self._decoder().feed(exact)
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].raw, exact)

        over = _block(2, pad=pad_exact + 1)
        self.assertEqual(len(over) - 2, self.LIMIT + 1)
        with self.assertRaises(sse.SSEFrameTooLarge):
            self._decoder().feed(over)

    # 5 ------------------------------------------------------------------
    def test_decoder_is_closed_after_overflow(self):
        dec = self._decoder()
        with self.assertRaises(sse.SSEFrameTooLarge):
            dec.feed(_raw(1, pad=self.LIMIT + 10))
        for _ in range(2):
            with self.assertRaises(sse.SSEFrameTooLarge) as cm:
                dec.feed(_block(2, pad=5))  # a valid frame afterwards: still closed
            self.assertEqual(cm.exception.frames, [])

    # 6 ------------------------------------------------------------------
    def test_crlf_measured_after_normalisation(self):
        base = _raw(1)
        pad = self.LIMIT - len(base)
        lf = _block(1, pad=pad)
        crlf = lf.replace(b"\n", b"\r\n")  # normalises to the same length as lf
        # The block is measured after normalisation: crlf has 3 CRLFs,
        # normalisation removes 3 CR bytes; the blank line (CRLFCRLF, 4 bytes)
        # is not part of the block. Block = 205 - 3 - 2 = 200 = LIMIT.
        self.assertEqual(len(crlf) - 5, self.LIMIT)
        # CRLF split across a chunk boundary: \r|\n is not counted twice.
        cut = crlf.index(b"\r\n") + 1
        dec = self._decoder()
        frames = dec.feed(crlf[:cut]) + dec.feed(crlf[cut:])
        self.assertEqual(len(frames), 1)
        self.assertEqual(frames[0].json["seq"], 1)
        # +1 byte after normalisation -> raises, also across a chunk boundary.
        # (A complete CRLF block with pad+1, i.e. 201 normalised, so the
        # negative half really checks "+1 byte", not an unterminated remainder.)
        over = _block(2, pad=pad + 1).replace(b"\n", b"\r\n")
        self.assertEqual(len(over) - 5, self.LIMIT + 1)
        dec2 = self._decoder()
        cut2 = over.index(b"\r\n") + 1
        self.assertEqual(dec2.feed(over[:cut2]), [])  # first part: no error yet
        with self.assertRaises(sse.SSEFrameTooLarge):
            dec2.feed(over[cut2:])


class BigBytesBuffer(SmallBuffer):
    """SmallBuffer with a high byte cap: forces no eviction."""

    BACKLOG_MAX_BYTES = 4096


class RunBufferByteEvictionTest(unittest.TestCase):
    """Byte eviction, evicted_through and the gap rule."""

    # 7 ------------------------------------------------------------------
    def test_byte_eviction_bounds_backlog(self):
        buf = SmallBuffer("s1")
        size = len(_block(1))
        n_fill = buf.BACKLOG_MAX_BYTES // size + 2  # well above the byte cap
        for seq in range(1, n_fill + 1):
            _append(buf, seq)
        self.assertLessEqual(buf.backlog_bytes, buf.BACKLOG_MAX_BYTES)
        self.assertGreater(buf.backlog_bytes, 0)
        # The newest frame is always kept.
        self.assertEqual(buf.backlog[-1][0], n_fill)
        # Eviction is recorded: evicted_through = seq of the last evicted
        # frame (contiguously sequenced in this test).
        self.assertIsNotNone(buf.evicted_through)
        self.assertEqual(buf.evicted_through, buf.backlog[0][0] - 1)
        self.assertLess(len(buf.backlog), n_fill)  # something was evicted

    # 7b --------------------------------------------------------------
    def test_backlog_exact_boundary_no_eviction_plus_one_evicts_one(self):
        # BACKLOG_MAX_BYTES = 2 * frame size: reaching the limit exactly does
        # NOT evict (swapping > for >= in _push_backlog would show here); only
        # the next frame evicts exactly one.
        buf = BoundaryBuffer("s1")
        size = len(_block(1))
        self.assertEqual(buf.BACKLOG_MAX_BYTES, 2 * size)
        _append(buf, 1)
        _append(buf, 2)
        self.assertIsNone(buf.evicted_through)  # exactly at the limit: no eviction
        self.assertEqual(buf.backlog_bytes, buf.BACKLOG_MAX_BYTES)
        self.assertEqual([s for s, _ in buf.backlog], [1, 2])
        _append(buf, 3)  # +1 frame over the limit
        self.assertEqual(buf.evicted_through, 1)  # exactly one evicted
        self.assertEqual([s for s, _ in buf.backlog], [2, 3])
        self.assertEqual(buf.backlog_bytes, 2 * size)

    # 7c --------------------------------------------------------------
    def test_backlog_stays_bounded_over_many_frames(self):
        # No memory growth beyond the bound: invariants after EVERY frame over
        # hundreds of frames with varying pad size, not just at the end.
        buf = ManyFramesBuffer("s1")
        slow, _ = _attach(buf, -1)  # never reads: its queue must not grow without bound
        n = 300
        for seq in range(1, n + 1):
            pad = seq % 100  # varying frame size (all below the byte cap)
            _append(buf, seq, pad=pad)
            self.assertLessEqual(buf.backlog_bytes, buf.BACKLOG_MAX_BYTES)
            self.assertEqual(buf.backlog_bytes,
                             sum(len(raw) for _, raw in buf.backlog))
            self.assertLessEqual(len(buf.backlog), buf.BACKLOG_LIMIT)
            if slow in buf.subscribers:
                self.assertLessEqual(slow.queued_bytes,
                                     buf.SUBSCRIBER_QUEUE_MAX_BYTES)
            else:
                # Dropped subscribers are drained; only the OVERFLOW sentinel
                # remains (size 0), the queue never grows again.
                self.assertEqual(slow.queued_bytes, 0)
        self.assertIsNotNone(buf.evicted_through)  # eviction happened continuously
        self.assertEqual(buf.backlog[-1][0], n)  # the newest frame is kept
        self.assertNotIn(slow, buf.subscribers)  # slow reader dropped deterministically
        self.assertEqual(_drain(slow), [(None, OVERFLOW)])

    # 7d --------------------------------------------------------------
    def test_single_frame_over_byte_cap_is_kept_and_delivered(self):
        # "The newest frame is kept": a single frame above BACKLOG_MAX_BYTES
        # stays as the only backlog entry (_push_backlog, condition
        # len(backlog) > 1) and is delivered to subscribers.
        buf = TinyBytesBuffer("s1")
        sub, _ = _attach(buf, -1)
        pad = 200  # frame well above the byte cap of 100
        _append(buf, 1, pad=pad)
        size1 = len(_block(1, pad=pad))
        self.assertGreater(size1, buf.BACKLOG_MAX_BYTES)
        self.assertEqual([s for s, _ in buf.backlog], [1])
        self.assertEqual(buf.backlog_bytes, size1)
        self.assertIsNone(buf.evicted_through)
        self.assertEqual([s for s, _ in _drain(sub)], [1])  # delivered
        # Second large frame: the older one goes, the newest stays.
        _append(buf, 2, pad=pad)
        self.assertEqual([s for s, _ in buf.backlog], [2])
        self.assertEqual(buf.evicted_through, 1)

    # 8 ------------------------------------------------------------------
    def test_attach_after_eviction_gap_rule(self):
        buf = SmallBuffer("s1")
        for seq in range(1, 8):
            _append(buf, seq)
        self.assertIsNotNone(buf.evicted_through)
        ev = buf.evicted_through
        # Re-attach without after_seq on an evicted backlog: None (fail closed).
        self.assertIsNone(buf.attach(-1))
        # attach(evicted_through - 1): hole -> None.
        self.assertIsNone(buf.attach(ev - 1))
        # attach(evicted_through): replay from the next frame.
        attached = buf.attach(ev)
        if attached is None:
            self.fail("attach(evicted_through) must return (subscriber, replay)")
        sub, replay = attached
        self.assertEqual([s for s, _ in replay], [s for s, _ in buf.backlog if s > ev])
        self.assertTrue(replay and replay[0][0] == ev + 1)
        buf.detach(sub)
        # attach(evicted_through) is also the lowest permitted bound: the
        # oldest retained frame is ev+1, so attach(oldest - 1) is the same case,
        # not a separate path.
        oldest = buf.backlog[0][0]
        self.assertEqual(oldest, ev + 1)

    # 9 ------------------------------------------------------------------
    def test_attach_minus_one_without_eviction_is_full_backlog(self):
        # Regression: without eviction attach(-1) still returns the whole
        # backlog. Generous byte cap so 3 frames always fit.
        buf = BigBytesBuffer("s1")
        for seq in (1, 2, 3):
            _append(buf, seq)
        self.assertIsNone(buf.evicted_through)
        attached = buf.attach(-1)
        if attached is None:
            self.fail("attach(-1) without eviction must return (subscriber, replay)")
        _, replay = attached
        self.assertEqual([s for s, _ in replay], [1, 2, 3])
        self.assertLessEqual(buf.backlog_bytes, buf.BACKLOG_MAX_BYTES)


class RunBufferBackpressureTest(unittest.TestCase):
    """Deterministic dropping, the OVERFLOW sentinel and fan-out."""

    # 10a -------------------------------------------------------------
    def test_subscriber_overflow_by_count(self):
        # Count overflow with a large byte cap: the bound must really be the
        # queue capacity and must not be triggered earlier by bytes (SmallBuffer
        # would drop at frame 2 because 182 > 150 bytes).
        buf = CountOverflowBuffer("s1")
        slow, _ = _attach(buf, -1)  # queue capacity SUBSCRIBER_QUEUE_LIMIT + 0
        fast, _ = _attach(buf, -1)
        fast_seen = []
        limit = buf.SUBSCRIBER_QUEUE_LIMIT
        for seq in range(1, limit + 1):  # fill the capacity exactly
            _append(buf, seq)  # never blocks (synchronous path)
            fast_seen += [s for s, _ in _drain(fast)]  # fast keeps up
        # Exactly at the count cap: slow is still attached (queue full but ok).
        self.assertIn(slow, buf.subscribers)
        _append(buf, limit + 1)  # one frame over capacity
        fast_seen += [s for s, _ in _drain(fast)]
        self.assertNotIn(slow, buf.subscribers)
        self.assertEqual(_drain(slow), [(None, OVERFLOW)])
        # The other subscriber is unaffected: every frame delivered.
        self.assertEqual(fast_seen, list(range(1, limit + 2)))

    # 10b ----------------------------------------------------------------
    def test_subscriber_overflow_by_bytes(self):
        buf = SmallBuffer("s1")
        sub, _ = _attach(buf, -1)
        size = len(_block(1))  # raw size of one frame
        _append(buf, 1)
        self.assertLessEqual(sub.queued_bytes, buf.SUBSCRIBER_QUEUE_MAX_BYTES)
        self.assertGreater(sub.queued_bytes, 0)
        n_over = buf.SUBSCRIBER_QUEUE_MAX_BYTES // size + 1
        for seq in range(2, n_over + 1):
            _append(buf, seq)
        self.assertNotIn(sub, buf.subscribers)
        self.assertEqual(_drain(sub), [(None, OVERFLOW)])

    # 10c -------------------------------------------------------------
    def test_subscriber_bytes_exact_boundary_keeps_plus_one_drops(self):
        # SUBSCRIBER_QUEUE_MAX_BYTES = 2 * frame size, large queue capacity:
        # reaching the byte cap exactly keeps the subscriber (swapping > for >=
        # in Subscriber.put would show here), +1 frame drops it.
        buf = BoundaryBuffer("s1")
        size = len(_block(1))
        self.assertEqual(buf.SUBSCRIBER_QUEUE_MAX_BYTES, 2 * size)
        sub, _ = _attach(buf, -1)
        _append(buf, 1)
        _append(buf, 2)
        self.assertIn(sub, buf.subscribers)  # exactly at the limit: stays
        self.assertEqual(sub.queued_bytes, buf.SUBSCRIBER_QUEUE_MAX_BYTES)
        _append(buf, 3)  # +1 frame over the limit
        self.assertNotIn(sub, buf.subscribers)
        self.assertEqual(_drain(sub), [(None, OVERFLOW)])

    # 11 -----------------------------------------------------------------
    def test_fifth_attach_evicts_oldest_with_overflow(self):
        buf = SmallBuffer("s1")
        subs = [_attach(buf, -1)[0] for _ in range(buf.MAX_SUBSCRIBERS_PER_RUN)]
        oldest = subs[0]
        fifth = _attach(buf, -1)
        self.assertNotIn(oldest, buf.subscribers)
        self.assertEqual(_drain(oldest), [(None, OVERFLOW)])
        self.assertEqual(len(buf.subscribers), buf.MAX_SUBSCRIBERS_PER_RUN)
        for sub in subs[1:] + [fifth[0]]:
            self.assertIn(sub, buf.subscribers)

    # 12 -----------------------------------------------------------------
    def test_done_and_finish_wake_all_subscribers(self):
        # done event: frame + (None, None); finish(): (None, None) to everyone.
        # Uses the default limits (256) so no capacity overflow distorts this
        # regression test.
        buf = RunBuffer("s1")
        a, _ = _attach(buf, -1)
        b, _ = _attach(buf, -1)
        _append(buf, 1)
        _append(buf, 2, event="done")
        for sub in (a, b):
            got = _drain(sub)
            self.assertEqual([s for s, _ in got[:2]], [1, 2])
            self.assertIn((None, None), got)
        self.assertTrue(buf.terminal)
        c, _ = _attach(buf, 2)
        buf.finish()
        self.assertEqual(_drain(c)[-1], (None, None))


class RunHubAdmissionTest(unittest.TestCase):
    """RunHubBusy admission and retained-bytes pruning."""

    # 13 -----------------------------------------------------------------
    def test_create_pending_busy_then_room_after_finish(self):
        hub = RunHub(max_active_runs=2)
        b1 = hub.create_pending("s1")
        hub.create_pending("s2")
        with self.assertRaises(runhub.RunHubBusy):
            hub.create_pending("s3")
        b1.finish()  # terminal -> no longer active
        b3 = hub.create_pending("s3")
        self.assertIsNotNone(b3)

    # 13b ----------------------------------------------------------------
    def test_registered_live_buffers_count_as_active(self):
        hub = RunHub(max_active_runs=2)
        b1 = hub.create_pending("s1")
        _append(b1, 1, run_id="ra")
        hub.register(b1)  # live, registered
        b2 = hub.create_pending("s2")
        with self.assertRaises(runhub.RunHubBusy):
            hub.create_pending("s3")
        b2.finish()  # terminal pending buffers are removed at once
        b3 = hub.create_pending("s3")
        self.assertIsNotNone(b3)

    # 14 -----------------------------------------------------------------
    def test_retained_bytes_prunes_oldest_terminal_first(self):
        hub = RunHub(
            max_runs=50,
            retention_seconds=10_000_000,
            max_active_runs=100,
            max_retained_bytes=300,
        )
        # Two terminal buffers with > 150 backlog bytes each, plus a live one.
        a = hub.create_pending("s1")
        _append(a, 1, run_id="ra")
        _append(a, 2, run_id="ra")
        hub.register(a)
        a.finish()
        b = hub.create_pending("s1")
        _append(b, 3, run_id="rb")
        _append(b, 4, run_id="rb")
        hub.register(b)
        b.finish()
        live = hub.create_pending("s1")
        _append(live, 5, run_id="rl")
        hub.register(live)
        # _prune (via create_pending/register) must bound terminal bytes.
        hub.create_pending("s1")
        self.assertIsNone(hub.lookup("s1", "ra"))  # oldest terminal first
        self.assertIsNotNone(hub.lookup("s1", "rb"))
        self.assertIsNotNone(hub.lookup("s1", "rl"))  # live buffers stay


if __name__ == "__main__":
    unittest.main()
