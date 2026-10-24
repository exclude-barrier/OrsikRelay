"""Preflight tests for ``install.sh --check`` (stdlib unittest, loopback only).

Each test builds a throwaway BRIDGE_HOME next to a loopback Hermes health stub
and runs the real installer in check mode. The check must never mutate the
installation it inspects and must never print secret values.
"""

import os
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

INSTALL_SH = Path(__file__).resolve().parent.parent / "install.sh"
INSTALL_BRIDGE_SH = Path(__file__).resolve().parent.parent / "install-bridge.sh"

HAVE_BASH = shutil.which("bash") is not None
HAVE_CURL = shutil.which("curl") is not None
HAVE_SS = shutil.which("ss") is not None
HAVE_UV = shutil.which("uv") is not None

SECRET = "test_key_not_secret"


def free_port():
    """Return a currently unused loopback TCP port."""
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class _HealthHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            code, body = getattr(self.server, "health_status", 200), b"ok"
        else:
            code, body = 404, b"not found"
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


class _HealthServer(ThreadingHTTPServer):
    daemon_threads = True
    health_status = 200


class HealthStub:
    """Loopback HTTP server whose ``/health`` answers with a fixed status."""

    def __init__(self, status=200):
        self.server = _HealthServer(("127.0.0.1", 0), _HealthHandler)
        self.server.health_status = status
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()

    def set_status(self, status):
        self.server.health_status = status

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    @property
    def url(self):
        host, port = self.server.server_address[:2]
        return f"http://{host}:{port}"


