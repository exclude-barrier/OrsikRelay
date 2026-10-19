"""Minimal, dependency-free Server-Sent Events decoder for the bridge.

The bridge reads the upstream Hermes session chat stream, needs to know each
event's ``seq``/``run_id`` (to buffer and replay) and must re-emit frames
byte-for-byte on reconnect. It therefore keeps each frame's raw bytes and only
parses the ``data:`` JSON for correlation. The single deliberate exception is
:func:`redact_echo`: the core echoes the whole request (image body included) in
the first ``run.started`` frame, so that one frame is rebuilt without its
``user_message`` field; and :func:`normalize_approval` narrows ``approval.request``
to the Mobile-v1 shape. Those are the only places the bridge re-serialises
upstream content, and it never happens in this decoder itself.

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


ECHO_EVENT = "run.started"
"""Event whose ``user_message`` echo the bridge strips before relaying."""


def redact_echo(frame: Frame) -> Frame:
    """Rebuild a ``run.started`` frame without its ``user_message`` echo.

    The official core echoes the request's ``user_message`` (including the whole
    base64 image) in the first ``run.started`` frame, which alone can exceed the
    relay frame bound. Every other field is carried over unchanged and existing
    ``id:``/``event:`` lines keep their order; only the ``data:`` payload is
    re-serialised. Any other frame is returned as is, byte-identical.
    """
    payload = frame.json
    if (
        frame.event != ECHO_EVENT
        or not isinstance(payload, dict)
        or "user_message" not in payload
    ):
        return frame
    redacted = {key: value for key, value in payload.items() if key != "user_message"}
    return _rebuild(frame, redacted)


_UNUSED_FIELDS = {
    # The whole turn transcript (tool output included) rides on the terminal
    # frame; alone it can exceed the relay bound and fail a finished turn.
    "run.completed": ("messages", "usage", "runtime"),
    "run.cancelled": ("messages", "usage", "runtime"),
    "run.failed": ("messages", "usage", "runtime"),
    "assistant.completed": ("runtime",),
    # Tool previews and arguments hold commands, paths and output.
    "tool.started": ("preview", "args"),
    "tool.completed": ("preview", "args"),
    "tool.failed": ("preview", "args"),
    # Reasoning text (tool_name "_thinking") and mid-turn commentary.
    "tool.progress": ("delta",),
    "assistant.commentary": ("text",),
}
"""Payload fields a Mobile-v1 client never reads, per event."""


def strip_unused(frame: Frame) -> Frame:
    """Drop :data:`_UNUSED_FIELDS` from a frame before it is relayed.

    The phone shows tool activity without commands, arguments or output and
    never needs the transcript, so these fields are not sent at all. Frames
    without such a field are returned byte-identical.
    """
    fields = _UNUSED_FIELDS.get(frame.event or "")
    payload = frame.json
    if not fields or not isinstance(payload, dict) or not any(key in payload for key in fields):
        return frame
    return _rebuild(frame, {key: value for key, value in payload.items() if key not in fields})


APPROVAL_EVENT = "approval.request"
"""Event the bridge narrows to the Mobile-v1 approval shape."""

APPROVAL_SAFE_DESCRIPTION = "A dangerous action requires your approval."
"""The only description a Mobile-v1 client accepts (it never shows core text)."""

APPROVAL_V1_CHOICES = ["once", "deny"]
"""The choices the bridge actually accepts on ``POST /v1/runs/{id}/approval``."""

_APPROVAL_V1_COPIED = ("session_id", "run_id", "seq", "ts", "request_id")


def normalize_approval(frame: Frame) -> Frame:
    """Narrow a core ``approval.request`` frame to the Mobile-v1 shape.

    The official core advertises ``choices = [once, session, always, deny]`` and
    adds the command, pattern keys, permanence flags and its own description.
    The bridge refuses everything but ``once``/``deny`` locally, so it also
    advertises exactly those, with the fixed v1 description, and drops every
    other field (the command text never reaches the phone). A frame missing a
    v1 field is relayed unchanged: the client then rejects it closed, the bridge
    never invents a value.
    """
    payload = frame.json
    if frame.event != APPROVAL_EVENT or not isinstance(payload, dict):
        return frame
    if not all(key in payload for key in _APPROVAL_V1_COPIED):
        return frame
    narrowed = {key: payload[key] for key in _APPROVAL_V1_COPIED}
    narrowed["description"] = APPROVAL_SAFE_DESCRIPTION
    narrowed["choices"] = list(APPROVAL_V1_CHOICES)
    return _rebuild(frame, narrowed)


def _rebuild(frame: Frame, payload: dict) -> Frame:
    """Re-serialise ``payload`` as the frame's single ``data:`` line.

    ``id:``/``event:`` lines and their order are kept; only the ``data:``
    payload is replaced.
    """
    data = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    data_line = b"data: " + data.encode("utf-8")
    rebuilt: list[bytes] = []
    replaced = False
    for line in frame.raw.rstrip(b"\n").split(b"\n"):
        if not line.startswith(b"data:"):
            rebuilt.append(line)
        elif not replaced:
            rebuilt.append(data_line)
            replaced = True
    if not replaced:
        rebuilt.append(data_line)
    return Frame(
        event=frame.event, data=data, json=payload, raw=b"\n".join(rebuilt) + b"\n\n"
    )


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
