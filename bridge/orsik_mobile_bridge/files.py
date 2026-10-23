"""Read-only file browser and project list under one operator-chosen folder.

Off unless the operator sets ``BRIDGE_FILES=1`` and ``BRIDGE_FILES_ROOT`` to an
absolute directory. Every request path is relative to that root and must stay
inside it after symlinks are resolved; the bridge's own data (its home, the
state database, bridge.env and the Hermes env file holding the API key) may
not lie inside the root, or the bridge refuses to start. Nothing is written.

All functions here block (file system, git); the server runs them on a worker
thread.
"""

from __future__ import annotations

import os
import stat
import subprocess
from pathlib import Path

MAX_ENTRIES = 2000
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_PATH_CHARS = 4096
MAX_PROJECTS = 50
# Repositories directly in the root or one level below (root/group/repo).
PROJECT_DEPTH = 2
GIT_TIMEOUT = 2.0

_ENABLE_ERROR = "BRIDGE_FILES must be 1 (on), 0 or empty (off)"
_ROOT_ERROR = "BRIDGE_FILES=1 needs BRIDGE_FILES_ROOT, an existing absolute directory"


class PathRefused(Exception):
    """The path is malformed or leaves the root."""


class NotFound(Exception):
    pass


class TooLarge(Exception):
    pass


def parse_enabled(raw: str | None) -> bool:
    value = (raw or "").strip()
    if value in ("", "0"):
        return False
    if value == "1":
        return True
    raise SystemExit(_ENABLE_ERROR)


def resolve_root(raw: str | None, protected: list[str]) -> str:
    """The real path of the root; refuses a root that contains bridge data."""
    value = (raw or "").strip()
    if not value or not os.path.isabs(value) or not os.path.isdir(value):
        raise SystemExit(_ROOT_ERROR)
    root = os.path.realpath(value)
    if root == os.path.sep:
        raise SystemExit("BRIDGE_FILES_ROOT must not be /")
    for path in protected:
        if path and _inside(root, os.path.realpath(path)):
            raise SystemExit(
                "BRIDGE_FILES_ROOT must not contain the bridge's own data or the Hermes env file")
    return root


def _inside(root: str, path: str) -> bool:
    return path == root or path.startswith(root + os.path.sep)


def resolve(root: str, relative: str) -> str:
    """The real path for a request path ("" is the root itself)."""
    if len(relative) > MAX_PATH_CHARS or "\x00" in relative or relative.startswith("/"):
        raise PathRefused()
    parts = [part for part in relative.split("/") if part]
    if any(part in (".", "..") for part in parts):
        raise PathRefused()
    real = os.path.realpath(os.path.join(root, *parts)) if parts else root
    if not _inside(root, real):
        raise PathRefused()
    return real


def _relative(root: str, real: str) -> str:
    return "" if real == root else os.path.relpath(real, root)


def list_dir(root: str, relative: str) -> dict:
    real = resolve(root, relative)
    try:
        if not stat.S_ISDIR(os.stat(real).st_mode):
            raise NotFound()
        scanner = os.scandir(real)
    except (FileNotFoundError, NotADirectoryError, PermissionError):
        raise NotFound() from None
    entries = []
    truncated = False
    with scanner:
        for entry in scanner:
            if len(entries) >= MAX_ENTRIES:
                truncated = True
                break
            entries.append(_entry(root, entry))
    entries.sort(key=lambda item: (item["type"] != "dir", item["name"].lower()))
    return {
        "object": "orsik.files.list",
        "path": _relative(root, real),
        "entries": entries,
        "truncated": truncated,
    }


def _entry(root: str, entry: os.DirEntry) -> dict:
    kind = "other"
    size = None
    mtime = None
    try:
        target = os.path.realpath(entry.path) if entry.is_symlink() else entry.path
        # A link that leaves the root is listed but never opened.
        if _inside(root, target):
            info = os.stat(target)
            mtime = int(info.st_mtime)
            if stat.S_ISDIR(info.st_mode):
                kind = "dir"
            elif stat.S_ISREG(info.st_mode):
                kind = "file"
                size = info.st_size
    except OSError:
        pass
    return {"name": entry.name, "type": kind, "size": size, "modified_at": mtime}