@unittest.skipUnless(HAVE_BASH and HAVE_CURL, "bash and curl are required")
class InstallCheckTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.home = self.root / "home"

        (self.home / "venv" / "bin").mkdir(parents=True)
        cli = self.home / "venv" / "bin" / "orsik-mobile-bridge"
        cli.write_text("#!/bin/sh\nexit 0\n")
        cli.chmod(0o755)
        self.home.chmod(0o700)

        self.hermes_env = self.root / "hermes.env"
        self.hermes_env.write_text(f"API_SERVER_KEY={SECRET}\n")
        self.hermes_env.chmod(0o600)

        self.stub = HealthStub(status=200)
        self.stub.start()
        self.addCleanup(self.stub.stop)

        self.port = free_port()
        self.write_env()

    # -- helpers ---------------------------------------------------------

    def write_env(
        self,
        bind="127.0.0.1",
        port=None,
        upstream=None,
        hermes_env=None,
        public_base_url="https://bridge.example.invalid",
    ):
        env_file = self.home / "bridge.env"
        env_file.write_text(
            "# preflight test env\n"
            "\n"
            f"BRIDGE_BIND={bind}\n"
            f"BRIDGE_PORT={self.port if port is None else port}\n"
            f"BRIDGE_UPSTREAM={self.stub.url if upstream is None else upstream}\n"
            f"BRIDGE_HERMES_ENV={self.hermes_env if hermes_env is None else hermes_env}\n"
            "BRIDGE_STATE_DB=\n"
            f"BRIDGE_PUBLIC_BASE_URL={public_base_url}\n"
        )
        env_file.chmod(0o600)
        return env_file

    def run_check(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith("BRIDGE_")}
        env["BRIDGE_HOME"] = str(self.home)
        env["HOME"] = str(self.root)
        return subprocess.run(
            ["bash", str(INSTALL_SH), "--check"],
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
        )

    def snapshot(self):
        """Sorted (absolute path, mode, mtime_ns) for everything we created."""
        entries = []
        for target in (self.home, self.hermes_env, *sorted(self.home.rglob("*"))):
            info = target.lstat()
            entries.append((str(target), stat.S_IMODE(info.st_mode), info.st_mtime_ns))
        return sorted(entries)

    def assertNoSecret(self, result):
        combined = result.stdout + result.stderr
        self.assertNotIn(SECRET, combined, "check leaked a secret value")

    # -- cases -----------------------------------------------------------

    def test_all_good_passes(self):
        before = self.snapshot()
        result = self.run_check()
        self.assertNoSecret(result)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PREFLIGHT: PASS", result.stdout)
        self.assertNotIn("FAIL", result.stdout)
        self.assertEqual(before, self.snapshot(), "check mode changed the install")

    def test_approvals_mode_manual_is_ok(self):
        (self.root / "config.yaml").write_text(
            "model:\n  provider: x\napprovals:\n  timeout: 300\n  mode: manual  # strict\n"
        )
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("OK   approvals_mode", result.stdout)

    def test_approvals_mode_smart_or_missing_warns(self):
        # No config.yaml next to the Hermes env file: the core default (smart) applies.
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("WARN approvals_mode", result.stdout)
        (self.root / "config.yaml").write_text("approvals:\n  mode: smart\nother:\n  mode: manual\n")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("WARN approvals_mode: approvals.mode is smart", result.stdout)

    def test_world_readable_env_fails(self):
        (self.home / "bridge.env").chmod(0o644)
        result = self.run_check()
        self.assertNoSecret(result)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL env_mode", result.stdout)

    def test_hermes_env_without_key_fails(self):
        bare = self.root / "bare.env"
        bare.write_text("SOMETHING_ELSE=1\n")
        bare.chmod(0o600)
        self.write_env(hermes_env=bare)
        result = self.run_check()
        self.assertNoSecret(result)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL hermes_env", result.stdout)

    def test_plain_http_base_url_fails(self):
        self.write_env(public_base_url="http://x")
        result = self.run_check()
        self.assertNoSecret(result)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL public_base_url", result.stdout)

    def test_a_port_the_bridge_cannot_use_fails(self):
        for port in ("abc", "0", "99999"):
            with self.subTest(port=port):
                self.write_env(port=port)
                result = self.run_check()
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("FAIL port", result.stdout)

    def test_the_terminal_switch_is_checked_like_the_bridge_reads_it(self):
        # The config step runs the installed bridge's python; here the repo's.
        python = self.home / "venv" / "bin" / "python"
        python.write_text(f"#!/bin/sh\nPYTHONPATH={INSTALL_SH.parent} exec {sys.executable} \"$@\"\n")
        python.chmod(0o755)
        tools = self.root / "tools"
        tools.mkdir()
        for name in ("tmux", "setsid"):
            (tools / name).write_text("#!/bin/sh\nexit 0\n")
            (tools / name).chmod(0o755)
        env_file = self.home / "bridge.env"
        base = env_file.read_text()
        for value, passes in (("0", True), ("1", True), ("yes", False)):
            with self.subTest(value=value):
                env_file.write_text(base + f"BRIDGE_TERMINAL={value}\n")
                env = {k: v for k, v in os.environ.items() if not k.startswith("BRIDGE_")}
                env.update(BRIDGE_HOME=str(self.home), HOME=str(self.root),
                           PATH=f"{tools}:{os.environ['PATH']}")
                result = subprocess.run(["bash", str(INSTALL_SH), "--check"], env=env,
                                        capture_output=True, text=True, timeout=30)
                output = result.stdout + result.stderr
                if passes:
                    self.assertIn("OK   config", result.stdout, output)
                else:
                    self.assertIn("FAIL config", result.stdout, output)
                    self.assertIn("BRIDGE_TERMINAL", result.stdout, output)

    def test_the_files_switch_and_root_are_checked_like_the_bridge_reads_them(self):
        python = self.home / "venv" / "bin" / "python"
        python.write_text(f"#!/bin/sh\nPYTHONPATH={INSTALL_SH.parent} exec {sys.executable} \"$@\"\n")
        python.chmod(0o755)
        projects = self.root / "projects"
        projects.mkdir()
        env_file = self.home / "bridge.env"
        base = env_file.read_text()
        cases = (
            ("BRIDGE_FILES=0\n", True),
            (f"BRIDGE_FILES=1\nBRIDGE_FILES_ROOT={projects}\n", True),
            ("BRIDGE_FILES=1\n", False),
            ("BRIDGE_FILES=yes\n", False),
            # The root would contain the bridge home and the Hermes env file.
            (f"BRIDGE_FILES=1\nBRIDGE_FILES_ROOT={self.root}\n", False),
        )
        for extra, passes in cases:
            with self.subTest(extra=extra):
                env_file.write_text(base + extra)
                result = self.run_check()
                output = result.stdout + result.stderr
                self.assertIn("OK   config" if passes else "FAIL config", result.stdout, output)

    def test_an_empty_upstream_line_fails_like_the_bridge(self):
        self.write_env(upstream="")
        result = self.run_check()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL upstream", result.stdout)

    def test_quoted_values_are_read_like_the_bridge_reads_them(self):
        self.write_env(upstream=f'"{self.stub.url}"', public_base_url="'https://bridge.example.invalid' ")
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("OK   upstream", result.stdout)

    def test_public_upstream_fails_and_skips_health(self):
        self.write_env(upstream="http://example.com:8899")
        result = self.run_check()
        self.assertNoSecret(result)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL upstream", result.stdout)
        self.assertIn("WARN hermes_health", result.stdout)

    def test_unhealthy_hermes_fails(self):
        self.stub.set_status(503)
        result = self.run_check()
        self.assertNoSecret(result)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL hermes_health", result.stdout)

    @unittest.skipUnless(HAVE_SS, "ss is required for the port probe")
    def test_busy_port_warns(self):
        occupied = free_port()
        with socket.socket() as busy:
            busy.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            busy.bind(("127.0.0.1", occupied))
            busy.listen(1)
            self.write_env(port=occupied)
            result = self.run_check()
        self.assertNoSecret(result)
        self.assertIn("WARN port", result.stdout)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_secret_never_printed(self):
        scenarios = [("good", self.run_check())]
        self.write_env(upstream="http://example.com:8899")
        scenarios.append(("bad upstream", self.run_check()))
        self.write_env(public_base_url="http://x")
        scenarios.append(("bad base url", self.run_check()))
        (self.home / "bridge.env").chmod(0o644)
        scenarios.append(("bad env mode", self.run_check()))
        for label, result in scenarios:
            with self.subTest(label):
                self.assertNoSecret(result)

    def test_check_does_not_modify_install(self):
        before = self.snapshot()
        self.run_check()
        self.assertEqual(before, self.snapshot())


