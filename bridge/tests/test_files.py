"""Read-only files and projects: off by default, never outside the root,
never the bridge's own data, no program from a repository's config."""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from aiohttp.test_utils import TestClient, TestServer

from orsik_mobile_bridge import core, files
from orsik_mobile_bridge import server as bridge_server

HAVE_GIT = shutil.which("git") is not None


class RootTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        (self.base / "projects").mkdir()
        (self.base / "bridge").mkdir()

    def test_switch_values(self):
        self.assertFalse(files.parse_enabled(None))
        self.assertFalse(files.parse_enabled("0"))
        self.assertTrue(files.parse_enabled("1"))
        for raw in ("yes", "true", "2"):
            with self.assertRaises(SystemExit):
                files.parse_enabled(raw)

    def test_the_root_must_be_an_existing_absolute_directory(self):
        for raw in (None, "", "relative/dir", str(self.base / "missing"), "/"):
            with self.assertRaises(SystemExit, msg=raw):
                files.resolve_root(raw, [])
        self.assertEqual(
            files.resolve_root(str(self.base / "projects"), []), os.path.realpath(self.base / "projects"))

    def test_a_root_containing_bridge_data_is_refused(self):
        with self.assertRaises(SystemExit):
            files.resolve_root(str(self.base), [str(self.base / "bridge")])
        with self.assertRaises(SystemExit):
            files.resolve_root(str(self.base / "bridge"), [str(self.base / "bridge")])
        # A sibling is fine.
        files.resolve_root(str(self.base / "projects"), [str(self.base / "bridge")])

    def test_the_env_protects_the_bridge_home_env_file_and_hermes_home(self):
        hermes = self.base / "hermes"
        hermes.mkdir()
        env = {
            "BRIDGE_FILES": "1",
            "BRIDGE_STATE_DB": str(self.base / "bridge" / "state.db"),
            "BRIDGE_HERMES_ENV": str(hermes / ".env"),
            "BRIDGE_ENV_FILE": str(self.base / "bridge" / "bridge.env"),
        }
        for root in (self.base, hermes, self.base / "bridge"):
            with self.assertRaises(SystemExit, msg=str(root)):
                bridge_server._files_root_from_env({**env, "BRIDGE_FILES_ROOT": str(root)})
        self.assertEqual(
            bridge_server._files_root_from_env({**env, "BRIDGE_FILES_ROOT": str(self.base / "projects")}),
            os.path.realpath(self.base / "projects"))
        self.assertIsNone(bridge_server._files_root_from_env({**env, "BRIDGE_FILES": "0"}))


class PathTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.root = self.base / "root"
        (self.root / "sub").mkdir(parents=True)
        (self.root / "sub" / "a.txt").write_text("hello")
        (self.base / "secret.txt").write_text("outside")
        os.symlink(self.base / "secret.txt", self.root / "out-link")
        os.symlink(self.base, self.root / "out-dir")
        os.symlink(self.root / "sub" / "a.txt", self.root / "in-link")
        self.real = os.path.realpath(self.root)

    def test_traversal_and_malformed_paths_are_refused(self):
        for bad in ("..", "../secret.txt", "sub/../../secret.txt", "/etc/passwd", "sub/./a.txt",
                    "a\x00b", "x" * (files.MAX_PATH_CHARS + 1)):
            with self.assertRaises(files.PathRefused, msg=repr(bad[:40])):
                files.resolve(self.real, bad)

    def test_symlinks_out_of_the_root_are_refused_and_listed_as_other(self):
        for bad in ("out-link", "out-dir", "out-dir/secret.txt"):
            with self.assertRaises(files.PathRefused, msg=bad):
                files.read_file(self.real, bad)
        listing = files.list_dir(self.real, "")
        kinds = {entry["name"]: entry["type"] for entry in listing["entries"]}
        self.assertEqual(kinds["out-link"], "other")
        self.assertEqual(kinds["out-dir"], "other")
        self.assertEqual(kinds["in-link"], "file")
        self.assertEqual(kinds["sub"], "dir")

    def test_listing_is_sorted_folders_first_and_bounded(self):
        for name in ("b.txt", "A.txt"):
            (self.root / name).write_text("x")
        names = [entry["name"] for entry in files.list_dir(self.real, "")["entries"]]
        self.assertEqual(names[:1], ["sub"])
        self.assertLess(names.index("A.txt"), names.index("b.txt"))
        with mock.patch.object(files, "MAX_ENTRIES", 2):
            listing = files.list_dir(self.real, "")
        self.assertTrue(listing["truncated"])
        self.assertEqual(len(listing["entries"]), 2)

    def test_reading_returns_bytes_and_refuses_folders_fifos_and_big_files(self):
        data, name = files.read_file(self.real, "sub/a.txt")
        self.assertEqual((data, name), (b"hello", "a.txt"))
        with self.assertRaises(files.NotFound):
            files.read_file(self.real, "sub")
        with self.assertRaises(files.NotFound):
            files.read_file(self.real, "")
        with self.assertRaises(files.NotFound):
            files.read_file(self.real, "missing")
        os.mkfifo(self.root / "pipe")
        with self.assertRaises(files.NotFound):
            files.read_file(self.real, "pipe")
        (self.root / "big.bin").write_bytes(b"x" * 11)
        with mock.patch.object(files, "MAX_FILE_BYTES", 10):
            with self.assertRaises(files.TooLarge):
                files.read_file(self.real, "big.bin")

    def test_content_types_are_a_closed_set(self):
        self.assertEqual(files.content_type("a.PNG", b"\x89PNG"), "image/png")
        self.assertEqual(files.content_type("a.txt", "ä".encode()), "text/plain; charset=utf-8")
        self.assertEqual(files.content_type("a.html", b"<script>"), "text/plain; charset=utf-8")
        self.assertEqual(files.content_type("a.bin", b"\x00\x01"), "application/octet-stream")
        self.assertEqual(files.content_type("a.txt", b"\xff\xfe"), "application/octet-stream")