def read_file(root: str, relative: str) -> tuple[bytes, str]:
    """The bytes of a regular file (at most MAX_FILE_BYTES) and its name."""
    real = resolve(root, relative)
    if real == root:
        raise NotFound()
    try:
        # O_NONBLOCK: a FIFO must not hang the worker; it is refused below.
        fd = os.open(real, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    except (FileNotFoundError, NotADirectoryError, PermissionError, IsADirectoryError):
        raise NotFound() from None
    except OSError:
        # ELOOP: the path was swapped for a symlink after resolving.
        raise PathRefused() from None
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode):
        os.close(fd)
        raise NotFound()
    with os.fdopen(fd, "rb") as handle:
        if info.st_size > MAX_FILE_BYTES:
            raise TooLarge()
        data = handle.read(MAX_FILE_BYTES + 1)
    if len(data) > MAX_FILE_BYTES:
        raise TooLarge()
    return data, os.path.basename(real)


_CONTENT_TYPES = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".gif": "image/gif",
}


def content_type(name: str, data: bytes) -> str:
    """A small, closed set: images by extension, UTF-8 text, else binary."""
    image = _CONTENT_TYPES.get(Path(name).suffix.lower())
    if image:
        return image
    if b"\x00" not in data[:8192]:
        try:
            data.decode("utf-8")
            return "text/plain; charset=utf-8"
        except UnicodeDecodeError:
            pass
    return "application/octet-stream"


def _git_env() -> dict[str, str]:
    env = {key: os.environ[key] for key in ("PATH", "HOME", "LANG") if key in os.environ}
    # No system/global config, no pager, no prompts.
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null",
               GIT_TERMINAL_PROMPT="0", GIT_OPTIONAL_LOCKS="0")
    return env


# A repository's own config may name programs (fsmonitor, hooks); none of
# them may run for a read.
_GIT_SAFE = ("-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null")


def _git(repo: str, *args: str) -> str | None:
    try:
        result = subprocess.run(
            ("git", *_GIT_SAFE, "-C", repo, *args),
            capture_output=True, timeout=GIT_TIMEOUT, env=_git_env(), stdin=subprocess.DEVNULL)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    return result.stdout.decode("utf-8", "replace")


def _project(root: str, repo: str) -> dict:
    branch = _git(repo, "rev-parse", "--abbrev-ref", "HEAD")
    head = _git(repo, "log", "-1", "--format=%h%x00%ct%x00%s")
    status = _git(repo, "status", "--porcelain", "--untracked-files=no")
    commit = None
    if head and head.count("\x00") == 2:
        short, when, subject = head.rstrip("\n").split("\x00")
        if when.isdigit():
            commit = {"short": short, "committed_at": int(when), "subject": subject[:200]}
    return {
        "name": os.path.basename(repo),
        "path": _relative(root, repo),
        "branch": branch.strip() if branch else None,
        "dirty": None if status is None else bool(status.strip()),
        "last_commit": commit,
    }


def list_projects(root: str) -> dict:
    """Git working trees in the root and up to PROJECT_DEPTH levels below."""
    repos: list[str] = []
    truncated = False

    def walk(directory: str, depth: int) -> None:
        nonlocal truncated
        if os.path.isdir(os.path.join(directory, ".git")):
            if len(repos) >= MAX_PROJECTS:
                truncated = True
                return
            repos.append(directory)
            return
        if depth >= PROJECT_DEPTH:
            return
        try:
            children = sorted(
                entry.path for entry in os.scandir(directory)
                if entry.is_dir(follow_symlinks=False) and not entry.name.startswith("."))
        except OSError:
            return
        for child in children:
            walk(child, depth + 1)

    walk(root, 0)
    return {
        "object": "orsik.projects.list",
        "projects": [_project(root, repo) for repo in repos],
        "truncated": truncated,
    }
