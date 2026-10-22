"""Per-run SSE buffer and registry for the bridge's session-chat takeover.

The official Hermes session chat stream is a live, non-replayable SSE queue and
the run is interrupted when the *upstream* socket drops. The bridge therefore
holds the upstream socket open server-side, retains each run's frames (bounded
in count *and* bytes) and lets a reconnecting client replay from an exclusive
``after_seq`` before following live again.

Fail-closed rules:
* A replay whose ``after_seq`` predates the oldest retained sequence is refused
  with a ``gap`` so the client never receives a stream with a hole.
* When the buffer is gone (e.g. bridge restart) the run is *not* reconstructed
  and no terminal event is fabricated; callers answer indeterminate.

Memory bounds: every accumulator is capped. The backlog is bounded by frame
count and by total bytes; each subscriber queue is bounded by item count and by
bytes; a run admits a bounded number of subscribers; the hub bounds the number of
active runs and the total bytes retained for finished runs. Backpressure is
drop-not-block: a slow subscriber is dropped with a single ``OVERFLOW`` marker
rather than stalling the single event loop that serves the upstream pump and all
other subscribers.
"""

from __future__ import annotations

import asyncio
import time
from collections import deque

from .sse import Frame

OVERFLOW = object()
"""Sentinel pushed to a slow subscriber before it is dropped."""

MAX_ACTIVE_RUNS = 16
"""Maximum concurrent (non-terminal) run buffers; further chat POSTs get 503."""

HUB_MAX_RETAINED_BYTES = 64 * 1024 * 1024
"""Total backlog bytes retained across terminal runs before oldest are evicted."""


class RunHubBusy(RuntimeError):
    """Raised by :meth:`RunHub.create_pending` when the active-run cap is reached."""


class Subscriber:
    """A bounded per-subscriber queue with byte accounting and an async getter."""

    def __init__(self, max_items: int, max_bytes: int) -> None:
        self._queue: asyncio.Queue = asyncio.Queue(maxsize=max_items)
        self._max_bytes = max_bytes
        self.queued_bytes = 0

    def put(self, item: tuple[int | None, bytes | object | None]) -> bool:
        """Enqueue unless the item or byte cap would be exceeded; never blocks."""
        if self._queue.full():
            return False
        _seq, raw = item
        size = len(raw) if isinstance(raw, bytes) else 0
        if size and self.queued_bytes + size > self._max_bytes:
            return False
        self._queue.put_nowait(item)
        self.queued_bytes += size
        return True

    def drain(self) -> None:
        while not self._queue.empty():
            try:
                self._queue.get_nowait()
            except asyncio.QueueEmpty:
                break
        self.queued_bytes = 0

    async def get(self) -> tuple[int | None, bytes | object | None]:
        item = await self._queue.get()
        _seq, raw = item
        if isinstance(raw, bytes):
            self.queued_bytes -= len(raw)
        return item

    def get_nowait(self) -> tuple[int | None, bytes | object | None]:
        item = self._queue.get_nowait()
        _seq, raw = item
        if isinstance(raw, bytes):
            self.queued_bytes -= len(raw)
        return item

    def empty(self) -> bool:
        return self._queue.empty()