def _git(repo: Path, *args: str) -> None:
    subprocess.run(("git", "-C", str(repo), *args), check=True, capture_output=True,
                   env={**os.environ, "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1"})


@unittest.skipUnless(HAVE_GIT, "git is required")
class ProjectsTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name) / "root"
        self.root.mkdir()
        self.real = os.path.realpath(self.root)

    def _repo(self, relative: str) -> Path:
        repo = self.root / relative
        repo.mkdir(parents=True)
        _git(repo, "init", "-q", "-b", "main")
        _git(repo, "-c", "user.email=t@example.invalid", "-c", "user.name=t", "commit", "-q",
             "--allow-empty", "-m", "first commit")
        return repo

    def test_repositories_up_to_two_levels_with_branch_commit_and_state(self):
        self._repo("alpha")
        beta = self._repo("group/beta")
        (beta / "f.txt").write_text("x")
        _git(beta, "add", "f.txt")
        self._repo("a/b/too-deep")
        (self.root / ".hidden").mkdir()
        listing = files.list_projects(self.real)
        by_name = {project["name"]: project for project in listing["projects"]}
        self.assertEqual(set(by_name), {"alpha", "beta"})
        self.assertEqual(by_name["alpha"]["path"], "alpha")
        self.assertEqual(by_name["beta"]["path"], "group/beta")
        self.assertEqual(by_name["alpha"]["branch"], "main")
        self.assertIs(by_name["alpha"]["dirty"], False)
        self.assertIs(by_name["beta"]["dirty"], True)
        self.assertEqual(by_name["alpha"]["last_commit"]["subject"], "first commit")
        self.assertFalse(listing["truncated"])

    def test_no_program_named_in_a_repository_config_runs(self):
        repo = self._repo("evil")
        marker = self.root.parent / "ran"
        hook = self.root.parent / "hook.sh"
        hook.write_text(f"#!/bin/sh\ntouch {marker}\n")
        hook.chmod(0o755)
        _git(repo, "config", "core.fsmonitor", str(hook))
        _git(repo, "config", "core.hooksPath", str(self.root.parent))
        files.list_projects(self.real)
        self.assertFalse(marker.exists(), "a program from the repository config ran")

    def test_the_number_of_projects_is_bounded(self):
        for index in range(3):
            self._repo(f"p{index}")
        with mock.patch.object(files, "MAX_PROJECTS", 2):
            listing = files.list_projects(self.real)
        self.assertEqual(len(listing["projects"]), 2)
        self.assertTrue(listing["truncated"])


