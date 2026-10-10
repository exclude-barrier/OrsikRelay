"""Per-run SSE buffer and registry for the bridge's session-chat takeover.

The official Hermes session chat stream is a live, non-replayable SSE queue and
the run is interrupted when the *upstream* socket drops. The bridge therefore
holds the upstream socket open server-side, retains each run's frames (bounded)
and lets a reconnecting client replay from an exclusive ``after_seq`` before
following live again.

Fail-closed rules:
* A replay whose ``after_seq`` predates the oldest retained sequence is refused
  with a ``gap`` so the client never receives a stream with a hole.
* When the buffer is gone (e.g. bridge restart) the run is *not* reconstructed
  and no terminal event is fabricated; callers answer indeterminate.
"""

from __future__ import annotations

import asyncio
import time
from collections import deque

from .sse import Frame

OVERFLOW = object()
"""Sentinel pushed to a slow subscriber before it is dropped."""


class RunBuffer:
    BACKLOG_LIMIT = 1000
    SUBSCRIBER_QUEUE_LIMIT = 256

    def __init__(self, session_id: str, *, created_at: float | None = None) -> None:
        self.session_id = session_id
        self.run_id: str | None = None
        self.created_at = time.time() if created_at is None else created_at
        self.updated_at = self.created_at
        self.terminal = False
        self.backlog: deque[tuple[int, bytes]] = deque(maxlen=self.BACKLOG_LIMIT)
        self.subscribers: set[asyncio.Queue] = set()
        self.ready = asyncio.Event()
        # "pending" until the first frame or an upstream failure is observed.
        self.state = "pending"
        self.error_status = 502
        self.error_body = b""
        self.error_content_type = "application/json"

    # -- lifecycle -------------------------------------------------------------
    def append(self, frame: Frame) -> None:
        data = frame.json or {}
        seq = data.get("seq")
        if not isinstance(seq, int):
            seq = (self.backlog[-1][0] + 1) if self.backlog else 0
        run_id = data.get("run_id")
        if self.run_id is None and isinstance(run_id, str) and run_id:
            self.run_id = run_id
        self.backlog.append((seq, frame.raw))
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

    def fail(self, status: int, content_type: str, body: bytes) -> None:
        self.state = "failed"
        self.error_status = status
        self.error_body = body
        self.error_content_type = content_type or "application/json"
        self.terminal = True
        self.ready.set()
        self._fan_out(None, None)

    def finish(self) -> None:
        self.terminal = True
        self.ready.set()
        self._fan_out(None, None)

    def _fan_out(self, seq: int | None, raw: bytes | None) -> None:
        for queue in list(self.subscribers):
            try:
                queue.put_nowait((seq, raw))
            except asyncio.QueueFull:
                self.subscribers.discard(queue)
                while not queue.empty():
                    try:
                        queue.get_nowait()
                    except asyncio.QueueEmpty:
                        break
                queue.put_nowait((None, OVERFLOW))

    # -- subscription ----------------------------------------------------------
    def attach(self, after_seq: int) -> tuple[asyncio.Queue, list[tuple[int, bytes]]] | None:
        """Return ``(queue, replay)`` or None when the requested seq is too old."""
        if after_seq >= 0 and self.backlog:
            oldest = self.backlog[0][0]
            if oldest > after_seq + 1:
                return None
        replay = [(seq, raw) for seq, raw in self.backlog if seq > after_seq]
        queue: asyncio.Queue = asyncio.Queue(maxsize=self.SUBSCRIBER_QUEUE_LIMIT + len(replay))
        self.subscribers.add(queue)
        return queue, replay

    def detach(self, queue: asyncio.Queue) -> None:
        self.subscribers.discard(queue)


class RunHub:
    """Bounded registry of live/recent run buffers, keyed by run_id."""

    def __init__(self, *, max_runs: int = 256, retention_seconds: float = 600.0) -> None:
        self._by_run: dict[str, RunBuffer] = {}
        self._pending: set[RunBuffer] = set()
        self._max_runs = max_runs
        self._retention = retention_seconds

    def create_pending(self, session_id: str) -> RunBuffer:
        self._prune()
        buf = RunBuffer(session_id)
        self._pending.add(buf)
        return buf

    def register(self, buf: RunBuffer) -> None:
        self._pending.discard(buf)
        if buf.run_id:
            self._by_run[buf.run_id] = buf

    def lookup(self, session_id: str, run_id: str) -> RunBuffer | None:
        buf = self._by_run.get(run_id)
        if buf is not None and buf.session_id == session_id:
            return buf
        return None

    def _prune(self, now: float | None = None) -> None:
        now = time.time() if now is None else now
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
        # Pending buffers that never learned a run_id (e.g. an upstream that
        # failed before the first frame) would otherwise leak.
        stale = [b for b in self._pending if now - b.updated_at > self._retention]
        for buf in stale:
            self._pending.discard(buf)