class RunBuffer:
    BACKLOG_LIMIT = 1000
    BACKLOG_MAX_BYTES = 4 * 1024 * 1024
    SUBSCRIBER_QUEUE_LIMIT = 256
    SUBSCRIBER_QUEUE_MAX_BYTES = 2 * 1024 * 1024
    MAX_SUBSCRIBERS_PER_RUN = 4

    def __init__(self, session_id: str, *, created_at: float | None = None) -> None:
        self.session_id = session_id
        self.run_id: str | None = None
        self.created_at = time.time() if created_at is None else created_at
        self.updated_at = self.created_at
        self.terminal = False
        self.backlog: deque[tuple[int, bytes]] = deque()
        self.backlog_bytes = 0
        # Highest seq evicted from the left; a replay at or below it would leave
        # a hole, so attach() must refuse it (never fabricate a gap-free stream).
        self.evicted_through: int | None = None
        # Ordered by insertion so the oldest subscriber can be evicted first.
        self.subscribers: dict[Subscriber, None] = {}
        self.ready = asyncio.Event()
        # "pending" until the first frame or an upstream failure is observed.
        self.state = "pending"
        self.error_status = 502
        self.error_body = b""
        self.error_content_type = "application/json"
        # Set by the pump when the stream aborted; the client stream emits a
        # trailing ``: bridge_<reason>`` comment before EOF.
        self.abort_reason: str | None = None

    # -- lifecycle -------------------------------------------------------------
    def append(self, frame: Frame) -> None:
        data = frame.json or {}
        seq = data.get("seq")
        if not isinstance(seq, int):
            seq = (self.backlog[-1][0] + 1) if self.backlog else 0
        run_id = data.get("run_id")
        if self.run_id is None and isinstance(run_id, str) and run_id:
            self.run_id = run_id
        self._push_backlog(seq, frame.raw)
        self.updated_at = time.time()
        if self.state == "pending":
            self.state = "streaming"
            self.ready.set()
        self._fan_out(seq, frame.raw)
        # Only the explicit ``done`` event is a terminal marker. A data-only
        # frame (event is None) is a regular SSE message and must NOT end the
        # run; stream EOF is handled by finish() from the pump's finally.
        if frame.event == "done":
            self.finish()

    def _push_backlog(self, seq: int, raw: bytes) -> None:
        self.backlog.append((seq, raw))
        self.backlog_bytes += len(raw)
        # Evict from the left while either bound is exceeded; the newest frame is
        # always kept, so a single frame larger than the byte cap still streams.
        while (len(self.backlog) > self.BACKLOG_LIMIT
               or self.backlog_bytes > self.BACKLOG_MAX_BYTES) and len(self.backlog) > 1:
            old_seq, old_raw = self.backlog.popleft()
            self.backlog_bytes -= len(old_raw)
            # The highest seq ever evicted: an upstream with a decreasing
            # seq must never lower it and hide a gap.
            self.evicted_through = old_seq if self.evicted_through is None else max(self.evicted_through, old_seq)

    def fail(self, status: int, content_type: str, body: bytes) -> None:
        self.state = "failed"
        self.error_status = status
        self.error_body = body
        # Only the media type: aiohttp refuses parameters such as "; charset=utf-8"
        # in content_type, which turned every relayed core refusal into a 500.
        self.error_content_type = (content_type or "").split(";")[0].strip() or "application/json"
        self.terminal = True
        self.ready.set()
        self._fan_out(None, None)

    def finish(self) -> None:
        self.terminal = True
        self.ready.set()
        self._fan_out(None, None)

    def _fan_out(self, seq: int | None, raw: bytes | None) -> None:
        for subscriber in list(self.subscribers):
            if not subscriber.put((seq, raw)):
                self.subscribers.pop(subscriber, None)
                subscriber.drain()
                subscriber.put((None, OVERFLOW))

    # -- subscription ----------------------------------------------------------
    def attach(self, after_seq: int) -> tuple[Subscriber, list[tuple[int, bytes]]] | None:
        """Return ``(subscriber, replay)`` or None when the requested seq is too old."""
        if self.evicted_through is not None and after_seq < self.evicted_through:
            return None
        if len(self.subscribers) >= self.MAX_SUBSCRIBERS_PER_RUN:
            oldest = next(iter(self.subscribers))
            self.subscribers.pop(oldest, None)
            oldest.drain()
            oldest.put((None, OVERFLOW))
        replay = [(seq, raw) for seq, raw in self.backlog if seq > after_seq]
        subscriber = Subscriber(self.SUBSCRIBER_QUEUE_LIMIT + len(replay),
                                self.SUBSCRIBER_QUEUE_MAX_BYTES)
        self.subscribers[subscriber] = None
        return subscriber, replay

    def detach(self, subscriber: Subscriber) -> None:
        self.subscribers.pop(subscriber, None)


