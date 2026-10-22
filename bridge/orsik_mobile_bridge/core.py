"""Dependency-free core logic for the Orsik Mobile Bridge PoC.

This module carries everything that can be reasoned about and tested without a
running HTTP server: pairing-code normalisation and storage, device-token
storage with a tight permission allowlist, the closed upstream path allowlist,
the device credential shape, and the Orsik Mobile Protocol v1 capability
envelope.

Nothing here ever returns or logs the Hermes API key, a pairing code, or a
device token in plaintext after minting.
"""

from __future__ import annotations

import json

import hashlib
import re
import secrets
import time
import uuid
from dataclasses import dataclass, field

# Contract alphabet: A-Z without I/L/O/U plus 1-9 (31 characters).
CODE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ123456789"
CODE_LENGTH = 24
CODE_TTL_SECONDS = 300

PROTOCOL_VERSION = 1
DEVICE_CREDENTIAL_OBJECT = "hermes.device.credential"

# The shipped client (HermesConnectionClient) hard-requires these exact values.
CAPABILITIES_OBJECT = "hermes.api_server.capabilities"
HERMES_PLATFORM = "hermes-agent"
SERVER_AGENT_MODE = "server_agent"
SERVER_TOOL_EXECUTION = "server"

# The closed permission set a device token may ever carry. Never widened.
DEVICE_PERMISSIONS = ("approvals", "chat", "runs")

# Closed mapping of the ONLY upstream paths the bridge proxies. The mobile
# client speaks the api_server namespace directly, so this is an identity map
# for those paths. Anything else is refused locally and never reaches Hermes
# (no admin/jobs/cron/memory/skills/toolsets/artifacts surface).
PROXIED_ROUTES: tuple[tuple[str, str], ...] = (
    ("GET", "/api/sessions"),
    ("POST", "/api/sessions"),
    ("GET", "/api/sessions/{session_id}"),
    ("GET", "/api/sessions/{session_id}/messages"),
    ("POST", "/api/sessions/{session_id}/chat/stream"),
    ("GET", "/v1/runs/{run_id}"),
    ("POST", "/v1/runs/{run_id}/approval"),
    ("POST", "/v1/runs/{run_id}/stop"),
    ("POST", "/v1/runs/{run_id}/steer"),
)


def _safe_segment(value: str) -> bool:
    """A path segment may not be empty, '.', '..', or contain a slash."""
    return bool(value) and value not in (".", "..") and "/" not in value and all(
        ch.isalnum() or ch in "-_." for ch in value
    )


MAX_RUN_ID_LENGTH = 256
MAX_REQUEST_ID_LENGTH = 256


def _is_control_free(value: str) -> bool:
    return not any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in value)


def is_safe_run_id(run_id: object) -> bool:
    return (
        isinstance(run_id, str)
        and bool(run_id)
        and len(run_id) <= MAX_RUN_ID_LENGTH
        and _safe_segment(run_id)
        and _is_control_free(run_id)
    )


# --- Mobile-v1 approval contract (least privilege) -------------------------
# The official core accepts once/deny/session/always; Orsik Mobile-v1 permits
# only once/deny. The bridge enforces the narrow set locally so an out-of-scope
# choice (e.g. "always"/"session") can NEVER reach Hermes.
APPROVAL_CHOICE_ONCE = "once"
APPROVAL_CHOICE_DENY = "deny"
ALLOWED_APPROVAL_CHOICES = (APPROVAL_CHOICE_ONCE, APPROVAL_CHOICE_DENY)
APPROVAL_BODY_FIELDS = frozenset({"choice", "request_id"})


def validate_approval_body(body: object) -> tuple[bool, str]:
    """Strict Mobile-v1 approval validation.

    Returns ``(ok, error_code)``. On any violation the caller must answer closed
    and MUST NOT issue an upstream request.
    """
    if not isinstance(body, dict):
        return False, "invalid_json"
    if set(body.keys()) != APPROVAL_BODY_FIELDS:
        return False, "invalid_fields"
    choice = body.get("choice")
    request_id = body.get("request_id")
    if not isinstance(choice, str) or choice not in ALLOWED_APPROVAL_CHOICES:
        return False, "invalid_choice"
    if (
        not isinstance(request_id, str)
        or not request_id.strip()
        or len(request_id) > MAX_REQUEST_ID_LENGTH
        or not _is_control_free(request_id)
    ):
        return False, "invalid_request_id"
    return True, ""


