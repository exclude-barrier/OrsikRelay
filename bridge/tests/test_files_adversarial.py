"""Adversarial tests for the read-only bridge files/projects routes (T15).

Every test encodes a security property of the threat model:

    a paired device may read files below BRIDGE_FILES_ROOT only; it must never
    read outside the root, never write, never make the bridge run a program
    chosen by repository content, and never exhaust the bridge.

Tests decorated with ``@unittest.expectedFailure`` prove a real defect in the
current implementation: they fail on the vulnerable behaviour and become
"unexpected success" once the defect is fixed.  The finding number in the
docstring refers to REPORT-T15.md in the worktree root.
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.parse
from pathlib import Path
from unittest import mock

from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import files
from orsik_mobile_bridge import server as bridge_server

HAVE_GIT = shutil.which("git") is not None


def _git(repo: Path, *args: str) -> None:
    subprocess.run(
        ("git", "-C", str(repo), *args), check=True, capture_output=True,
        env={**os.environ, "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1"})


def _init_repo(repo: Path) -> None:
    repo.mkdir(parents=True, exist_ok=True)
    _git(repo, "init", "-q", "-b", "main")
    _git(repo, "-c", "user.email=t@example.invalid", "-c", "user.name=t",
         "commit", "-q", "--allow-empty", "-m", "first commit")


class _TempTree(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.root = self.base / "root"
        self.root.mkdir()
        self.real = os.path.realpath(self.root)
        (self.base / "secret.txt").write_text("OUTSIDE-SECRET-MARKER")


class PathContainmentUnitTest(_TempTree):
    def test_malformed_and_traversing_paths_never_escape(self):
        for bad in (
            "..",
            "../secret.txt",
            "sub/../../secret.txt",
            "/etc/passwd",
            "//etc/passwd",
            "sub/./a.txt",
            "sub/../..",
            "a\x00b",
            "x" * (files.MAX_PATH_CHARS + 1),
            "sub/..",
        ):
            with self.subTest(path=repr(bad[:40])):
                with self.assertRaises(files.PathRefused):
                    files.resolve(self.real, bad)

    def test_backslash_and_unicode_lookalikes_stay_inside_the_root(self):
        for literal in ("..\\..\\secret.txt", "\u2025/etc/passwd", "\uff0e\uff0e/x"):
            with self.subTest(path=repr(literal)):
                try:
                    real = files.resolve(self.real, literal)
                except files.PathRefused:
                    continue
                self.assertTrue(files._inside(self.real, real))

    def test_symlink_chains_and_loops_never_escape(self):
        (self.root / "sub").mkdir()
        (self.root / "sub" / "a.txt").write_text("hello")
        os.symlink(self.base / "secret.txt", self.root / "hop1")
        os.symlink(self.root / "hop1", self.root / "hop2")
        os.symlink(self.root / "hop2", self.root / "hop3")
        os.symlink("loop", self.root / "loop")
        for bad in ("hop1", "hop2", "hop3", "hop2/../secret.txt", "loop"):
            with self.subTest(path=bad):
                with self.assertRaises(files.PathRefused):
                    files.read_file(self.real, bad)

    def test_a_relative_symlink_followed_by_dotdot_is_refused(self):
        os.symlink(self.base, self.root / "escape")
        with self.assertRaises(files.PathRefused):
            files.resolve(self.real, "escape/../secret.txt")

    def test_double_slashes_and_a_trailing_slash_stay_inside(self):
        (self.root / "sub").mkdir()
        (self.root / "sub" / "a.txt").write_text("hello")
        self.assertEqual(files.read_file(self.real, "sub//a.txt")[0], b"hello")
        self.assertEqual(files.read_file(self.real, "sub/a.txt/")[0], b"hello")

    def test_a_link_inside_the_root_is_read_through_its_target(self):
        (self.root / "target.txt").write_text("inside")
        os.symlink(self.root / "target.txt", self.root / "link.txt")
        self.assertEqual(files.read_file(self.real, "link.txt")[0], b"inside")

    @unittest.expectedFailure  # residual, see docs/bridge-security.md
    def test_hardlink_to_a_file_outside_the_root_is_not_served(self):
        """Finding 5: a hard link inside the root bypasses symlink containment."""
        outside = self.base / "outside.txt"
        outside.write_text("HARD-LINK-OUTSIDE")
        os.link(outside, self.root / "hard.txt")
        with self.assertRaises(files.PathRefused):
            files.read_file(self.real, "hard.txt")

    def test_an_intermediate_directory_swapped_for_a_symlink_is_not_followed(self):
        """Finding 6 (theoretical): O_NOFOLLOW protects only the final component."""
        (self.root / "d").mkdir()
        (self.root / "d" / "f.txt").write_text("inside")
        outside = self.base / "outside-dir"
        outside.mkdir()
        (outside / "f.txt").write_text("TOCTOU-OUTSIDE")
        target = os.path.join(self.real, "d", "f.txt")
        os.rename(self.root / "d", self.root / "d-real")
        os.symlink(outside, self.root / "d")
        with mock.patch.object(files, "resolve", return_value=target):
            with self.assertRaises(files.PathRefused):
                files.read_file(self.real, "d/f.txt")


class RootConfigurationTest(_TempTree):
    def setUp(self):
        super().setUp()
        self.home = self.base / "home"
        self.home.mkdir()
        (self.home / ".hermes").mkdir()
        (self.home / ".hermes" / ".env").write_text("API_SERVER_KEY=HERMES-KEY-MARKER\n")
        self.bridge_home = self.base / "bridge-home"
        self.bridge_home.mkdir()
        (self.bridge_home / "bridge.env").write_text("HERMES_API_KEY=BRIDGE-ENV-MARKER\n")
        self.elsewhere = self.base / "elsewhere"
        self.elsewhere.mkdir()

    def test_the_root_is_stored_as_its_real_path(self):
        os.symlink(self.root, self.base / "root-link")
        self.assertEqual(
            files.resolve_root(str(self.base / "root-link"), [], []), self.real)

    def test_slash_and_the_home_holding_the_default_state_db_are_refused(self):
        with self.assertRaises(SystemExit):
            files.resolve_root("/", [], [])
        env = {
            "BRIDGE_FILES": "1",
            "BRIDGE_FILES_ROOT": str(self.home),
            "BRIDGE_HOME": str(self.home),
            "HERMES_API_KEY": "x",
        }
        with self.assertRaises(SystemExit):
            bridge_server._files_root_from_env(env)

    def test_a_root_containing_the_hermes_env_directory_is_refused(self):
        env = {
            "BRIDGE_FILES": "1",
            "BRIDGE_FILES_ROOT": str(self.home),
            "BRIDGE_HERMES_ENV": str(self.home / ".hermes" / ".env"),
            "BRIDGE_STATE_DB": str(self.elsewhere / "state.db"),
        }
        with self.assertRaises(SystemExit):
            bridge_server._files_root_from_env(env)

    def test_a_parent_of_bridge_home_reached_through_a_symlink_is_refused(self):
        os.symlink(self.bridge_home, self.base / "bridge-link")
        env = {
            "BRIDGE_FILES": "1",
            "BRIDGE_FILES_ROOT": str(self.base),
            "BRIDGE_HOME": str(self.base / "bridge-link"),
            "HERMES_API_KEY": "x",
        }
        with self.assertRaises(SystemExit):
            bridge_server._files_root_from_env(env)

    def test_a_root_inside_the_hermes_home_does_not_reach_the_env_file(self):
        workspace = self.home / ".hermes" / "workspace"
        workspace.mkdir()
        env = {
            "BRIDGE_FILES": "1",
            "BRIDGE_FILES_ROOT": str(workspace),
            "BRIDGE_HERMES_ENV": str(self.home / ".hermes" / ".env"),
            "BRIDGE_STATE_DB": str(self.elsewhere / "state.db"),
        }
        root = bridge_server._files_root_from_env(env)
        self.assertIsNotNone(root)
        assert root is not None
        with self.assertRaises(files.PathRefused):
            files.read_file(root, "../.env")

    def test_home_with_hermes_env_unset_but_api_key_in_the_process_env_is_refused(self):
        """Finding 3: the supported HERMES_API_KEY mode leaves ~/.hermes/.env browsable."""
        env = {
            "BRIDGE_FILES": "1",
            "BRIDGE_FILES_ROOT": str(self.home),
            "BRIDGE_HOME": str(self.home),
            "BRIDGE_STATE_DB": str(self.elsewhere / "state.db"),
            "HERMES_API_KEY": "HERMES-KEY-MARKER",
        }
        with self.assertRaises(SystemExit):
            bridge_server._files_root_from_env(env)

    def test_bridge_home_with_a_custom_state_db_is_refused(self):
        """Finding 3: BRIDGE_HOME (with bridge.env) is only protected via the state DB."""
        env = {
            "BRIDGE_FILES": "1",
            "BRIDGE_FILES_ROOT": str(self.bridge_home),
            "BRIDGE_HOME": str(self.bridge_home),
            "BRIDGE_STATE_DB": str(self.elsewhere / "state.db"),
            "HERMES_API_KEY": "x",
        }
        with self.assertRaises(SystemExit):
            bridge_server._files_root_from_env(env)

    def test_a_hermes_env_symlinked_into_the_root_is_refused(self):
        """Finding 4: only the link's directory is checked, not the file's target."""
        config = self.base / "config"
        config.mkdir()
        os.symlink(self.home / ".hermes" / ".env", config / ".env")
        env = {
            "BRIDGE_FILES": "1",
            "BRIDGE_FILES_ROOT": str(self.home),
            "BRIDGE_HERMES_ENV": str(config / ".env"),
            "BRIDGE_STATE_DB": str(self.elsewhere / "state.db"),
        }
        with self.assertRaises(SystemExit):
            bridge_server._files_root_from_env(env)


class SpecialFileUnitTest(_TempTree):
    def test_fifo_and_unix_socket_are_never_read_as_files(self):
        os.mkfifo(self.root / "pipe")
        with self.assertRaises(files.NotFound):
            files.read_file(self.real, "pipe")
        sock = socket.socket(socket.AF_UNIX)
        self.addCleanup(sock.close)
        sock.bind(str(self.root / "sock"))
        with self.assertRaises((files.PathRefused, files.NotFound)):
            files.read_file(self.real, "sock")
        kinds = {e["name"]: e["type"] for e in files.list_dir(self.real, "")["entries"]}
        self.assertEqual(kinds["pipe"], "other")
        self.assertEqual(kinds["sock"], "other")

    def test_a_symlink_to_a_device_file_outside_the_root_is_refused(self):
        os.symlink("/dev/null", self.root / "null")
        with self.assertRaises(files.PathRefused):
            files.read_file(self.real, "null")

    def test_files_exactly_at_and_over_the_size_limit(self):
        (self.root / "exact.bin").write_bytes(b"x" * files.MAX_FILE_BYTES)
        data, _ = files.read_file(self.real, "exact.bin")
        self.assertEqual(len(data), files.MAX_FILE_BYTES)
        (self.root / "over.bin").write_bytes(b"x" * (files.MAX_FILE_BYTES + 1))
        with self.assertRaises(files.TooLarge):
            files.read_file(self.real, "over.bin")

    def test_a_sparse_file_at_the_limit_is_served_as_zeros(self):
        path = self.root / "sparse.bin"
        with open(path, "wb") as handle:
            handle.truncate(files.MAX_FILE_BYTES)
        data, _ = files.read_file(self.real, "sparse.bin")
        self.assertEqual(len(data), files.MAX_FILE_BYTES)
        self.assertEqual(data[:16], b"\x00" * 16)

    def test_a_file_that_grows_while_read_stays_bounded(self):
        path = self.root / "grow.bin"
        path.write_bytes(b"x" * 10)
        stop = threading.Event()

        def grow():
            while not stop.is_set():
                with open(path, "ab") as handle:
                    handle.write(b"x" * 128)

        writer = threading.Thread(target=grow, daemon=True)
        writer.start()
        self.addCleanup(writer.join, 5)
        self.addCleanup(stop.set)
        with mock.patch.object(files, "MAX_FILE_BYTES", 1024):
            for _ in range(60):
                try:
                    data, _ = files.read_file(self.real, "grow.bin")
                except files.TooLarge:
                    continue
                self.assertLessEqual(len(data), 1024)
        stop.set()
        writer.join(5)

    def test_a_large_directory_is_truncated_by_the_entry_cap(self):
        for index in range(files.MAX_ENTRIES + 500):
            (self.root / f"f{index:05d}").touch()
        listing = files.list_dir(self.real, "")
        self.assertTrue(listing["truncated"])
        self.assertEqual(len(listing["entries"]), files.MAX_ENTRIES)

    def test_invalid_utf8_and_control_character_names_are_representable(self):
        raw = os.path.join(os.fsencode(self.root), b"bad\xffname")
        os.close(os.open(raw, os.O_CREAT | os.O_WRONLY, 0o644))
        (self.root / "line\nbreak").write_text("x")
        names = [entry["name"] for entry in files.list_dir(self.real, "")["entries"]]
        # A name that is not valid UTF-8 cannot be named in a request: skipped.
        self.assertNotIn("bad\udcffname", names)
        self.assertIn("line\nbreak", names)
        json.dumps(names)


class GitConfigurationTest(unittest.TestCase):
    def setUp(self):
        if not HAVE_GIT:
            self.skipTest("git is required")
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.root = self.base / "root"
        self.root.mkdir()
        self.real = os.path.realpath(self.root)
        self.marker = self.base / "RAN"

    def _repo(self, relative: str = "repo") -> Path:
        repo = self.root / relative
        _init_repo(repo)
        return repo

    def _script(self, name: str, body: str) -> Path:
        script = self.base / name
        script.write_text(f"#!/bin/sh\n{body}\n")
        script.chmod(0o755)
        return script

    def test_a_clean_filter_driver_from_the_repository_config_is_not_run(self):
        """Finding 1: git status executes a filter named by repository config."""
        repo = self._repo()
        (repo / "x.bin").write_bytes(b"data")
        (repo / ".gitattributes").write_text("*.bin filter=evil\n")
        _git(repo, "add", "-A")
        script = self._script(
            "clean.sh", f"touch '{self.marker}'\nexec cat")
        _git(repo, "config", "filter.evil.clean", str(script))
        files.list_projects(self.real)
        self.assertFalse(self.marker.exists(), "a repository filter program ran")

    def test_a_process_filter_driver_from_the_repository_config_is_not_run(self):
        """Finding 1: the long-running filter protocol is started by git status."""
        repo = self._repo()
        (repo / "x.bin").write_bytes(b"data")
        (repo / ".gitattributes").write_text("*.bin filter=evil\n")
        _git(repo, "add", "-A")
        script = self._script(
            "process.sh", f"touch '{self.marker}'\nexit 0")
        _git(repo, "config", "filter.evil.process", str(script))
        files.list_projects(self.real)
        self.assertFalse(self.marker.exists(), "a repository filter process ran")

    def test_other_program_naming_config_does_not_run(self):
        repo = self._repo()
        script = self._script(
            "prog.sh", f"touch '{self.marker}'\nexit 0")
        (repo / ".gitattributes").write_text("* diff=evil\n")
        for key, value in (
            ("core.pager", str(script)),
            ("core.fsmonitor", str(script)),
            ("core.hooksPath", str(self.base)),
            ("core.sshCommand", str(script)),
            ("core.editor", str(script)),
            ("sequence.editor", str(script)),
            ("credential.helper", str(script)),
            ("diff.external", str(script)),
            ("diff.evil.textconv", str(script)),
            ("interactive.diffFilter", str(script)),
        ):
            _git(repo, "config", key, value)
        files.list_projects(self.real)
        self.assertFalse(self.marker.exists(), "a program from the repository config ran")

    def test_an_included_attacker_config_cannot_enable_fsmonitor(self):
        repo = self._repo()
        script = self._script(
            "prog.sh", f"touch '{self.marker}'\nexit 0")
        evil = self.base / "evil.conf"
        evil.write_text(f"[core]\n\tfsmonitor = {script}\n")
        _git(repo, "config", "include.path", str(evil))
        _git(repo, "config", "includeIf.gitdir:/.path", str(evil))
        _git(repo, "config", "safe.directory", "*")
        files.list_projects(self.real)
        self.assertFalse(self.marker.exists(), "an included config enabled a program")

    def test_a_gitdir_file_pointing_outside_the_root_is_not_a_project(self):
        outside = self.base / "outside-repo"
        _init_repo(outside)
        victim = self.root / "victim"
        victim.mkdir()
        (victim / ".git").write_text(f"gitdir: {outside / '.git'}\n")
        listing = files.list_projects(self.real)
        self.assertEqual(listing["projects"], [])

    def test_a_symlinked_git_directory_reveals_no_outside_metadata(self):
        """Finding 8: a .git symlink to a gitdir outside the root is followed."""
        outside = self.base / "outside-repo"
        _init_repo(outside)
        (outside / "marker.txt").write_text("x")
        _git(outside, "add", "-A")
        _git(outside, "-c", "user.email=t@example.invalid", "-c", "user.name=t",
             "commit", "-q", "-m", "OUTSIDE-COMMIT-SUBJECT")
        victim = self.root / "victim"
        victim.mkdir()
        os.symlink(outside / ".git", victim / ".git")
        listing = files.list_projects(self.real)
        self.assertNotIn("OUTSIDE-COMMIT-SUBJECT", json.dumps(listing))
        for project in listing["projects"]:
            self.assertIsNone(project["branch"])
            self.assertIsNone(project["last_commit"])

    def test_a_slow_git_is_bounded_by_the_timeout(self):
        fake_bin = self.base / "bin"
        fake_bin.mkdir()
        fake_git = fake_bin / "git"
        fake_git.write_text("#!/bin/sh\nexec sleep 5\n")
        fake_git.chmod(0o755)
        (self.root / "repo" / ".git").mkdir(parents=True)
        started = time.monotonic()
        with mock.patch.object(files, "GIT_TIMEOUT", 0.2), mock.patch.dict(
                os.environ, {"PATH": str(fake_bin) + os.pathsep + os.environ.get("PATH", "")}):
            listing = files.list_projects(self.real)
        self.assertLess(time.monotonic() - started, 5.0)
        self.assertEqual(len(listing["projects"]), 1)
        self.assertIsNone(listing["projects"][0]["dirty"])

    def test_a_project_scan_does_not_write(self):
        (self.root / "repo" / ".git").mkdir(parents=True)
        _git(self.root / "repo", "init", "-q", "-b", "main")
        (self.root / "repo" / "f.txt").write_text("x")
        _git(self.root / "repo", "add", "f.txt")
        index = self.root / "repo" / ".git" / "index"
        before = index.read_bytes()
        names_before = {entry.name for entry in (self.root / "repo" / ".git").iterdir()}
        files.list_projects(self.real)
        self.assertEqual(index.read_bytes(), before)
        self.assertEqual(
            {entry.name for entry in (self.root / "repo" / ".git").iterdir()}, names_before)


class FilesRouteAdversarialTest(unittest.IsolatedAsyncioTestCase):
    async def _start(self, enabled: bool = True):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.root = self.base / "root"
        (self.root / "sub").mkdir(parents=True)
        (self.root / "sub" / "a.txt").write_text("hello")
        (self.base / "secret.txt").write_text("OUTSIDE-SECRET-MARKER")
        self.state = bridge_server.BridgeState(
            upstream="http://127.0.0.1:9", master_key="master_key_test",
            files_root=os.path.realpath(self.root) if enabled else None)
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.addCleanup(self.state.store.close)
        issued = self.state.codes.issue()
        resp = await self.client.post("/v1/devices/pair", json={"code": issued[0]})
        self.token = (await resp.json())["device_key"]
        self.auth = {"Authorization": "Bearer " + self.token}

    async def test_encoded_traversal_variants_are_closed(self):
        await self._start()
        cases = (
            "..",
            "%2e%2e",
            "..%2Fsecret.txt",
            "sub%2F..%2F..%2Fsecret.txt",
            "sub/../../secret.txt",
            "/etc/passwd",
            "%2Fetc%2Fpasswd",
            "%2F%2Fetc%2Fpasswd",
            "..%5C..%5Cetc%5Cpasswd",
            "%E2%80%A5%2Fetc%2Fpasswd",
            "%EF%BC%8E%EF%BC%8E%2Fx",
            "a%00b",
            "x" * (files.MAX_PATH_CHARS + 1),
        )
        for raw in cases:
            with self.subTest(path=raw[:40]):
                resp = await self.client.get(
                    "/v1/files/content?path=" + raw, headers=self.auth)
                self.assertIn(resp.status, (403, 404), raw[:40])
                self.assertNotIn("OUTSIDE-SECRET-MARKER", await resp.text())

    async def test_symlinks_leaving_the_root_are_closed(self):
        await self._start()
        os.symlink(self.base / "secret.txt", self.root / "out-link")
        os.symlink(self.base, self.root / "out-dir")
        os.symlink(self.root / "out-link", self.root / "chain")
        for bad in ("out-link", "out-dir/secret.txt", "chain"):
            with self.subTest(path=bad):
                resp = await self.client.get(
                    "/v1/files/content?path=" + urllib.parse.quote(bad), headers=self.auth)
                self.assertEqual(resp.status, 403)
                self.assertNotIn("OUTSIDE-SECRET-MARKER", await resp.text())

    async def test_double_slashes_and_a_trailing_slash_stay_inside(self):
        await self._start()
        for path in ("sub//a.txt", "sub/a.txt/"):
            resp = await self.client.get(
                "/v1/files/content?path=" + urllib.parse.quote(path), headers=self.auth)
            self.assertEqual(resp.status, 200)
            self.assertEqual(await resp.read(), b"hello")

    async def test_missing_and_revoked_tokens_are_401(self):
        await self._start()
        self.assertEqual((await self.client.get("/v1/files/list")).status, 401)
        for header in ("Basic abc", "Bearer", "Bearer nope"):
            with self.subTest(header=header):
                resp = await self.client.get(
                    "/v1/files/list", headers={"Authorization": header})
                self.assertEqual(resp.status, 401)
        revoked = await self.client.post("/v1/devices/self/revoke", headers=self.auth)
        self.assertEqual(revoked.status, 200)
        for path in ("/v1/files/list", "/v1/files/content?path=sub/a.txt", "/v1/projects"):
            with self.subTest(path=path):
                self.assertEqual(
                    (await self.client.get(path, headers=self.auth)).status, 401)

    async def test_the_disabled_switch_is_closed_and_not_advertised(self):
        await self._start(enabled=False)
        for path in ("/v1/files/list", "/v1/files/content?path=x", "/v1/projects"):
            with self.subTest(path=path):
                resp = await self.client.get(path, headers=self.auth)
                self.assertEqual(resp.status, 403)
                self.assertEqual((await resp.json())["error"]["code"], "endpoint_out_of_scope")
        caps = await (await self.client.get("/v1/capabilities", headers=self.auth)).json()
        self.assertIs(caps["features"]["files"], False)
        self.assertIs(caps["features"]["projects"], False)
        for name in ("files_list", "files_content", "projects"):
            self.assertNotIn(name, caps["endpoints"])

    async def test_query_method_and_body_handling_are_closed(self):
        await self._start()
        for path in (
            "/v1/files/list?x=1",
            "/v1/files/list?path=a&path=b",
            "/v1/files/content?path=sub/a.txt&path=",
            "/v1/projects?path=a",
            "/v1/files/list/",
        ):
            with self.subTest(path=path):
                resp = await self.client.get(path, headers=self.auth)
                self.assertEqual(resp.status, 403, path)
        self.assertEqual(
            (await self.client.get("/v1/files/content?path=sub/a.txt",
                                   headers=self.auth, data=b"x")).status, 400)
        self.assertEqual(
            (await self.client.get("/v1/files/content?path=sub/a.txt",
                                   headers=self.auth, data=b"x" * 10_000_001)).status, 413)
        self.assertNotEqual(
            (await self.client.post("/v1/files/list", headers=self.auth)).status, 200)
        self.assertNotEqual(
            (await self.client.head(
                "/v1/files/content?path=sub/a.txt", headers=self.auth)).status, 200)

    async def test_listing_and_reading_errors_are_closed(self):
        await self._start()
        cases = (
            ("/v1/files/list?path=missing", 404),
            ("/v1/files/list?path=sub/a.txt", 404),
            ("/v1/files/content?path=sub", 404),
            ("/v1/files/content?path=", 404),
        )
        for path, status in cases:
            with self.subTest(path=path):
                resp = await self.client.get(path, headers=self.auth)
                self.assertEqual(resp.status, status)
                self.assertEqual((await resp.json())["error"]["code"], "not_found")

    async def test_fifo_and_socket_never_hang_or_are_served(self):
        await self._start()
        os.mkfifo(self.root / "pipe")
        sock = socket.socket(socket.AF_UNIX)
        self.addCleanup(sock.close)
        sock.bind(str(self.root / "sock"))
        for name in ("pipe", "sock"):
            with self.subTest(name=name):
                started = time.monotonic()
                resp = await self.client.get(
                    "/v1/files/content?path=" + name, headers=self.auth)
                self.assertIn(resp.status, (403, 404))
                self.assertLess(time.monotonic() - started, 5.0)
        listing = await (await self.client.get("/v1/files/list", headers=self.auth)).json()
        kinds = {entry["name"]: entry["type"] for entry in listing["entries"]}
        self.assertEqual(kinds["pipe"], "other")
        self.assertEqual(kinds["sock"], "other")

    async def test_file_size_limit_is_enforced_at_the_route(self):
        await self._start()
        (self.root / "small.bin").write_bytes(b"x" * 1025)
        with mock.patch.object(files, "MAX_FILE_BYTES", 1024):
            resp = await self.client.get(
                "/v1/files/content?path=small.bin", headers=self.auth)
        self.assertEqual(resp.status, 413)
        self.assertEqual((await resp.json())["error"]["code"], "file_too_large")

    async def test_a_large_directory_is_truncated_not_buffered_without_bound(self):
        await self._start()
        for index in range(files.MAX_ENTRIES + 500):
            (self.root / f"f{index:05d}").touch()
        resp = await self.client.get("/v1/files/list", headers=self.auth)
        self.assertEqual(resp.status, 200)
        listing = await resp.json()
        self.assertTrue(listing["truncated"])
        self.assertEqual(len(listing["entries"]), files.MAX_ENTRIES)

    async def test_a_symlink_loop_is_a_closed_error_on_the_route(self):
        """Finding 7: list_dir lets ELOOP escape and the route answers 500."""
        await self._start()
        os.symlink("loop", self.root / "loop")
        resp = await self.client.get("/v1/files/list?path=loop", headers=self.auth)
        self.assertIn(resp.status, (403, 404))

    @unittest.expectedFailure  # residual, see docs/bridge-security.md
    async def test_a_hardlink_to_outside_is_not_served_at_the_route(self):
        """Finding 5: a hard link inside the root is read across the boundary."""
        await self._start()
        os.link(self.base / "secret.txt", self.root / "hard.txt")
        resp = await self.client.get(
            "/v1/files/content?path=hard.txt", headers=self.auth)
        self.assertIn(resp.status, (403, 404))
        self.assertNotIn("OUTSIDE-SECRET-MARKER", await resp.text())

    async def test_audit_path_with_newline_cannot_forge_lines(self):
        """Finding 2: a file name with a newline injects a second audit line."""
        await self._start()
        name = "evil\nforged action=chat device=forged-device"
        (self.root / name).write_text("x")
        with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
            resp = await self.client.get(
                "/v1/files/content?path=" + urllib.parse.quote(name, safe=""),
                headers=self.auth)
            self.assertEqual(resp.status, 200)
        messages = [record.getMessage() for record in logs.records]
        self.assertEqual(len(messages), 1)
        self.assertNotIn("\n", messages[0])
        self.assertNotIn("\r", messages[0])

    async def test_file_read_audit_contains_no_content(self):
        await self._start()
        (self.root / "note.txt").write_text("AUDIT-CONTENT-MARKER")
        with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
            resp = await self.client.get(
                "/v1/files/content?path=note.txt", headers=self.auth)
            self.assertEqual(resp.status, 200)
        joined = "\n".join(logs.output)
        self.assertIn("action=file_read", joined)
        self.assertIn("path=note.txt", joined)
        self.assertNotIn("AUDIT-CONTENT-MARKER", joined)

    async def test_invalid_utf8_and_control_char_names_do_not_break_the_listing(self):
        await self._start()
        raw = os.path.join(os.fsencode(self.root), b"bad\xffname")
        os.close(os.open(raw, os.O_CREAT | os.O_WRONLY, 0o644))
        (self.root / "line\nbreak").write_text("x")
        resp = await self.client.get("/v1/files/list", headers=self.auth)
        self.assertEqual(resp.status, 200)
        body = await resp.text()
        self.assertNotIn("\n", body)
        self.assertIn("\\n", body)
        listing = json.loads(body)
        names = [entry["name"] for entry in listing["entries"]]
        self.assertNotIn("bad\udcffname", names)
        self.assertIn("line\nbreak", names)

    async def test_no_file_extension_is_served_as_an_active_type(self):
        await self._start()
        payloads = {
            "page.html": b"<script>alert(1)</script>",
            "app.js": b"<script>alert(1)</script>",
            "image.svg": b"<svg xmlns='http://www.w3.org/2000/svg'><script/></svg>",
            "data.json": b"<script>alert(1)</script>",
            "doc.xml": b"<script>alert(1)</script>",
            "note.txt": b"<script>alert(1)</script>",
            "pic.png": b"<script>alert(1)</script>",
            "raw.bin": b"\x00\x01\x02",
        }
        for name, payload in payloads.items():
            (self.root / name).write_bytes(payload)
            with self.subTest(name=name):
                resp = await self.client.get(
                    "/v1/files/content?path=" + name, headers=self.auth)
                self.assertEqual(resp.status, 200)
                content_type = resp.headers["Content-Type"].lower()
                for active in ("text/html", "javascript", "svg", "xml"):
                    self.assertNotIn(active, content_type)
                self.assertEqual(resp.headers["X-Content-Type-Options"], "nosniff")
                self.assertEqual(resp.headers["Cache-Control"], "no-store")
        resp = await self.client.get(
            "/v1/files/content?path=pic.png", headers=self.auth)
        self.assertEqual(resp.headers["Content-Type"], "image/png")

    async def test_listing_and_projects_are_json_not_html(self):
        await self._start()
        resp = await self.client.get("/v1/files/list", headers=self.auth)
        self.assertIn("application/json", resp.headers["Content-Type"])
        projects = await self.client.get("/v1/projects", headers=self.auth)
        if projects.status == 200:
            self.assertIn("application/json", projects.headers["Content-Type"])


if __name__ == "__main__":
    unittest.main()
