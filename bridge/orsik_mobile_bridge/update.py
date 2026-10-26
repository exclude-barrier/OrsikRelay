"""Explicit local update using the packaged, existing bootstrap installer."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile

from .setup import SettingsFile, SetupError, config_path


def _bootstrap() -> bytes:
    path = Path(sys.prefix) / "share/orsik-mobile-bridge/install-bridge.sh"
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, "rb") as handle:
            info = os.fstat(handle.fileno())
            if (not stat.S_ISREG(info.st_mode) or info.st_uid not in (0, os.geteuid())
                    or info.st_mode & 0o022):
                raise SetupError("Installierter Update-Installer ist nicht vertrauenswuerdig.")
            content = handle.read(1024 * 1024 + 1)
            if not content or len(content) > 1024 * 1024:
                raise SetupError("Installierter Update-Installer ist ungueltig.")
            return content
    except OSError:
        raise SetupError("Update-Installer fehlt; Bridge einmal mit dem regulaeren Installer aktualisieren.") from None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="omb update", description=__doc__)
    parser.add_argument("--yes", action="store_true", help="Update ausdruecklich ohne Nachfrage bestaetigen")
    parser.add_argument("--no-service", action="store_true", help="keinen systemd-Dienst aendern/starten")
    parser.add_argument("--env-file", help="bridge.env der Installation")
    args = parser.parse_args(argv)
    try:
        if not args.yes and (not sys.stdin.isatty() or not sys.stdout.isatty()):
            raise SetupError("omb update braucht ein Terminal oder eine ausdrueckliche Bestaetigung mit --yes.")
        settings = SettingsFile(config_path(args.env_file), dict(os.environ))
        home = Path(settings.values.get("BRIDGE_HOME") or "~/.local/orsik-mobile-bridge").expanduser().absolute()
        if settings.path != home / "bridge.env":
            raise SetupError("Update unterstuetzt bridge.env in BRIDGE_HOME; externe Konfiguration manuell aktualisieren.")
        env = dict(os.environ)
        env["BRIDGE_HOME"] = str(home)
        hermes_env = settings.values.get("BRIDGE_HERMES_ENV")
        if hermes_env:
            key_file = Path(hermes_env).expanduser()
            if key_file.name != ".env":
                raise SetupError("Der Bootstrap-Updatepfad benoetigt die Hermes-Konfiguration HERMES_HOME/.env.")
            env["HERMES_HOME"] = str(key_file.parent)
        bootstrap = _bootstrap()
        print("Bridge auf die neueste Release-Markierung aktualisieren?")
        print("Der Installer sichert den Geraetestore, behaelt bridge.env und erneuert die Bridge-venv.")
        print("Er prueft Hermes und kann den systemd-User-Dienst aktualisieren/neustarten (--no-service unterbindet das).")
        if not args.yes and input("Update starten? [j/N]: ").strip().lower() != "j":
            print("Abgebrochen; kein Update gestartet.")
            return 0
        # Never check out a release over the user's source/OMP workspace. Run a
        # copy outside the replaced venv; the existing installer clones official
        # sources into this private temporary directory and performs its backup.
        with tempfile.TemporaryDirectory(prefix="omb-update-") as temporary:
            root = Path(temporary)
            script = root / "install-bridge.sh"
            script.write_bytes(bootstrap)
            script.chmod(0o600)
            env["ORSIK_DIR"] = str(root / "source")
            env.pop("ORSIK_REF", None)
            command = ["bash", str(script)]
            if args.no_service:
                command.append("--no-service")
            result = subprocess.run(command, env=env, check=False)
            if result.returncode:
                print("Update fehlgeschlagen; Installer-Ausgabe und Backup pruefen. Keine automatische Wiederholung.")
            return result.returncode
    except EOFError:
        print("Abgebrochen; kein Update gestartet.")
        return 0
    except KeyboardInterrupt:
        print("\nUpdate unterbrochen; bei bereits gestartetem Installer dessen Zustand pruefen.")
        return 130
    except SetupError as error:
        print(str(error))
        return 1
    except OSError:
        print("Update konnte nicht ausgefuehrt werden; bash, Speicherplatz und Dateirechte pruefen.")
        return 1