# --- Query allowlist -------------------------------------------------------
# Only the query keys the shipped client actually uses on session reads are
# permitted; any other key, or any query on a non-session route, is refused.
_SESSION_QUERY_KEYS = frozenset({"limit", "offset", "order", "include_children"})


def allowed_query_keys(method: str, upstream_path: str) -> frozenset[str]:
    if method.upper() != "GET":
        return frozenset()
    path = upstream_path.rstrip("/")
    if path == "/api/sessions" or path.startswith("/api/sessions/"):
        return _SESSION_QUERY_KEYS
    return frozenset()


class RateLimiter:
    """Fixed-window limiter bounding pairing/admin abuse (loopback-global)."""

    def __init__(self, max_events: int, window_seconds: float) -> None:
        self._max = max_events
        self._window = window_seconds
        self._events: list[float] = []

    def allow(self, now: float | None = None) -> bool:
        now = time.time() if now is None else now
        cutoff = now - self._window
        self._events = [t for t in self._events if t > cutoff]
        if len(self._events) >= self._max:
            return False
        self._events.append(now)
        return True


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def normalize_code(raw: object) -> str | None:
    """Trim, upper-case, drop '-'/'_'/whitespace, then enforce 24/length+alphabet."""
    if not isinstance(raw, str):
        return None
    cleaned = "".join(ch for ch in raw.strip().upper() if ch not in "-_ \t\r\n")
    if len(cleaned) != CODE_LENGTH:
        return None
    if any(ch not in CODE_ALPHABET for ch in cleaned):
        return None
    return cleaned


def generate_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))


@dataclass
class _CodeEntry:
    expires_at: float
    used: bool = False


class CodeStore:
    """Short-lived, single-use pairing codes, stored only as SHA-256 hashes."""

    _MAX_CODES = 1024

    def __init__(self, ttl_seconds: int = CODE_TTL_SECONDS, max_codes: int = _MAX_CODES) -> None:
        self._ttl = ttl_seconds
        self._max = max_codes
        self._entries: dict[str, _CodeEntry] = {}

    def _prune(self, now: float) -> None:
        for key in [k for k, e in self._entries.items() if e.used or e.expires_at < now]:
            self._entries.pop(key, None)

    def issue(self, now: float | None = None) -> tuple[str, int] | None:
        now = time.time() if now is None else now
        self._prune(now)
        if len(self._entries) >= self._max:
            return None
        code = generate_code()
        self._entries[sha256_hex(code)] = _CodeEntry(expires_at=now + self._ttl)
        return code, self._ttl

    def redeem(self, raw_code: object, now: float | None = None) -> bool:
        code = normalize_code(raw_code)
        if code is None:
            return False
        now = time.time() if now is None else now
        key = sha256_hex(code)
        entry = self._entries.get(key)
        if entry is None or entry.used or entry.expires_at < now:
            return False
        entry.used = True
        return True


@dataclass
class DeviceRecord:
    device_id: str
    label: str
    profile: str
    permissions: tuple[str, ...] = DEVICE_PERMISSIONS
    revoked: bool = False


class TokenStore:
    """Device tokens stored only as SHA-256 hashes, each with a fixed allowlist."""

    _MAX_TOKENS = 4096

    def __init__(self, max_tokens: int = _MAX_TOKENS) -> None:
        self._max = max_tokens
        self._by_hash: dict[str, DeviceRecord] = {}

    def _prune(self) -> None:
        # Revoked tokens can never authenticate again: always drop them so the
        # store stays bounded without weakening revocation.
        for key in [k for k, r in self._by_hash.items() if r.revoked]:
            self._by_hash.pop(key, None)

    def count(self) -> int:
        return len(self._by_hash)

    def mint(self, label: str, profile: str = "default") -> tuple[str, DeviceRecord] | None:
        """Mint a device token, or None when the hard cap is reached."""
        self._prune()
        if len(self._by_hash) >= self._max:
            return None
        device_id = str(uuid.uuid4())
        # Mobile-v1 device_key shape: "hd_" + 43 base64url chars (46 total).
        token = "hd_" + secrets.token_urlsafe(32)
        record = DeviceRecord(device_id=device_id, label=label, profile=profile)
        self._by_hash[sha256_hex(token)] = record
        return token, record

    def lookup(self, token: str | None) -> DeviceRecord | None:
        if not token:
            return None
        record = self._by_hash.get(sha256_hex(token))
        if record is None or record.revoked:
            return None
        return record

    def revoke(self, token: str | None) -> DeviceRecord | None:
        record = self.lookup(token)
        if record is not None:
            record.revoked = True
        return record


