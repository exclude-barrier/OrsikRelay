"""Host metrics: fixed sources only, no shell, no caller-chosen arguments."""

import subprocess
import unittest

from orsik_mobile_bridge import hostmetrics


class ServiceListTest(unittest.TestCase):
    def test_default_and_configured_lists(self):
        self.assertEqual(hostmetrics.parse_services(None), ("hermes-gateway", "orsik-mobile-bridge"))
        self.assertEqual(hostmetrics.parse_services(""), ("hermes-gateway", "orsik-mobile-bridge"))
        self.assertEqual(hostmetrics.parse_services(" docker , hermes-gateway "), ("docker", "hermes-gateway"))

    def test_a_service_suffix_is_tolerated_and_an_empty_list_refused(self):
        self.assertEqual(hostmetrics.parse_services("docker.service, x"), ("docker", "x"))
        for raw in (",", " , ,"):
            with self.assertRaises(SystemExit, msg=raw):
                hostmetrics.parse_services(raw)

    def test_unsafe_names_are_refused_at_start(self):
        for raw in ("a;b", "../x", "x y", "-x", "a" * 65, ",".join(f"s{i}" for i in range(9))):
            with self.assertRaises(SystemExit, msg=raw):
                hostmetrics.parse_services(raw)


class CpuTest(unittest.TestCase):
    def test_busy_share_between_two_samples(self):
        samples = iter([
            "cpu  100 0 100 700 100 0 0 0 0 0\n",
            "cpu  150 0 150 750 150 0 0 0 0 0\n",
        ])
        percent = hostmetrics.cpu_percent(read=lambda: next(samples), sleep=lambda _: None)
        # 100 busy of 200 total ticks (iowait counts as idle).
        self.assertEqual(percent, 50.0)

    def test_guest_ticks_are_not_counted_twice(self):
        # /proc/stat already accounts guest/guest_nice in user/nice; adding the
        # guest fields to the total would double-count them.
        samples = iter([
            "cpu  100 0 100 700 100 0 0 0 20 20\n",
            "cpu  150 0 150 850 100 0 0 0 50 50\n",
        ])
        percent = hostmetrics.cpu_percent(read=lambda: next(samples), sleep=lambda _: None)
        # 100 busy of 250 total ticks over the first eight (non-guest) fields.
        self.assertEqual(percent, 40.0)

    def test_unreadable_stat_gives_none(self):
        def broken():
            raise OSError("no /proc")
        self.assertIsNone(hostmetrics.cpu_percent(read=broken, sleep=lambda _: None))


class MemoryTest(unittest.TestCase):
    def test_total_and_used_from_meminfo(self):
        text = "MemTotal:       1000 kB\nMemFree:  100 kB\nMemAvailable:    400 kB\n"
        self.assertEqual(hostmetrics.memory(read=lambda: text), {"total_bytes": 1024000, "used_bytes": 614400})

    def test_missing_fields_give_none(self):
        self.assertIsNone(hostmetrics.memory(read=lambda: "MemFree: 1 kB\n"))


class GpuTest(unittest.TestCase):
    def test_no_nvidia_smi_means_no_gpu(self):
        self.assertIsNone(hostmetrics.gpu(which=lambda _: None, run=None))

    def test_first_gpu_is_parsed_with_fixed_arguments(self):
        seen = {}

        def run(args, **kwargs):
            seen["args"] = args
            seen["shell"] = kwargs.get("shell", False)
            return subprocess.CompletedProcess(args, 0, stdout="RTX 4090, 37, 1024, 24564\nRTX 3060, 0, 1, 2\n")

        self.assertEqual(
            hostmetrics.gpu(which=lambda _: "/usr/bin/nvidia-smi", run=run),
            {"name": "RTX 4090", "utilization_percent": 37,
             "memory_used_bytes": 1024 * 1024 * 1024, "memory_total_bytes": 24564 * 1024 * 1024},
        )
        self.assertEqual(seen["args"][0], "/usr/bin/nvidia-smi")
        self.assertFalse(seen["shell"])

    def test_failing_or_garbled_output_gives_none(self):
        def failing(args, **kwargs):
            return subprocess.CompletedProcess(args, 9, stdout="")

        def garbled(args, **kwargs):
            return subprocess.CompletedProcess(args, 0, stdout="nonsense\n")

        for run in (failing, garbled):
            self.assertIsNone(hostmetrics.gpu(which=lambda _: "/x/nvidia-smi", run=run))


class ServicesTest(unittest.TestCase):
    def test_is_active_per_fixed_unit(self):
        calls = []

        def run(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, {"hermes-gateway.service": 0, "orsik-mobile-bridge.service": 3}.get(args[-1], 1))

        self.assertEqual(
            hostmetrics.services(("hermes-gateway", "orsik-mobile-bridge", "other"), run=run),
            [{"name": "hermes-gateway", "active": True}, {"name": "orsik-mobile-bridge", "active": False},
             {"name": "other", "active": None}],
        )
        self.assertEqual(calls[0], ["systemctl", "--user", "is-active", "--quiet", "hermes-gateway.service"])

    def test_a_hanging_or_missing_systemctl_is_unknown_not_stopped(self):
        def hang(args, **kwargs):
            raise subprocess.TimeoutExpired(args, 2)

        def missing(args, **kwargs):
            raise FileNotFoundError("systemctl")

        for run in (hang, missing):
            self.assertEqual(hostmetrics.services(("x",), run=run), [{"name": "x", "active": None}])


class CollectTest(unittest.TestCase):
    def test_envelope_shape(self):
        result = hostmetrics.collect(
            ("hermes-gateway",),
            cpu=lambda: 12.5, mem=lambda: {"total_bytes": 2, "used_bytes": 1}, gpu_reader=lambda: None,
            service_reader=lambda names: [{"name": n, "active": True} for n in names], cores=lambda: 6,
            now=lambda: 1700000000.0,
        )
        self.assertEqual(result, {
            "object": "orsik.host.metrics",
            "cpu": {"percent": 12.5, "cores": 6},
            "memory": {"total_bytes": 2, "used_bytes": 1},
            "gpu": None,
            "services": [{"name": "hermes-gateway", "active": True}],
            "collected_at": 1700000000.0,
        })
