"""Minimal, dependency-free Server-Sent Events decoder for the bridge.

The bridge reads the upstream Hermes session chat stream, needs to know each
event's ``seq``/``run_id`` (to buffer and replay) and must re-emit frames
byte-for-byte on reconnect. It therefore keeps each frame's raw bytes and only
parses the ``data:`` JSON for correlation — it never re-serialises upstream
content.

Parsing is total: a malformed ``data:`` line yields a frame with ``json=None``
and is still relayed, never dropped and never raised.
"""

from __future__ import annotations

import json
from dataclasses import dataclass


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
    """Incrementally decode an SSE byte stream into :class:`Frame` objects."""

    def __init__(self) -> None:
        self._buf = b""
        self._hold = b""

    def feed(self, chunk: bytes) -> list[Frame]:
        if not chunk:
            return []
        data = self._hold + chunk
        self._hold = b""
        # A trailing lone CR may pair with an LF at the start of the next chunk;
        # hold it back so a CRLF split across chunks is normalised correctly.
        if data.endswith(b"\r"):
            self._hold = b"\r"
            data = data[:-1]
        self._buf += data
        # Normalise CRLF / lone CR to LF for line splitting (SSE allows all three).
        self._buf = self._buf.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        frames: list[Frame] = []
        while b"\n\n" in self._buf:
            block, self._buf = self._buf.split(b"\n\n", 1)
            frame = self._parse(block)
            if frame is not None:
                frames.append(frame)
        return frames

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