@dataclass
class ProxyMatch:
    upstream_path: str
    params: dict[str, str] = field(default_factory=dict)


def match_proxy_route(method: str, path: str) -> ProxyMatch | None:
    """Return the upstream mapping for an allowed device route, else None.

    Only the closed PROXIED_ROUTES set is matched. Path segments are validated
    so an unmatched or malformed path never reaches upstream.
    """
    method = method.upper()
    normalized = path.rstrip("/") or "/"
    for route_method, template in PROXIED_ROUTES:
        if route_method != method:
            continue
        params = _match_template(template, normalized)
        if params is None:
            continue
        if not all(_safe_segment(v) for v in params.values()):
            return None
        return ProxyMatch(upstream_path=_fill_template(template, params), params=params)
    return None


def _match_template(template: str, path: str) -> dict[str, str] | None:
    t_parts = template.strip("/").split("/")
    p_parts = path.strip("/").split("/")
    if len(t_parts) != len(p_parts):
        return None
    params: dict[str, str] = {}
    for t, p in zip(t_parts, p_parts):
        if t.startswith("{") and t.endswith("}"):
            params[t[1:-1]] = p
        elif t != p:
            return None
    return params


def _fill_template(template: str, params: dict[str, str]) -> str:
    out = template
    for name, value in params.items():
        out = out.replace("{" + name + "}", value)
    return out


def build_device_credentials_contract() -> dict:
    # Exactly the four fields the client's device_credentials check requires
    # (extra keys would fail closed).
    return {
        "enabled": True,
        "protocol_version": PROTOCOL_VERSION,
        "pair_endpoint": "device_pair",
        "self_revoke_endpoint": "device_self_revoke",
    }


def build_interactive_approvals_contract() -> dict:
    """The Mobile-v1 session_chat_interactive_approvals contract.

    Truthful: the official Hermes core registers a session-stream approval
    notifier and accepts ``POST /v1/runs/{run_id}/approval`` for the session
    run's ``run_id``, so ``approval.request`` genuinely reaches the client. The
    bridge narrows the choices to the least-privilege set ``once``/``deny`` and
    refuses anything else locally, so those are exactly the choices advertised.
    """
    return {
        "enabled": True,
        "protocol_version": PROTOCOL_VERSION,
        "event": "approval.request",
        "choices": [APPROVAL_CHOICE_ONCE, APPROVAL_CHOICE_DENY],
        "response_endpoint": "run_approval",
    }


def build_capabilities() -> dict:
    """The Orsik Mobile Protocol v1 capability envelope, in the exact shape the
    shipped HermesConnectionClient hard-requires.

    Truthful advertisement: the official Hermes core registers a
    session-stream approval notifier and accepts ``POST /v1/runs/{run_id}/approval``
    for the session run, so ``approval.request`` genuinely reaches the client.
    The bridge narrows the choices to the least-privilege set ``once``/``deny``
    and refuses anything else locally, so that is exactly what it advertises.
    """
    return {
        "object": CAPABILITIES_OBJECT,
        "platform": HERMES_PLATFORM,
        "auth": {"type": "bearer", "required": True},
        "runtime": {
            "mode": SERVER_AGENT_MODE,
            "tool_execution": SERVER_TOOL_EXECUTION,
            "bridge": "orsik-mobile-bridge",
            "core": "official-hermes",
        },
        "features": {
            "session_resources": True,
            "session_chat_streaming": True,
            "run_approval_response": True,
            "run_status": True,
            "run_stop": True,
            "run_steer": True,
            "session_chat_interactive_approvals": build_interactive_approvals_contract(),
            "device_credentials": build_device_credentials_contract(),
        },
        "endpoints": {
            "health": {"method": "GET", "path": "/health"},
            "sessions": {"method": "GET", "path": "/api/sessions"},
            "session_create": {"method": "POST", "path": "/api/sessions"},
            "session_detail": {"method": "GET", "path": "/api/sessions/{session_id}"},
            "session_messages": {"method": "GET", "path": "/api/sessions/{session_id}/messages"},
            "session_chat_stream": {"method": "POST", "path": "/api/sessions/{session_id}/chat/stream"},
            "run_status": {"method": "GET", "path": "/v1/runs/{run_id}"},
            "run_approval": {"method": "POST", "path": "/v1/runs/{run_id}/approval"},
            "run_stop": {"method": "POST", "path": "/v1/runs/{run_id}/stop"},
            "run_steer": {"method": "POST", "path": "/v1/runs/{run_id}/steer"},
            "device_pair": {"method": "POST", "path": "/v1/devices/pair"},
            "device_self_revoke": {"method": "POST", "path": "/v1/devices/self/revoke"},
        },
    }


