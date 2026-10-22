"""Read-only host metrics for the app's Home card.

Fixed sources only: /proc/stat, /proc/meminfo, ``nvidia-smi`` with fixed
arguments (when installed) and ``systemctl --user is-active`` for the
operator's fixed service list (``BRIDGE_HOST_SERVICES``). No shell, no
argument ever comes from a request, and every failure degrades to "no
value" instead of an error.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
from typing import Callable

DEFAULT_SERVICES = ("hermes-gateway", "orsik-mobile-bridge")
MAX_SERVICES = 8
_SERVICE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9@_.-]{0,63}$")
_SUBPROCESS_TIMEOUT = 2.0
_GPU_QUERY = "--query-gpu=name,utilization.gpu,memory.used,memory.total"


def parse_services(raw: str | None) -> tuple[str, ...]:
    """The operator's service list; an unsafe name stops the bridge at start."""
    if raw is None or not raw.strip():
        return DEFAULT_SERVICES
    names = tuple(part.strip() for part in raw.split(",") if part.strip())
    if len(names) > MAX_SERVICES or not all(_SERVICE_NAME.match(name) for name in names):
        raise SystemExit("BRIDGE_HOST_SERVICES: at most 8 plain unit names (letters, digits, @ _ . -)")
    return names


def _read(path: str) -> str:
    with open(path, encoding="ascii") as handle:
        return handle.read()


def _cpu_ticks(text: str) -> tuple[int, int]:
    fields = [int(value) for value in text.splitlines()[0].split()[1:]]
    idle = fields[3] + (fields[4] if len(fields) > 4 else 0)
    return sum(fields), idle


def cpu_percent(
    read: Callable[[], str] = lambda: _read("/proc/stat"),
    sleep: Callable[[float], None] = time.sleep,
    interval: float = 0.2,
) -> float | None:
    """Busy share of all cores between two /proc/stat samples, or None."""
    try:
        total1, idle1 = _cpu_ticks(read())
        sleep(interval)
        total2, idle2 = _cpu_ticks(read())
    except (OSError, ValueError, IndexError):
        return None
    total = total2 - total1
    if total <= 0:
        return None
    return round(100.0 * (total - (idle2 - idle1)) / total, 1)


def memory(read: Callable[[], str] = lambda: _read("/proc/meminfo")) -> dict | None:
    try:
        values = {}
        for line in read().splitlines():
            key, _, rest = line.partition(":")
            if key in ("MemTotal", "MemAvailable"):
                values[key] = int(rest.split()[0]) * 1024
        total, available = values["MemTotal"], values["MemAvailable"]
    except (OSError, ValueError, IndexError, KeyError):
        return None
    return {"total_bytes": total, "used_bytes": total - available}


def gpu(which: Callable[[str], str | None] = shutil.which, run=subprocess.run) -> dict | None:
    """The first NVIDIA GPU, or None when there is none or nvidia-smi fails."""
    binary = which("nvidia-smi")
    if binary is None:
        return None
    try:
        done = run(
            [binary, _GPU_QUERY, "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=_SUBPROCESS_TIMEOUT, check=False,
        )
        if done.returncode != 0:
            return None
        name, utilization, used, total = (part.strip() for part in done.stdout.splitlines()[0].split(","))
        return {
            "name": name[:64],
            "utilization_percent": int(utilization),
            "memory_used_bytes": int(used) * 1024 * 1024,
            "memory_total_bytes": int(total) * 1024 * 1024,
        }
    except (OSError, ValueError, IndexError, subprocess.SubprocessError):
        return None


def services(names: tuple[str, ...], run=subprocess.run) -> list[dict]:
    result = []
    for name in names:
        try:
            active = run(
                ["systemctl", "--user", "is-active", "--quiet", name + ".service"],
                capture_output=True, timeout=_SUBPROCESS_TIMEOUT, check=False,
            ).returncode == 0
        except (OSError, subprocess.SubprocessError):
            active = False
        result.append({"name": name, "active": active})
    return result


def collect(
    service_names: tuple[str, ...],
    cpu: Callable[[], float | None] = cpu_percent,
    mem: Callable[[], dict | None] = memory,
    gpu_reader: Callable[[], dict | None] = gpu,
    service_reader: Callable[[tuple[str, ...]], list[dict]] = services,
    cores: Callable[[], int | None] = os.cpu_count,
    now: Callable[[], float] = time.time,
) -> dict:
    """One snapshot; blocking (about 0.2 s for the CPU sample), run it off the loop."""
    return {
        "object": "orsik.host.metrics",
        "cpu": {"percent": cpu(), "cores": cores()},
        "memory": mem(),
        "gpu": gpu_reader(),
        "services": service_reader(service_names),
        "collected_at": now(),
    }