class RunHub:
    """Bounded registry of live/recent run buffers, keyed by run_id."""

    def __init__(
        self,
        *,
        max_runs: int = 256,
        retention_seconds: float = 600.0,
        max_active_runs: int = MAX_ACTIVE_RUNS,
        max_retained_bytes: int = HUB_MAX_RETAINED_BYTES,
    ) -> None:
        self._by_run: dict[str, RunBuffer] = {}
        self._pending: set[RunBuffer] = set()
        self._max_runs = max_runs
        self._retention = retention_seconds
        self._max_active_runs = max_active_runs
        self._max_retained_bytes = max_retained_bytes

    def create_pending(self, session_id: str) -> RunBuffer:
        self._prune()
        active = sum(1 for b in self._pending if not b.terminal)
        active += sum(1 for b in self._by_run.values() if not b.terminal)
        if active >= self._max_active_runs:
            raise RunHubBusy(f"active run cap reached ({active} >= {self._max_active_runs})")
        buf = RunBuffer(session_id)
        self._pending.add(buf)
        return buf

    def register(self, buf: RunBuffer) -> None:
        self._pending.discard(buf)
        if buf.run_id:
            # The pump calls register() once per frame; prune only when a new run
            # is actually admitted so the hot path stays O(1) per frame.
            if self._by_run.get(buf.run_id) is not buf:
                self._by_run[buf.run_id] = buf
                self._prune()
            return
        self._prune()

    def lookup(self, session_id: str, run_id: str) -> RunBuffer | None:
        buf = self._by_run.get(run_id)
        if buf is not None and buf.session_id == session_id:
            return buf
        return None

    def belongs_to_other_session(self, session_id: str, run_id: str) -> bool:
        """True when this bridge relays [run_id] for a different session."""
        buf = self._by_run.get(run_id)
        return buf is not None and buf.session_id != session_id

    def _prune(self, now: float | None = None) -> None:
        now = time.time() if now is None else now
        # Terminal pending buffers (a run that failed before it ever learned a
        # run_id) must not linger for the whole retention window.
        for buf in list(self._pending):
            if buf.terminal:
                self._pending.discard(buf)
        for run_id, buf in list(self._by_run.items()):
            if buf.terminal and now - buf.updated_at > self._retention:
                self._by_run.pop(run_id, None)
        # Bound total retained runs: evict the oldest terminal buffers first, then
        # (only if still over the hard cap) the oldest buffer at all — memory is a
        # security bound; a dropped live buffer degrades to a client re-attach gap.
        if len(self._by_run) > self._max_runs:
            ordered = sorted(self._by_run.items(), key=lambda kv: kv[1].updated_at)
            for run_id, buf in ordered:
                if len(self._by_run) <= self._max_runs:
                    break
                if buf.terminal:
                    self._by_run.pop(run_id, None)
            for run_id, _ in ordered:
                if len(self._by_run) <= self._max_runs:
                    break
                self._by_run.pop(run_id, None)
        # Bound the bytes retained for finished runs; live buffers are bounded by
        # max_active_runs * BACKLOG_MAX_BYTES and are never evicted for bytes.
        retained = sum(b.backlog_bytes for b in self._by_run.values() if b.terminal)
        if retained > self._max_retained_bytes:
            for run_id, buf in sorted(self._by_run.items(), key=lambda kv: kv[1].updated_at):
                if retained <= self._max_retained_bytes:
                    break
                if buf.terminal:
                    retained -= buf.backlog_bytes
                    self._by_run.pop(run_id, None)
        # Pending buffers that never learned a run_id (e.g. an upstream that
        # failed before the first frame) would otherwise leak.
        stale = [b for b in self._pending if now - b.updated_at > self._retention]
        for buf in stale:
            self._pending.discard(buf)
