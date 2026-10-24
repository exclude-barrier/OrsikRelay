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

import itertools
import os
import select
import signal
import stat
import subprocess
import time
from pathlib import Path

MAX_ENTRIES = 2000
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_PATH_CHARS = 4096
MAX_PROJECTS = 50
# Repositories directly in the root or one level below (root/group/repo).
PROJECT_DEPTH = 2
GIT_TIMEOUT = 2.0
# All git calls of one project scan together.
SCAN_DEADLINE = 10.0
# Folders looked at per directory while searching for projects.
MAX_SCAN_CHILDREN = 1000
GIT_OUTPUT_LIMIT = 4096

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


def resolve_root(raw: str | None, protected: list[str], sealed: list[str]) -> str:
    """The real path of the root. It may not contain any [protected] path
    (the home folder, bridge and Hermes data) nor lie inside a [sealed]
    folder (the bridge and Hermes homes)."""
    value = (raw or "").strip()
    if not value or not os.path.isabs(value) or not os.path.isdir(value):
        raise SystemExit(_ROOT_ERROR)
    root = os.path.realpath(value)
    if root == os.path.sep:
        raise SystemExit("BRIDGE_FILES_ROOT must not be /")
    for path in protected:
        if path and _inside(root, os.path.realpath(path)):
            raise SystemExit(
                "BRIDGE_FILES_ROOT must not contain the home folder, the bridge's own data or the Hermes home")
    for path in sealed:
        if path and _inside(os.path.realpath(path), root):
            raise SystemExit("BRIDGE_FILES_ROOT must not lie inside the bridge or the Hermes home")
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


def _open_inside(root: str, real: str, flags: int) -> int:
    """Opens [real] (already resolved inside [root]) one component at a time
    with O_NOFOLLOW, so no component can be swapped for a symlink between
    resolving and opening."""
    parts = [] if real == root else os.path.relpath(real, root).split(os.path.sep)
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for index, part in enumerate(parts):
            last = index == len(parts) - 1
            mode = flags if last else os.O_RDONLY | os.O_DIRECTORY
            try:
                child = os.open(part, mode | os.O_NOFOLLOW, dir_fd=fd)
            except OSError:
                try:
                    swapped = stat.S_ISLNK(os.lstat(part, dir_fd=fd).st_mode)
                except OSError:
                    swapped = False
                if swapped:
                    raise PathRefused() from None
                raise
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def _open_error(error: OSError) -> Exception:
    import errno
    if error.errno in (errno.ELOOP, errno.ENAMETOOLONG):
        return PathRefused()
    return NotFound()


def list_dir(root: str, relative: str) -> dict:
    real = resolve(root, relative)
    try:
        fd = _open_inside(root, real, os.O_RDONLY | os.O_DIRECTORY)
    except OSError as error:
        raise _open_error(error) from None
    entries = []
    truncated = False
    try:
        with os.scandir(fd) as scanner:
            for entry in scanner:
                if len(entries) >= MAX_ENTRIES:
                    truncated = True
                    break
                if not _utf8(entry.name):
                    # Not representable in JSON; it could not be opened by name either.
                    continue
                entries.append(_entry(root, real, entry))
    finally:
        os.close(fd)
    entries.sort(key=lambda item: (item["type"] != "dir", item["name"].lower()))
    return {
        "object": "orsik.files.list",
        # The absolute root, so a client can name a path to Hermes (which
        # does not know the root).
        "root": root,
        "path": _relative(root, real),
        "entries": entries,
        "truncated": truncated,
    }


def _utf8(name: str) -> bool:
    try:
        name.encode("utf-8")
        return True
    except UnicodeEncodeError:
        return False


def _entry(root: str, folder: str, entry: os.DirEntry) -> dict:
    kind = "other"
    size = None
    mtime = None
    try:
        path = os.path.join(folder, entry.name)
        target = os.path.realpath(path) if entry.is_symlink() else path
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
        fd = _open_inside(root, real, os.O_RDONLY | os.O_NONBLOCK)
    except OSError as error:
        # ELOOP: a component was swapped for a symlink after resolving.
        raise _open_error(error) from None
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


