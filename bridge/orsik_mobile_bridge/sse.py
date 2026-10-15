"""Minimal, dependency-free Server-Sent Events decoder for the bridge.

The bridge reads the upstream Hermes session chat stream, needs to know each
event's ``seq``/``run_id`` (to buffer and replay) and must re-emit frames
byte-for-byte on reconnect. It therefore keeps each frame's raw bytes and only
parses the ``data:`` JSON for correlation — it never re-serialises upstream
content.

Parsing is total: a malformed ``data:`` line yields a frame with ``json=None``
and is still relayed, never dropped and never raised. The one deliberate
counterpart to that totality is a hard per-frame byte bound: an unterminated or
oversized frame raises :class:`SSEFrameTooLarge` instead of growing the buffer
without limit, so a hostile or broken upstream cannot exhaust memory.
"""

from __future__ import annotations

import json
from dataclasses import dataclass

MAX_FRAME_BYTES = 1024 * 1024
"""Hard upper bound for one decoded frame block, measured after CR/LF normalisation."""


class SSEFrameTooLarge(ValueError):
    """A single SSE frame block exceeded :data:`MAX_FRAME_BYTES`.

    ``frames`` carries the frames that completed before the violation in the
    same ``feed`` call so the caller can still relay them; the offending frame is
    never emitted, not even partially.
    """

    def __init__(self, limit: int, size: int, frames: list["Frame"]) -> None:
        super().__init__(f"SSE frame block is {size} bytes, limit is {limit}")
        self.limit = limit
        self.size = size
        self.frames = frames


@dataclass(frozen=True)
class Frame:
    """One decoded SSE frame.

    ``raw`` is the exact byte sequence to re-emit (including the terminating
    blank line). ``event`` is the ``event:`` line value (may be None). ``json``
    is the parsed ``data`` payload, or None if it was absent or not JSON.
    """

    event: str | None
    data: str
    json: dict | None
    raw: bytes


class SSEDecoder:
    """Incrementally decode an SSE byte stream into :class:`Frame` objects.

    ``max_frame_bytes`` bounds the accumulator: once a partial frame or a
    complete frame block exceeds it, :class:`SSEFrameTooLarge` is raised and the
    decoder is closed (``feed`` then keeps raising with ``frames == []``). It
    never resynchronises, because a skip would silently produce a stream with a
    hole.
    """

    def __init__(self, max_frame_bytes: int = MAX_FRAME_BYTES) -> None:
        self._buf = bytearray()
        self._hold = b""
        self._max = max_frame_bytes
        self._closed = False
        self._overflowed_size = 0

    def feed(self, chunk: bytes) -> list[Frame]:
        if self._closed:
            raise SSEFrameTooLarge(self._max, self._overflowed_size, [])
        if not chunk:
            return []
        data = self._hold + chunk
        self._hold = b""
        # A trailing lone CR may pair with an LF at the start of the next chunk;
        # hold it back so a CRLF split across chunks is normalised correctly.
        if data.endswith(b"\r"):
            self._hold = b"\r"
            data = data[:-1]
        # Normalise CRLF / lone CR to LF for line splitting (SSE allows all three).
        # Only the new bytes are normalised and scanned: the retained remainder is
        # already normalised and holds no blank line, so per-chunk work stays
        # proportional to the chunk instead of the (up to max_frame_bytes) buffer.
        scan_from = max(len(self._buf) - 1, 0)
        self._buf += data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        frames: list[Frame] = []
        consumed = 0
        while True:
            end = self._buf.find(b"\n\n", scan_from)
            if end < 0:
                break
            block = bytes(self._buf[consumed:end])
            consumed = scan_from = end + 2
            if len(block) > self._max:
                self._overflow(len(block), frames)
            frame = self._parse(block)
            if frame is not None:
                frames.append(frame)
        if consumed:
            del self._buf[:consumed]
        # A chunk may legitimately carry many small frames; only the still
        # incomplete remainder (a frame in progress) is measured here. A trailing
        # lone LF is the first half of the terminating blank line, not part of the
        # frame body, so it must not count toward the limit -- otherwise a frame
        # of exactly max_frame_bytes would be accepted or rejected by chunking.
        pending = len(self._buf) - (1 if self._buf.endswith(b"\n") else 0)
        if pending > self._max:
            self._overflow(pending, frames)
        return frames

    def _overflow(self, size: int, frames: list[Frame]) -> None:
        self._closed = True
        self._overflowed_size = size
        self._buf = bytearray()
        self._hold = b""
        raise SSEFrameTooLarge(self._max, size, frames)

    @staticmethod
    def _parse(block: bytes) -> Frame | None:
        event: str | None = None
        data_lines: list[str] = []
        has_data = False
        for line in block.split(b"\n"):
            text = line.decode("utf-8", "replace")
            if not text or text.startswith(":"):
                continue
            field, _, value = text.partition(":")
            if value.startswith(" "):
                value = value[1:]
            if field == "event":
                event = value
            elif field == "data":
                has_data = True
                data_lines.append(value)
        if event is None and not has_data:
            return None
        data = "\n".join(data_lines)
        parsed: dict | None = None
        if has_data:
            try:
                obj = json.loads(data)
                if isinstance(obj, dict):
                    parsed = obj
            except (ValueError, TypeError):
                parsed = None
        # Re-emit exactly one trailing blank line per frame.
        raw = block + b"\n\n"
        return Frame(event=event, data=data, json=parsed, raw=raw)