class FilesRouteTest(unittest.IsolatedAsyncioTestCase):
    async def _start(self, enabled=True):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name) / "root"
        (self.root / "sub").mkdir(parents=True)
        (self.root / "sub" / "a.txt").write_text("hello")
        (self.root / "img.png").write_bytes(b"\x89PNG\r\n")
        self.state = bridge_server.BridgeState(
            upstream="http://127.0.0.1:9", master_key="master_key_test",
            files_root=os.path.realpath(self.root) if enabled else None)
        self.client = TestClient(TestServer(bridge_server.create_app(self.state)))
        await self.client.start_server()
        self.addAsyncCleanup(self.client.close)
        self.addCleanup(self.state.store.close)
        issued = self.state.codes.issue()
        resp = await self.client.post("/v1/devices/pair", json={"code": issued[0]})
        self.auth = {"Authorization": "Bearer " + (await resp.json())["device_key"]}

    async def test_off_by_default_and_not_advertised(self):
        await self._start(enabled=False)
        for path in ("/v1/files/list", "/v1/files/content?path=x", "/v1/projects"):
            resp = await self.client.get(path, headers=self.auth)
            self.assertEqual(resp.status, 403, path)
            self.assertEqual((await resp.json())["error"]["code"], "endpoint_out_of_scope")
        caps = await (await self.client.get("/v1/capabilities", headers=self.auth)).json()
        self.assertIs(caps["features"]["files"], False)
        self.assertNotIn("files_list", caps["endpoints"])

    async def test_capabilities_name_the_three_routes(self):
        await self._start()
        caps = await (await self.client.get("/v1/capabilities", headers=self.auth)).json()
        self.assertIs(caps["features"]["files"], True)
        self.assertIs(caps["features"]["projects"], True)
        self.assertEqual(caps["endpoints"]["files_list"], {"method": "GET", "path": "/v1/files/list"})
        self.assertEqual(caps["endpoints"]["files_content"], {"method": "GET", "path": "/v1/files/content"})
        self.assertEqual(caps["endpoints"]["projects"], {"method": "GET", "path": "/v1/projects"})

    async def test_token_query_body_and_method_are_checked(self):
        await self._start()
        self.assertEqual((await self.client.get("/v1/files/list")).status, 401)
        for path in ("/v1/files/list?path=a&path=b", "/v1/files/list?x=1", "/v1/projects?path=a"):
            self.assertEqual((await self.client.get(path, headers=self.auth)).status, 403, path)
        self.assertEqual((await self.client.get("/v1/files/list", headers=self.auth, data=b"x")).status, 400)
        self.assertNotEqual((await self.client.post("/v1/files/list", headers=self.auth)).status, 200)
        self.assertNotEqual((await self.client.head("/v1/files/content?path=sub/a.txt", headers=self.auth)).status, 200)

    async def test_listing_and_reading(self):
        await self._start()
        listing = await (await self.client.get("/v1/files/list", headers=self.auth)).json()
        self.assertEqual(listing["object"], "orsik.files.list")
        self.assertEqual(listing["path"], "")
        self.assertEqual([entry["name"] for entry in listing["entries"]], ["sub", "img.png"])
        sub = await (await self.client.get("/v1/files/list?path=sub", headers=self.auth)).json()
        self.assertEqual(sub["entries"][0]["size"], 5)
        resp = await self.client.get("/v1/files/content?path=sub/a.txt", headers=self.auth)
        self.assertEqual(resp.status, 200)
        self.assertEqual(await resp.read(), b"hello")
        self.assertEqual(resp.headers["Content-Type"], "text/plain; charset=utf-8")
        self.assertEqual(resp.headers["X-Content-Type-Options"], "nosniff")
        self.assertEqual(resp.headers["Cache-Control"], "no-store")
        image = await self.client.get("/v1/files/content?path=img.png", headers=self.auth)
        self.assertEqual(image.headers["Content-Type"], "image/png")

    async def test_errors_are_closed(self):
        await self._start()
        cases = [
            ("/v1/files/list?path=..", 403, "path_not_allowed"),
            ("/v1/files/content?path=../x", 403, "path_not_allowed"),
            ("/v1/files/list?path=missing", 404, "not_found"),
            ("/v1/files/list?path=sub/a.txt", 404, "not_found"),
            ("/v1/files/content?path=sub", 404, "not_found"),
        ]
        for path, status, code in cases:
            resp = await self.client.get(path, headers=self.auth)
            self.assertEqual(resp.status, status, path)
            self.assertEqual((await resp.json())["error"]["code"], code, path)
        (self.root / "big").write_bytes(b"x" * 11)
        with mock.patch.object(files, "MAX_FILE_BYTES", 10):
            resp = await self.client.get("/v1/files/content?path=big", headers=self.auth)
        self.assertEqual(resp.status, 413)

    async def test_a_file_read_is_audited_without_content(self):
        await self._start()
        with self.assertLogs("orsik_mobile_bridge.audit", level="INFO") as logs:
            await self.client.get("/v1/files/content?path=sub/a.txt", headers=self.auth)
        joined = "\n".join(logs.output)
        self.assertIn("action=file_read", joined)
        self.assertIn("path=sub/a.txt", joined)
        self.assertNotIn("hello", joined)

    @unittest.skipUnless(HAVE_GIT, "git is required")
    async def test_projects_route_shares_one_snapshot(self):
        await self._start()
        with mock.patch.object(files, "list_projects", return_value={"object": "orsik.projects.list", "projects": [], "truncated": False}) as listed:
            for _ in range(3):
                resp = await self.client.get("/v1/projects", headers=self.auth)
                self.assertEqual(resp.status, 200)
            listed.assert_called_once()


if __name__ == "__main__":
    unittest.main()
