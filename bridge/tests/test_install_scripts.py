"""Tests for the shell installers' env parsing, backup and systemd unit.

Everything runs in a temporary HOME with fake ``git``/``curl``/``uv``/
``systemctl`` shims: no real service manager, network or user configuration is
touched.  The reference for env files is the bridge itself
(``_load_env_file``/``_env_value``): ``export``, surrounding whitespace,
matching outer quotes, an unquoted ``" #"`` comment and the LAST occurrence of
a key winning.
"""

import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

BRIDGE_DIR = Path(__file__).resolve().parent.parent
INSTALL_SH = BRIDGE_DIR / "install.sh"
INSTALL_BRIDGE_SH = BRIDGE_DIR / "install-bridge.sh"

HAVE_BASH = shutil.which("bash") is not None

SECRET = "install_script_test_key"
REPO_URL = "https://github.com/exclude-barrier/OrsikRelay.git"


def _write_exe(path: Path, text: str) -> None:
    path.write_text(textwrap.dedent(text).lstrip())
    path.chmod(0o755)


def _bash_function(name: str, script: Path = INSTALL_BRIDGE_SH) -> str:
    text = script.read_text()
    start = text.index(f"{name}() {{")
    end = text.index("\n}\n", start) + 3
    return text[start:end]


@unittest.skipUnless(HAVE_BASH, "bash is required")
class InstallBridgeScriptTest(unittest.TestCase):
    """Run ``install-bridge.sh`` against fake tools in a temporary HOME."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.hermes_home = self.root / "hermes"
        self.hermes_home.mkdir()
        self.hermes_env = self.hermes_home / ".env"
        self.write_hermes_env(f"API_SERVER_KEY={SECRET}\n")
        (self.hermes_home / "config.yaml").write_text(
            "approvals:\n  mode: manual\n"
        )

        self.bridge_home = self.home / ".local" / "orsik-mobile-bridge"
        self.bridge_env = self.bridge_home / "bridge.env"
        self.or_src = self.root / "checkout"
        (self.or_src / ".git").mkdir(parents=True)
        (self.or_src / "bridge").mkdir()
        _write_exe(self.or_src / "bridge" / "install.sh", r"""
            #!/usr/bin/env bash
            printf 'install.sh %s\n' "$*" >>"$CALL_LOG"
            if [ "$1" = "--check" ]; then exit 0; fi
            mkdir -p "$BRIDGE_HOME"
            if [ ! -f "$BRIDGE_HOME/bridge.env" ]; then
              cat >"$BRIDGE_HOME/bridge.env" <<EOF2
            BRIDGE_BIND=${FAKE_BIND:-127.0.0.1}
            BRIDGE_PORT=${FAKE_PORT:-9842}
            BRIDGE_UPSTREAM=http://127.0.0.1:8642
            BRIDGE_HERMES_ENV=$HERMES_HOME/.env
            BRIDGE_STATE_DB=${FAKE_STATE_DB:-$BRIDGE_HOME/state.db}
            BRIDGE_PUBLIC_BASE_URL=https://bridge.example.invalid
            EOF2
              chmod 600 "$BRIDGE_HOME/bridge.env"
            fi
            exit 0
            """)

        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "calls.log"
        self.log.write_text("")
        _write_exe(self.bin / "git", r"""
            #!/usr/bin/env bash
            printf 'git %s\n' "$*" >>"$CALL_LOG"
            case "$1" in
              clone) exit 0 ;;
            esac
            if [ "$1" = "-C" ] && [ "$3" = "remote" ]; then
              printf '%s\n' "https://github.com/exclude-barrier/OrsikRelay.git"
            fi
            if [ "$1" = "-C" ] && [ "$3" = "describe" ]; then
              printf 'v-test\n'
            fi
            exit 0
            """)
        _write_exe(self.bin / "curl", "#!/bin/sh\nprintf 200\n")
        _write_exe(self.bin / "uv", "#!/bin/sh\nexit 0\n")
        _write_exe(self.bin / "systemctl", r"""
            #!/usr/bin/env bash
            printf 'systemctl %s\n' "$*" >>"$CALL_LOG"
            exit 0
            """)

    # -- helpers ---------------------------------------------------------

    def write_hermes_env(self, text: str) -> None:
        self.hermes_env.write_text(text)
        self.hermes_env.chmod(0o600)

    def args(self):
        return ["bash", str(INSTALL_BRIDGE_SH)]

    def run_script(self, extra_env=None, args=(), cwd=None):
        env = {
            k: v
            for k, v in os.environ.items()
            if not k.startswith("BRIDGE_") and k not in ("HERMES_HOME", "ORSIK_DIR")
        }
        env.update(
            HOME=str(self.home),
            HERMES_HOME=str(self.hermes_home),
            ORSIK_DIR=str(self.or_src),
            BRIDGE_HOME=str(self.bridge_home),
            PATH=f"{self.bin}:{os.environ['PATH']}",
            CALL_LOG=str(self.log),
        )
        env.update(extra_env or {})
        return subprocess.run(
            [*self.args(), *args],
            env=env,
            cwd=cwd,
            capture_output=True,
            text=True,
            timeout=60,
        )

    def install_fake_cli(self, exit_code=0):
        """An "installed" bridge CLI that records how it was called."""
        cli = self.bridge_home / "venv" / "bin" / "orsik-mobile-bridge"
        cli.parent.mkdir(parents=True, exist_ok=True)
        _write_exe(cli, rf"""
            #!/usr/bin/env bash
            printf 'cli %s\n' "$*" >>"$CALL_LOG"
            printf 'cli-env BRIDGE_STATE_DB=%s\n' "${{BRIDGE_STATE_DB:-}}" >>"$CALL_LOG"
            printf 'cli-env BRIDGE_ENV_FILE=%s\n' "${{BRIDGE_ENV_FILE:-}}" >>"$CALL_LOG"
            exit {exit_code}
            """)
        return cli

    def write_bridge_env(self, state_db_line: str) -> None:
        self.bridge_home.mkdir(parents=True, exist_ok=True)
        self.bridge_home.chmod(0o700)
        self.bridge_env.write_text(
            "# existing install\n"
            f"{state_db_line}\n"
            "BRIDGE_BIND=127.0.0.1\n"
            "BRIDGE_PORT=9842\n"
            "BRIDGE_UPSTREAM=http://127.0.0.1:8642\n"
            f"BRIDGE_HERMES_ENV={self.hermes_env}\n"
            "BRIDGE_PUBLIC_BASE_URL=https://bridge.example.invalid\n"
        )
        self.bridge_env.chmod(0o600)

    def calls(self):
        return self.log.read_text().splitlines()

    # -- B2: the API key check parses like the bridge --------------------

    def test_empty_quoted_api_key_is_refused(self):
        for value in ('""', "''"):
            with self.subTest(value=value):
                self.write_hermes_env(f"API_SERVER_KEY={value}\n")
                result = self.run_script()
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("API_SERVER_KEY is not set", result.stderr)
                self.assertFalse(
                    [line for line in self.calls() if line.startswith("install.sh")],
                    "the script continued after refusing the key",
                )

    def test_exported_api_key_is_accepted(self):
        self.write_hermes_env(f"export API_SERVER_KEY={SECRET}\n")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("install.sh --check", self.log.read_text())

    def test_indented_api_key_is_accepted(self):
        self.write_hermes_env(f"   API_SERVER_KEY={SECRET}\n")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_comment_only_value_is_non_empty_like_the_bridge(self):
        # _env_value strips surrounding whitespace but "# comment" is not an
        # inline comment (that needs a space *before* the '#'), so the bridge
        # accepts it as a (bad) key.  The shell script must agree.
        self.write_hermes_env("API_SERVER_KEY=  # comment\n")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_last_api_key_wins(self):
        self.write_hermes_env(f"API_SERVER_KEY={SECRET}\nAPI_SERVER_KEY=\n")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("API_SERVER_KEY is not set", result.stderr)

    # -- B4: messages use the configured bind and port ------------------

    def test_service_and_tailscale_hint_use_the_configured_bind_and_port(self):
        cases = (("127.0.0.1", "http://127.0.0.1:9123"),
                 ("::1", "http://[::1]:9123"))
        for bind, url in cases:
            with self.subTest(bind=bind):
                self.log.write_text("")
                if self.bridge_home.exists():
                    shutil.rmtree(self.bridge_home)
                result = self.run_script(
                    extra_env={"FAKE_BIND": bind, "FAKE_PORT": "9123"})
                output = result.stdout + result.stderr
                self.assertEqual(result.returncode, 0, output)
                self.assertIn(f"Service orsik-mobile-bridge.service is running on {url}",
                              result.stdout)
                self.assertIn(f"--https=8443 {url}", result.stdout)
                self.assertNotIn("9842", result.stdout)

    # -- B7: pre-update backup uses the resolved state DB ---------------

    def test_backup_uses_the_resolved_quoted_state_db(self):
        self.install_fake_cli()
        real_db = self.root / "data dir" / "state.db"
        real_db.parent.mkdir()
        real_db.write_bytes(b"sqlite")
        self.write_bridge_env(f'export BRIDGE_STATE_DB="{real_db}"')

        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls()
        backup = [line for line in calls if line.startswith("cli admin backup ")]
        self.assertEqual(len(backup), 1, calls)
        self.assertTrue(backup[0].startswith(
            f"cli admin backup {self.bridge_home}/state.db.bak-"), backup[0])
        self.assertIn(f"cli-env BRIDGE_STATE_DB={real_db}", calls)
        self.assertIn("cli-env BRIDGE_ENV_FILE=", calls)
        # The resolved path is passed explicitly, not the bridge.env override.
        self.assertNotIn(f"cli-env BRIDGE_ENV_FILE={self.bridge_env}", calls)

    def test_relative_backup_path_is_resolved_against_service_home(self):
        self.install_fake_cli()
        relative_db = Path("data dir") / "state.db"
        real_db = self.home / relative_db
        real_db.parent.mkdir()
        real_db.write_bytes(b"sqlite")
        self.write_bridge_env(f'BRIDGE_STATE_DB="{relative_db}"')
        caller = self.root / "caller"
        caller.mkdir()

        for caller_has_store in (False, True):
            with self.subTest(caller_has_store=caller_has_store):
                self.log.write_text("")
                if caller_has_store:
                    decoy = caller / relative_db
                    decoy.parent.mkdir()
                    decoy.write_bytes(b"unrelated")

                result = self.run_script(cwd=caller)

                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                calls = self.calls()
                backups = [line for line in calls
                           if line.startswith("cli admin backup ")]
                self.assertEqual(len(backups), 1, calls)
                self.assertIn(f"cli-env BRIDGE_STATE_DB={real_db}", calls)
                install = next(line for line in calls
                               if line.startswith("install.sh --update"))
                self.assertLess(calls.index(backups[0]), calls.index(install))
                self.assertNotIn("skipping the pre-update backup", result.stdout)

    def test_backup_is_skipped_when_the_state_db_does_not_exist(self):
        self.install_fake_cli()
        missing = self.root / "missing" / "state.db"
        self.write_bridge_env(f"BRIDGE_STATE_DB={missing}")

        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"No device store at {missing}", result.stdout)
        self.assertIn("skipping the pre-update backup", result.stdout)
        self.assertFalse([line for line in self.calls()
                          if line.startswith("cli admin backup")])
        self.assertFalse(missing.exists(), "the backup created a new empty store")

    def test_backup_failure_aborts_the_update(self):
        self.install_fake_cli(exit_code=1)
        db = self.root / "state.db"
        db.write_bytes(b"sqlite")
        self.write_bridge_env(f"BRIDGE_STATE_DB={db}")

        result = self.run_script()
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("could not back up the device store", result.stderr)
        self.assertFalse([line for line in self.calls()
                          if line.startswith("install.sh")])

    def test_exported_state_db_survives_the_config_step(self):
        self.install_fake_cli()
        db = self.bridge_home / "custom.db"
        db.write_bytes(b"sqlite")
        self.write_bridge_env(f"  export BRIDGE_STATE_DB={db}")

        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        text = self.bridge_env.read_text()
        self.assertEqual(text.count("BRIDGE_STATE_DB="), 1, text)
        self.assertIn(f"BRIDGE_STATE_DB={db}", text)


@unittest.skipUnless(HAVE_BASH, "bash is required")
class SetEnvParsingTest(unittest.TestCase):
    """``set_env``/``set_default`` recognise the same key forms as the bridge."""

    def _run(self, env_text: str, call: str):
        with tempfile.TemporaryDirectory() as d:
            env_file = Path(d) / "bridge.env"
            env_file.write_text(env_text)
            env_file.chmod(0o600)
            script = (
                "set -euo pipefail\n"
                "die() { printf '%s\\n' \"$*\" >&2; exit 1; }\n"
                "ENV_FILE=\"$TEST_ENV\"\n"
                + _bash_function("env_value") + "\n"
                + _bash_function("set_env") + "\n"
                + _bash_function("set_default") + "\n"
                + call + "\n"
            )
            env = dict(os.environ, TEST_ENV=str(env_file))
            result = subprocess.run(
                ["bash", "-c", script], env=env, capture_output=True,
                text=True, timeout=30)
            return result, env_file.read_text()

    def test_default_is_not_appended_when_the_key_is_exported(self):
        for text in ("export BRIDGE_STATE_DB=/srv/keep.db\n",
                     "   BRIDGE_STATE_DB=/srv/keep.db\n",
                     "  export   BRIDGE_STATE_DB=/srv/keep.db\n"):
            with self.subTest(text=text):
                result, output = self._run(
                    text, 'set_default BRIDGE_STATE_DB /default/state.db')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(output, text)

    def test_set_env_rewrites_exported_and_indented_lines(self):
        for text in ("export BRIDGE_PORT=1111\n",
                     "   BRIDGE_PORT=1111\n"):
            with self.subTest(text=text):
                result, output = self._run(text, 'set_env BRIDGE_PORT 2222')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(output, "BRIDGE_PORT=2222\n")

    def test_set_env_replaces_all_occurrences(self):
        result, output = self._run(
            "BRIDGE_PORT=1\nexport BRIDGE_PORT=2\n",
            'set_env BRIDGE_PORT 3')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output, "BRIDGE_PORT=3\nBRIDGE_PORT=3\n")

    def test_env_value_last_occurrence_wins(self):
        result, _ = self._run(
            'BRIDGE_PORT=1\nexport BRIDGE_PORT="2"\n',
            'env_value "$ENV_FILE" BRIDGE_PORT')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "2\n")


@unittest.skipUnless(HAVE_BASH, "bash is required")
class SystemdUnitGenerationTest(unittest.TestCase):
    """The unit's ReadWritePaths follows a state DB outside BRIDGE_HOME."""

    def _run_install(self, state_db_line: str):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        home = root / "home"
        home.mkdir()
        bridge_home = home / "bridge"
        bridge_home.mkdir()
        bridge_home.chmod(0o700)
        hermes_env = root / "hermes.env"
        hermes_env.write_text(f"API_SERVER_KEY={SECRET}\n")
        hermes_env.chmod(0o600)
        bridge_env = bridge_home / "bridge.env"
        bridge_env.write_text(
            "BRIDGE_BIND=127.0.0.1\n"
            "BRIDGE_PORT=9842\n"
            "BRIDGE_UPSTREAM=http://127.0.0.1:8642\n"
            f"BRIDGE_HERMES_ENV={hermes_env}\n"
            f"{state_db_line}\n"
            "BRIDGE_PUBLIC_BASE_URL=https://bridge.example.invalid\n"
        )
        bridge_env.chmod(0o600)

        source = root / "source"
        source.mkdir()
        (source / "pyproject.toml").write_text(
            "[project]\nname = 'bridge-test'\nversion = '0'\n")
        (source / "uv.lock").write_text("version = 1\n")

        bin_dir = root / "bin"
        bin_dir.mkdir()
        _write_exe(bin_dir / "uv", "#!/bin/sh\nexit 0\n")
        _write_exe(bin_dir / "systemctl", "#!/bin/sh\nexit 0\n")

        config_home = root / "config"
        env = {k: v for k, v in os.environ.items()
               if not k.startswith("BRIDGE_")}
        env.update(HOME=str(home), XDG_CONFIG_HOME=str(config_home),
                   BRIDGE_HOME=str(bridge_home),
                   PATH=f"{bin_dir}:{os.environ['PATH']}")
        result = subprocess.run(
            ["bash", str(INSTALL_SH), "--systemd", str(source)],
            env=env, capture_output=True, text=True, timeout=60)
        unit = config_home / "systemd" / "user" / "orsik-mobile-bridge.service"
        return result, unit, root, bridge_home

    def test_external_state_db_directory_is_writable(self):
        with tempfile.TemporaryDirectory() as d:
            external = Path(d) / "state dir"
            external.mkdir()
            db = external / "orsik.db"
            for line in (f'BRIDGE_STATE_DB="{db}"', f"BRIDGE_STATE_DB={db}"):
                with self.subTest(line=line):
                    result, unit, root, bridge_home = self._run_install(line)
                    self.assertEqual(result.returncode, 0,
                                     result.stdout + result.stderr)
                    text = unit.read_text()
                    self.assertIn(
                        f'ReadWritePaths="{bridge_home}" "{external}"', text)

    def test_systemd_special_characters_are_escaped(self):
        with tempfile.TemporaryDirectory() as d:
            external = Path(d) / 'state %"dir'
            external.mkdir()
            db = external / "orsik.db"
            result, unit, root, bridge_home = self._run_install(
                f'BRIDGE_STATE_DB="{db}"')
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            escaped = (str(external).replace("\\", "\\\\")
                       .replace('"', '\\"').replace("%", "%%"))
            self.assertIn(f'ReadWritePaths="{bridge_home}" "{escaped}"',
                          unit.read_text())

    def test_internal_state_db_adds_no_extra_path(self):
        # The default path is derived from BRIDGE_HOME inside the run.
        result, unit, root, bridge_home = self._run_install("BRIDGE_STATE_DB=")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        text = unit.read_text()
        self.assertIn(f'ReadWritePaths="{bridge_home}"', text)
        self.assertEqual(text.count("ReadWritePaths="), 1)
        self.assertNotIn("state.db", text)


if __name__ == "__main__":
    unittest.main()