# A repository's own config and content may name programs (fsmonitor,
# hooks, and through .gitattributes clean/smudge/process filters or
# textconv); none of them may run for a read. An empty tree as the source
# of attributes means no attribute selects any driver; the work tree is
# pinned (core.worktree is ignored) and submodules are not entered.
_EMPTY_TREE = {"sha1": "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
               "sha256": "6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321"}


def _git_safe(repo: str, object_format: str = "sha1") -> tuple[str, ...]:
    return (
        "git", "--no-optional-locks",
        f"--attr-source={_EMPTY_TREE[object_format]}",
        f"--git-dir={os.path.join(repo, '.git')}", f"--work-tree={repo}",
        "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null",
        "-c", "core.untrackedCache=false", "-c", "submodule.recurse=false",
    )


def _git(repo: str, *args: str, object_format: str = "sha1", deadline: float | None = None) -> str | None:
    """git's first GIT_OUTPUT_LIMIT bytes, or None (error, timeout). The
    whole process group dies afterwards, so nothing git started survives."""
    budget = GIT_TIMEOUT if deadline is None else min(GIT_TIMEOUT, deadline - time.monotonic())
    if budget <= 0:
        return None
    try:
        process = subprocess.Popen(
            (*_git_safe(repo, object_format), *args), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, env=_git_env(), start_new_session=True)
    except OSError:
        return None
    end = time.monotonic() + budget
    output = bytearray()
    complete = False
    try:
        fd = process.stdout.fileno()
        while len(output) < GIT_OUTPUT_LIMIT:
            remaining = end - time.monotonic()
            if remaining <= 0 or not select.select([fd], [], [], remaining)[0]:
                return None
            chunk = os.read(fd, GIT_OUTPUT_LIMIT)
            if not chunk:
                complete = True
                break
            output += chunk
        if complete:
            try:
                if process.wait(timeout=max(0.05, end - time.monotonic())) != 0:
                    return None
            except subprocess.TimeoutExpired:
                return None
    finally:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        process.stdout.close()
        process.wait()
    return output[:GIT_OUTPUT_LIMIT].decode("utf-8", "replace")


def _project(root: str, repo: str, deadline: float) -> dict:
    fmt = (_git(repo, "rev-parse", "--show-object-format", deadline=deadline) or "sha1").strip()
    fmt = fmt if fmt in _EMPTY_TREE else "sha1"
    branch = _git(repo, "rev-parse", "--abbrev-ref", "HEAD", object_format=fmt, deadline=deadline)
    head = _git(repo, "log", "-1", "--format=%h%x00%ct%x00%s", object_format=fmt, deadline=deadline)
    status = _git(repo, "status", "--porcelain", "--untracked-files=no", "--ignore-submodules=all",
                  object_format=fmt, deadline=deadline)
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
        git_dir = os.path.join(directory, ".git")
        # A real .git folder inside the root only (a link to an outside
        # repository is not followed).
        if not os.path.islink(git_dir) and os.path.isdir(git_dir) and _inside(root, os.path.realpath(git_dir)):
            if len(repos) >= MAX_PROJECTS:
                truncated = True
                return
            repos.append(directory)
            return
        if depth >= PROJECT_DEPTH:
            return
        try:
            with os.scandir(directory) as scanner:
                children = sorted(
                    entry.path for entry in itertools.islice(scanner, MAX_SCAN_CHILDREN)
                    if entry.is_dir(follow_symlinks=False) and not entry.name.startswith("."))
        except OSError:
            return
        for child in children:
            walk(child, depth + 1)

    walk(root, 0)
    deadline = time.monotonic() + SCAN_DEADLINE
    return {
        "object": "orsik.projects.list",
        "projects": [_project(root, repo, deadline) for repo in repos],
        "truncated": truncated,
    }