class SystemdUnitSourceTest(unittest.TestCase):
    def test_unit_sets_restrictive_umask(self):
        self.assertIn("\nUMask=0077\n", INSTALL_SH.read_text())


def _bash_function(name: str) -> str:
    text = INSTALL_BRIDGE_SH.read_text()
    start = text.index(f"{name}() {{")
    end = text.index("\n}\n", start) + 3
    return text[start:end]


@unittest.skipUnless(HAVE_BASH, "bash is required")
class SetEnvTest(unittest.TestCase):
    """``set_env`` writes values as data, never as a sed expression."""

    def _run(self, env_file: Path, value: str):
        script = (
            "set -euo pipefail\n"
            "die() { printf '%s\\n' \"$*\" >&2; exit 1; }\n"
            "ENV_FILE=\"$TEST_ENV\"\n"
            + _bash_function("set_env")
            + "\nset_env BRIDGE_HERMES_ENV \"$TEST_VALUE\"\n"
        )
        env = dict(os.environ, TEST_ENV=str(env_file), TEST_VALUE=value)
        return subprocess.run(
            ["bash", "-c", script], env=env, capture_output=True, text=True, timeout=30)

    def test_special_characters_are_written_literally(self):
        with tempfile.TemporaryDirectory() as d:
            env_file = Path(d) / "bridge.env"
            env_file.write_text("BRIDGE_HERMES_ENV=/old\n")
            env_file.chmod(0o600)
            value = r"/srv/a&b|c\d /x"
            result = self._run(env_file, value)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(env_file.read_text(), f"BRIDGE_HERMES_ENV={value}\n")
            self.assertEqual(stat.S_IMODE(env_file.stat().st_mode), 0o600)

    def test_missing_key_is_appended(self):
        with tempfile.TemporaryDirectory() as d:
            env_file = Path(d) / "bridge.env"
            env_file.write_text("BRIDGE_BIND=127.0.0.1\n")
            env_file.chmod(0o600)
            result = self._run(env_file, "https://example.invalid")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                env_file.read_text(),
                "BRIDGE_BIND=127.0.0.1\nBRIDGE_HERMES_ENV=https://example.invalid\n",
            )

    def test_newline_value_is_refused_and_file_untouched(self):
        with tempfile.TemporaryDirectory() as d:
            env_file = Path(d) / "bridge.env"
            original = "BRIDGE_HERMES_ENV=/old\n"
            env_file.write_text(original)
            env_file.chmod(0o600)
            result = self._run(env_file, "/a\nBRIDGE_BIND=0.0.0.0")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("must not contain a newline", result.stderr)
            self.assertEqual(env_file.read_text(), original)


