"""Local, dependency-free setup menu. No HTTP administration or service restart."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import stat
import sys
import tempfile

from . import files, server, store, terminal

_SETTINGS = {"BRIDGE_TERMINAL", "BRIDGE_FILES", "BRIDGE_FILES_ROOT"}
_MAX_CONFIG = 1024 * 1024


class SetupError(Exception):
    pass


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
                    raise SetupError("bridge.env muss eine eigene private Datei sein (chmod 600).")
                data = handle.read(_MAX_CONFIG + 1)
                if len(data) > _MAX_CONFIG:
                    raise SetupError("bridge.env ist zu gross.")
                data.decode("utf-8")
                return data
        except (OSError, UnicodeDecodeError):
            raise SetupError("bridge.env nicht lesbar; Pfad und Dateirechte pruefen.") from None

    def save(self, changes: dict[str, str]) -> None:
        if not changes:
            return
        for key, value in changes.items():
            if (key not in _SETTINGS or not isinstance(value, str)
                    or any(not ch.isprintable() or ch in "\"'\\" for ch in value)):
                raise SetupError("Nur erlaubte einzeilige Einstellungen ohne Quotes/Backslash speichern.")
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
                raise SetupError("bridge.env wurde inzwischen geaendert; Setup neu starten.")
            os.replace(temporary, self.path)
            self.original = content
        except OSError:
            raise SetupError("Speichern fehlgeschlagen; Dateirechte pruefen.") from None
        finally:
            if temporary is not None:
                Path(temporary).unlink(missing_ok=True)


def _text(value: str) -> str:
    # Labels/paths can contain terminal controls. Never let them clear a prompt.
    return "".join(ch for ch in value if ch.isprintable())[:160]


def _confirmed(prompt: str) -> bool:
    return input(prompt + " [j/N]: ").strip().lower() == "j"


def _devices(settings: SettingsFile) -> None:
    env = settings.values
    path = Path(server._state_db_path(env)).expanduser()
    if not path.is_absolute():
        path = Path.home() / path  # Default working directory of a user unit.
    if not path.is_file():
        print("Keine Geraetedatenbank vorhanden. Zuerst ein Geraet koppeln.")
        return
    db = store.BridgeStore(str(path))
    try:
        while True:
            devices = [d for d in db.list_devices() if not d["revoked"]]
            by_number = {str(number): device for number, device in enumerate(devices, 1)}
            print("\nGeraete-Terminalrechte (Aenderungen wirken sofort):")
            for number, device in enumerate(devices, 1):
                status = "freigegeben" if device["terminal_allowed"] else "gesperrt"
                print(f"{number}. {_text(device['label'])} | {_text(device['device_id'])} | {status}")
            choice = input("Geraetenummer waehlen, 0 = zurueck: ").strip()
            if choice == "0":
                return
            device = by_number.get(choice)
            if device is None:
                print("Ungueltige Auswahl.")
                continue
            allowed = not device["terminal_allowed"]
            verb = "Volle Server-Shell freigeben" if allowed else "Terminalrecht entziehen"
            if _confirmed(f"{verb} fuer {_text(device['label'])} ({_text(device['device_id'])})?"):
                if db.set_terminal_access(device["device_id"], allowed):
                    print("Geraeterecht gespeichert. Globaler Terminal-Schalter bleibt unveraendert.")
                else:
                    print("Geraet nicht mehr aktiv; keine Freigabe gespeichert.")
    finally:
        db.close()


def _menu(settings: SettingsFile) -> int:
    pending: dict[str, str] = {}
    while True:
        env = {**settings.values, **pending}
        print("\nOMB Setup — lokale Bridge-Einstellungen")
        print(f"Konfiguration: {_text(str(settings.path))}")
        print(f"1. Terminal: {'AN' if terminal.parse_enabled(env.get('BRIDGE_TERMINAL')) else 'AUS'}")
        print(f"2. Dateizugriff (nur lesend): {'AN' if files.parse_enabled(env.get('BRIDGE_FILES')) else 'AUS'}")
        print(f"3. Dateiordner: {_text(env.get('BRIDGE_FILES_ROOT', '')) or '(nicht gesetzt)'}")
        print("4. Geraete-Terminalrechte verwalten (wirkt sofort)")
        print("s. Einstellungen speichern | q. Ohne Speichern beenden")
        if pending:
            print("Ungespeicherte Einstellungen vorhanden.")
        choice = input("Auswahl: ").strip().lower()
        if choice == "q":
            return 0
        try:
            if choice == "1":
                pending["BRIDGE_TERMINAL"] = "0" if terminal.parse_enabled(env.get("BRIDGE_TERMINAL")) else "1"
                if pending["BRIDGE_TERMINAL"] == "1":
                    print("Volle Shell als Bridge-Benutzer; jedes Geraet braucht zusaetzlich eine Freigabe.")
            elif choice == "2":
                pending["BRIDGE_FILES"] = "0" if files.parse_enabled(env.get("BRIDGE_FILES")) else "1"
            elif choice == "3":
                pending["BRIDGE_FILES_ROOT"] = input("Absoluter freizugebender Ordner: ").strip()
            elif choice == "4":
                _devices(settings)
            elif choice == "s":
                if not pending:
                    print("Keine Aenderungen.")
                    continue
                if not _confirmed("Einstellungen speichern?"):
                    continue
                # Existing validation: never open the store or read Hermes keys.
                if terminal.parse_enabled(env.get("BRIDGE_TERMINAL")):
                    terminal.default_command()
                server._files_root_from_env(env)
                settings.save(pending)
                print("Einstellungen gespeichert. Zum Anwenden den Dienst neu starten:")
                print("  systemctl --user restart orsik-mobile-bridge.service")
                print("Bei eigener Service-Konfiguration deren Neustartverfahren verwenden.")
                return 0
            else:
                print("Ungueltige Auswahl.")
        except (SetupError, store.StoreUnavailable) as error:
            # Store errors are intentionally not printed (may contain details).
            print(str(error) if isinstance(error, SetupError) else "Geraetedatenbank nicht verfuegbar.")
        except SystemExit as error:
            # Only existing validators are called here; they return fixed text.
            print(_text(str(error)))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="omb setup", description=__doc__)
    parser.add_argument("--env-file", help="bridge.env (sonst BRIDGE_ENV_FILE oder BRIDGE_HOME/bridge.env)")
    args = parser.parse_args(argv)
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        print("omb setup braucht ein interaktives Terminal.")
        return 1
    try:
        return _menu(SettingsFile(config_path(args.env_file), dict(os.environ)))
    except (EOFError, KeyboardInterrupt):
        print("\nBeendet; ungespeicherte Einstellungen verworfen.")
        return 0
    except SetupError as error:
        print(str(error))
        return 1
    except SystemExit as error:
        print(_text(str(error)))
        return 1
