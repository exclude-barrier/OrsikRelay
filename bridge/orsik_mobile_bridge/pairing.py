"""Operator-facing pairing bootstrap helpers.

* Build the client deep link ``orsik://pair?server=<url>&code=<code>`` the
  operator hands to the phone (QR or paste).
* Render an ASCII QR code for the terminal when a QR renderer is available.

The pairing code is a short-lived, single-use, server-bound secret — it is never
written to a URL-embedded place other than this on-screen deep link and never
logged. The device key is issued only after redemption, never in the link.
"""

from __future__ import annotations

import shutil
import subprocess
from urllib.parse import quote, urlsplit


def pairing_uri(base_url: str, code: str) -> str:
    """Return the ``orsik://pair`` deep link for an HTTPS base URL and code.

    The base URL must be absolute HTTPS with a host and no userinfo (fail-closed
    otherwise, mirroring the client parser).
    """
    parts = urlsplit(base_url)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        raise ValueError("base URL must be an absolute https URL without userinfo")
    return f"orsik://pair?server={quote(base_url, safe='')}&code={quote(code, safe='')}"


def format_code(code: str) -> str:
    """Display form: 6 groups of 4 separated by '-', matching the CLI contract."""
    return "-".join(code[i:i + 4] for i in range(0, len(code), 4))


def render_ascii_qr(text: str) -> str | None:
    """Render ``text`` as a QR block for the terminal, or None without a renderer.

    Uses the system ``qrencode`` when available; this is a display convenience
    only — the bridge never depends on it for correctness.

    ``ANSIUTF8`` carries explicit black/white ANSI colors, so the code keeps
    the dark-on-light polarity a scanner expects in both dark and light
    terminal themes. ``-m 2`` keeps a two-module quiet zone around it.
    """
    exe = shutil.which("qrencode")
    if not exe:
        return None
    try:
        result = subprocess.run(
            [exe, "-t", "ANSIUTF8", "-o", "-", "-m", "2", text],
            capture_output=True, check=True, timeout=10,
        )
        return result.stdout.decode("utf-8", "replace")
    except (OSError, subprocess.SubprocessError):
        return None