@unittest.skipUnless(HAVE_BASH and HAVE_UV, "bash and uv are required")
class InstallPrerequisiteTest(unittest.TestCase):
    """A normal install must refuse to start without its prerequisites."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.source = self.root / "bridge"
        self.source.mkdir()
        (self.source / "pyproject.toml").write_text(
            "[project]\nname = 'bridge-test'\nversion = '0'\n"
        )
        (self.source / "uv.lock").write_text("version = 1\n")
        self.home = self.root / "home-under-test"

    def run_install(self, path):
        env = {k: v for k, v in os.environ.items() if not k.startswith("BRIDGE_")}
        env["BRIDGE_HOME"] = str(self.home)
        env["HOME"] = str(self.root)
        env["PATH"] = path
        return subprocess.run(
            [shutil.which("bash") or "bash", str(INSTALL_SH), str(self.source)],
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
        )

    def test_missing_uv_aborts_before_touching_bridge_home(self):
        result = self.run_install("/nonexistent-bin")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("uv", result.stderr.lower())
        self.assertFalse(
            self.home.exists(), "installer created BRIDGE_HOME without uv present"
        )


@unittest.skipUnless(HAVE_BASH, "bash is required")
class CheckOriginTest(unittest.TestCase):
    """An existing checkout is only reused when it points at the official repo."""

    def _run(self, origin: str | None):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "init", "-q", d], check=True)
            if origin is not None:
                subprocess.run(["git", "-C", d, "remote", "add", "origin", origin], check=True)
            script = (
                "set -euo pipefail\n"
                "die() { printf '%s\\n' \"$*\" >&2; exit 1; }\n"
                "REPO_URL=https://github.com/exclude-barrier/OrsikRelay.git\n"
                "ORSIK_DIR=\"$TEST_DIR\"\n"
                + _bash_function("check_origin")
                + "\ncheck_origin\n"
            )
            env = dict(os.environ, TEST_DIR=d)
            return subprocess.run(
                ["bash", "-c", script], env=env, capture_output=True, text=True, timeout=30)

    def test_official_origins_are_accepted(self):
        for origin in ("https://github.com/exclude-barrier/OrsikRelay",
                       "https://github.com/exclude-barrier/OrsikRelay.git",
                       "git@github.com:exclude-barrier/OrsikRelay.git"):
            result = self._run(origin)
            self.assertEqual(result.returncode, 0, (origin, result.stderr))

    def test_foreign_or_missing_origin_is_refused(self):
        for origin in ("https://github.com/attacker/OrsikRelay.git", None):
            result = self._run(origin)
            self.assertNotEqual(result.returncode, 0, origin)
            self.assertIn("is not a checkout of", result.stderr)