def build_credential(record: DeviceRecord, token: str, issued_at: float) -> dict:
    # Exactly the seven fields the client's pairing parser requires.
    return {
        "object": DEVICE_CREDENTIAL_OBJECT,
        "device_id": record.device_id,
        "device_key": token,
        "profile": record.profile,
        "label": record.label,
        "permissions": list(record.permissions),
        "issued_at": issued_at,
    }


_HISTORY_IMAGE_PART_TYPES = frozenset({"image", "image_url", "input_image"})
# Hermes 0.22+ stores an API image turn as text: the caption, a blank line, one
# hint per image (a server path or the remote URL) and optionally one
# "[screenshot]" placeholder per image. Without a caption the core inserts its
# default question. Mirrors tui_gateway/session_history.py of the core.
_IMAGE_HINT_SEPARATOR = "\n\n[Image attached"
_IMAGE_HINT_RE = re.compile(r"\[Image attached(?: at)?: [^\n\]]+\]")
_SCREENSHOT_PLACEHOLDER = "[screenshot]"
_DEFAULT_IMAGE_CAPTION = "What do you see in this image?"


def _image_hint_parts(text: str) -> list[dict] | None:
    """The content-part list for a core image-hint text, or None if it is not one.

    Only an exact match is rewritten: the caption, then nothing but hint lines
    and (0 or one per image) trailing placeholders. Anything else stays as it is.
    """
    index = text.find(_IMAGE_HINT_SEPARATOR)
    if index < 0:
        return None
    # The caption may itself contain blank lines; the hints start at the first
    # separator, exactly where the core appended them.
    caption, rest = text[:index], text[index + 2:]
    lines = rest.split("\n")
    placeholders = 0
    while lines and lines[-1] == _SCREENSHOT_PLACEHOLDER:
        lines.pop()
        placeholders += 1
    if not lines or not all(_IMAGE_HINT_RE.fullmatch(line) for line in lines):
        return None
    if placeholders not in (0, len(lines)):
        return None
    parts: list[dict] = []
    if caption and caption != _DEFAULT_IMAGE_CAPTION:
        parts.append({"type": "text", "text": caption})
    parts.extend({"type": "image_url"} for _ in lines)
    return parts
# Never read by the app: model scratch text and the raw tool-call arguments.
_HISTORY_UNUSED_FIELDS = ("tool_calls", "reasoning", "reasoning_content")


def strip_history_images(body: bytes) -> bytes:
    """Slim a ``GET /api/sessions/{id}/messages`` page to what the app reads.

    - Inline image data is dropped: Hermes keeps a turn with an image as its
      multimodal part list, base64 included, so a page with a few images
      would exceed every client bound. The image parts stay (without data)
      so the app can count them.
    - Tool messages lose their ``content`` (tool output, commands, paths);
      the app renders only the tool name for them.
    - ``tool_calls`` and reasoning fields are removed.
    - A user text with the core's image hints (Hermes 0.22+) becomes the
      caption plus one data-less image part per image, so neither the server
      path of the stored image nor the hint text reaches the device.

    A body that is not the expected JSON shape is returned unchanged.
    """
    try:
        page = json.loads(body)
    except ValueError:
        return body
    data = page.get("data") if isinstance(page, dict) else None
    if not isinstance(data, list):
        return body
    changed = False
    for message in data:
        if not isinstance(message, dict):
            continue
        for key in _HISTORY_UNUSED_FIELDS:
            if key in message:
                del message[key]
                changed = True
        if message.get("role") == "tool" and message.get("content"):
            message["content"] = ""
            changed = True
        content = message.get("content")
        if message.get("role") == "user" and isinstance(content, str):
            # The hint carries the server-side path of the stored image; the
            # app only needs to know that an image was attached.
            parts = _image_hint_parts(content)
            if parts is not None:
                message["content"] = parts
                changed = True
            continue
        if not isinstance(content, list):
            continue
        for index, part in enumerate(content):
            if isinstance(part, dict) and part.get("type") in _HISTORY_IMAGE_PART_TYPES:
                content[index] = {"type": part["type"]}
                changed = True
    if not changed:
        return body
    return json.dumps(page, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
