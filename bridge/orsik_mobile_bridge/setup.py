"""Private local configuration and the keyboard-driven setup entry point."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import stat
import sys
import tempfile

from . import files, server, store, terminal, setup_i18n

_SETTINGS = {"BRIDGE_TERMINAL", "BRIDGE_FILES", "BRIDGE_FILES_ROOT"}
_MAX_CONFIG = 1024 * 1024


class SetupError(Exception):
    def localized(self, language: str) -> str:
        message = str(self.args[0]) if self.args else 'validation_error'
        return setup_i18n.text(language, message) if message in setup_i18n.TEXT[language] else message

    def __str__(self):
        return self.localized(setup_i18n.detect_language())


def config_path(explicit: str | None) -> Path:
    home = os.environ.get("BRIDGE_HOME") or "~/.local/orsik-mobile-bridge"
    adjacent = Path(sys.prefix).parent / "bridge.env"
    default = adjacent if not os.environ.get("BRIDGE_HOME") and adjacent.is_file() else Path(home).expanduser() / "bridge.env"
    return Path(explicit or os.environ.get("BRIDGE_ENV_FILE") or
                str(default)).expanduser().absolute()


def _key(line: str) -> str:
    line = line.strip()
    if line.startswith("export "):
        line = line[7:].lstrip()
    if not line or line.startswith("#") or "=" not in line:
        return ""
    return line.partition("=")[0].strip()


class SettingsFile:
    def __init__(self, path: Path, environ: dict[str, str]):
        self.path = path
        self.original = self._read()
        self.values = dict(environ)
        self.values.update(server._parse_env_text(self.original.decode("utf-8")))
        self.values["BRIDGE_ENV_FILE"] = str(path)
        if path == Path(sys.prefix).parent / "bridge.env" and not self.values.get("BRIDGE_HOME"):
            self.values["BRIDGE_HOME"] = str(path.parent)
        # The user unit has no WorkingDirectory; relative paths use user HOME.
        service_home = Path.home()
        for key in ("BRIDGE_STATE_DB", "BRIDGE_HERMES_ENV", "HERMES_HOME"):
            value = self.values.get(key)
            if value:
                candidate = Path(value).expanduser()
                self.values[key] = str(candidate if candidate.is_absolute() else service_home / candidate)

    def _read(self) -> bytes:
        try:
            fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as handle:
                info = os.fstat(handle.fileno())
                if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                        or info.st_mode & 0o077):
                    raise SetupError("config_private")
                data = handle.read(_MAX_CONFIG + 1)
                if len(data) > _MAX_CONFIG:
                    raise SetupError("config_large")
                data.decode("utf-8")
                return data
        except (OSError, UnicodeDecodeError):
            raise SetupError("config_read") from None

    def save(self, changes: dict[str, str]) -> None:
        if not changes:
            return
        for key, value in changes.items():
            if (key not in _SETTINGS or not isinstance(value, str)
                    or any(not ch.isprintable() or ch in "\"'\\" for ch in value)):
                raise SetupError("config_value")
        lines = []
        remaining = set(changes)
        for line in self.original.decode("utf-8").splitlines(keepends=True):
            key = _key(line)
            if key in changes:
                lines.append(f'{key}="{changes[key]}"\n')
                remaining.discard(key)
            else:
                lines.append(line)
        if remaining and lines and not lines[-1].endswith(("\n", "\r")):
            lines.append("\n")
        lines.extend(f'{key}="{changes[key]}"\n' for key in sorted(remaining))
        content = "".join(lines).encode("utf-8")
        temporary = None
        try:
            fd, temporary = tempfile.mkstemp(prefix=".omb-", dir=self.path.parent)
            with os.fdopen(fd, "wb") as handle:
                handle.write(content)
                handle.flush()
                os.fsync(handle.fileno())
            if self._read() != self.original:
                raise SetupError("config_changed")
            os.replace(temporary, self.path)
            self.original = content
        except OSError:
            raise SetupError("config_write") from None
        finally:
            if temporary is not None:
                Path(temporary).unlink(missing_ok=True)



class SetupSession:
    """Draft global settings; device decisions remain explicitly immediate."""
    def __init__(self, settings: SettingsFile, language: str):
        if language not in setup_i18n.TEXT:
            raise ValueError('unsupported setup language')
        self.settings = settings
        self.language = language
        self.pending: dict[str, str] = {}
        self.restart_required = False

    def t(self, key: str) -> str:
        return setup_i18n.text(self.language, key)

    @property
    def values(self) -> dict[str, str]:
        return {**self.settings.values, **self.pending}

    def set_value(self, key: str, value: str) -> None:
        if key not in _SETTINGS:
            raise SetupError('config_value')
        original = self.settings.values.get(key, '')
        if key in ('BRIDGE_TERMINAL', 'BRIDGE_FILES'):
            original = '1' if original.strip() == '1' else '0'
        if value == original:
            self.pending.pop(key, None)
        else:
            self.pending[key] = value

    def toggle(self, key: str) -> None:
        parser = terminal.parse_enabled if key == 'BRIDGE_TERMINAL' else files.parse_enabled
        self.set_value(key, '0' if parser(self.values.get(key)) else '1')

    def save(self) -> None:
        if not self.pending:
            return
        env = self.values
        if terminal.parse_enabled(env.get('BRIDGE_TERMINAL')):
            terminal.default_command()
        server._files_root_from_env(env)
        self.settings.save(self.pending)
        self.settings.values.update(self.pending)
        self.pending.clear()
        self.restart_required = True

    def _store_path(self) -> Path:
        path = Path(server._state_db_path(self.settings.values)).expanduser()
        return path if path.is_absolute() else Path.home() / path

    def devices(self) -> list[dict]:
        path = self._store_path()
        if not path.is_file():
            return []
        db = store.BridgeStore(str(path))
        try:
            return [device for device in db.list_devices() if not device['revoked']]
        finally:
            db.close()

    def change_terminal(self, device_id: str, allowed: bool) -> bool:
        path = self._store_path()
        if not path.is_file():
            raise SetupError('no_devices')
        db = store.BridgeStore(str(path))
        try:
            return db.set_terminal_access(device_id, allowed)
        finally:
            db.close()


def error_text(error: BaseException, language: str) -> str:
    if isinstance(error, SetupError):
        key = str(error.args[0]) if error.args else ''
        return error.localized(language) if key in setup_i18n.TEXT[language] else setup_i18n.text(language, 'validation_error')
    if isinstance(error, store.StoreUnavailable):
        return setup_i18n.text(language, 'store_error')
    known = {
        terminal._ENABLE_ERROR: 'invalid_terminal',
        files._ENABLE_ERROR: 'invalid_files',
        files._ROOT_ERROR: 'invalid_folder',
        'BRIDGE_TERMINAL=1 needs tmux and setsid (util-linux) on the PATH': 'terminal_tools',
        'BRIDGE_FILES_ROOT must not be /': 'protected_folder',
        "BRIDGE_FILES_ROOT must not contain the home folder, the bridge's own data or the Hermes home": 'protected_folder',
        'BRIDGE_FILES_ROOT must not lie inside the bridge or the Hermes home': 'protected_folder',
    }
    return setup_i18n.text(language, known.get(str(error), 'validation_error'))


def main(argv: list[str]) -> int:
    initial = setup_i18n.detect_language()
    language_parser = argparse.ArgumentParser(add_help=False)
    language_parser.add_argument('--language', '--lang', choices=('de', 'en'), default=initial)
    language = language_parser.parse_known_args(argv)[0].language
    parser = argparse.ArgumentParser(prog='omb setup', description=setup_i18n.text(language, 'title'))
    parser.add_argument('--env-file', help=setup_i18n.text(language, 'env_help'))
    parser.add_argument('--language', '--lang', choices=('de', 'en'), default=language,
                        help=setup_i18n.text(language, 'lang_help'))
    args = parser.parse_args(argv)
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        print(setup_i18n.text(language, 'tty'))
        return 1
    try:
        from .setup_tui import SetupApplication
        settings = SettingsFile(config_path(args.env_file), dict(os.environ))
        return SetupApplication(SetupSession(settings, language)).application.run()
    except (EOFError, KeyboardInterrupt):
        print(setup_i18n.text(language, 'ended'))
        return 0
    except (SetupError, store.StoreUnavailable, SystemExit) as error:
        print(error_text(error, language))
        return 1
