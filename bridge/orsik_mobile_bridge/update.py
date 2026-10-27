"""Explicit local update using the packaged, existing bootstrap installer."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile

from .setup import SettingsFile, SetupError, config_path, command_language
from . import setup_i18n


def _bootstrap() -> bytes:
    path = Path(sys.prefix) / "share/orsik-mobile-bridge/install-bridge.sh"
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, "rb") as handle:
            info = os.fstat(handle.fileno())
            if (not stat.S_ISREG(info.st_mode) or info.st_uid not in (0, os.geteuid())
                    or info.st_mode & 0o022):
                raise SetupError('update_installer_untrusted')
            content = handle.read(1024 * 1024 + 1)
            if not content or len(content) > 1024 * 1024:
                raise SetupError('update_installer_invalid')
            return content
    except OSError:
        raise SetupError('update_installer_missing') from None


def _latest_release(source: Path) -> tuple[str, str]:
    try:
        subprocess.run(['git', 'clone', '--quiet', '--filter=blob:none', '--no-checkout',
                        'https://github.com/exclude-barrier/OrsikRelay.git', str(source)],
                       check=True, capture_output=True, timeout=60)
        tag = subprocess.check_output(
            ['git', '-C', str(source), 'describe', '--tags', '--abbrev=0', 'origin/main'],
            timeout=15, stderr=subprocess.DEVNULL, text=True).strip()
        commit = subprocess.check_output(
            ['git', '-C', str(source), 'rev-parse', tag + '^{commit}'],
            timeout=15, stderr=subprocess.DEVNULL, text=True).strip()
        if len(commit) != 40 or any(c not in '0123456789abcdef' for c in commit):
            raise SetupError('update_check_failed')
        return tag, commit
    except (OSError, subprocess.SubprocessError):
        raise SetupError('update_check_failed') from None


def _installed_commit(home: Path) -> str | None:
    try:
        fd = os.open(home / 'installed-commit', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as handle:
            info = os.fstat(handle.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
                return None
            value = handle.read(128).decode('ascii').strip()
            return value if len(value) == 40 and all(c in '0123456789abcdef' for c in value) else None
    except (OSError, UnicodeError):
        return None


def main(argv: list[str]) -> int:
    language = command_language(argv)
    def t(key):
        return setup_i18n.text(language, key)
    parser = argparse.ArgumentParser(prog="omb update", description=t('update_description'))
    parser.add_argument('--language', '--lang', choices=('de', 'en'), default=language, help=t('lang_help'))
    parser.add_argument("--yes", action="store_true", help=t('update_yes_help'))
    parser.add_argument("--no-service", action="store_true", help=t('update_no_service_help'))
    parser.add_argument("--env-file", help=t('update_env_help'))
    args = parser.parse_args(argv)
    try:
        if not args.yes and (not sys.stdin.isatty() or not sys.stdout.isatty()):
            raise SetupError('update_tty')
        settings = SettingsFile(config_path(args.env_file), dict(os.environ))
        home = Path(settings.values.get("BRIDGE_HOME") or "~/.local/orsik-mobile-bridge").expanduser().absolute()
        if settings.path != home / "bridge.env":
            raise SetupError('update_external_config')
        env = dict(os.environ)
        env["BRIDGE_HOME"] = str(home)
        hermes_env = settings.values.get("BRIDGE_HERMES_ENV")
        if hermes_env:
            key_file = Path(hermes_env).expanduser()
            if key_file.name != ".env":
                raise SetupError('update_hermes_config')
            env["HERMES_HOME"] = str(key_file.parent)
        bootstrap = _bootstrap()
        with tempfile.TemporaryDirectory(prefix="omb-update-") as temporary:
            root = Path(temporary)
            tag, commit = _latest_release(root / 'source')
            if _installed_commit(home) == commit:
                print(t('update_current') + ' ' + tag)
                return 0
            if _installed_commit(home) is None:
                print(t('update_unknown'))
            print(t('update_confirm_title') + ' ' + tag)
            print(t('update_confirm_store'))
            print(t('update_confirm_service'))
            if not args.yes and input(t('update_prompt')).strip().lower() != ('j' if language == 'de' else 'y'):
                print(t('update_cancelled'))
                return 0
            script = root / "install-bridge.sh"
            script.write_bytes(bootstrap)
            script.chmod(0o600)
            env["ORSIK_DIR"] = str(root / "source")
            env["ORSIK_REF"] = commit
            command = ["bash", str(script)]
            if args.no_service:
                command.append("--no-service")
            result = subprocess.run(command, env=env, check=False)
            if result.returncode:
                print(t('update_failed'))
            return result.returncode
    except EOFError:
        print(t('update_cancelled'))
        return 0
    except KeyboardInterrupt:
        print(t('update_interrupted'))
        return 130
    except SetupError as error:
        print(error.localized(language))
        return 1
    except OSError:
        print(t('update_execution_failed'))
        return 1
