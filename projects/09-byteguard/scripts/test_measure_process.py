#!/usr/bin/env python3
"""Actual Python-child measurement/termination regressions; no Java required."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

sys.dont_write_bytecode = True
from measure_process import measure


class MeasurementTest(unittest.TestCase):
    def run_child(self, source, timeout=3):
        with tempfile.TemporaryFile() as output:
            result = measure([sys.executable, "-c", source], output, timeout)
            output.seek(0)
            return result, output.read()

    def test_success_has_actual_usage_and_stdout(self):
        result, output = self.run_child("data=bytearray(20*1024*1024);print(sum(range(100000)))")
        self.assertEqual(0, result["exitCode"])
        self.assertEqual(b"4999950000\n", output)
        self.assertGreater(result["elapsedSeconds"], 0)
        self.assertGreater(result["maxRssKiB"], 8192)
        self.assertGreater(result["userSeconds"] + result["systemSeconds"], 0)
        self.assertFalse(result["timedOut"] or result["cancelled"])

    def test_nonzero_exit_is_preserved(self):
        result, _ = self.run_child("raise SystemExit(7)")
        self.assertEqual(7, result["exitCode"])
        self.assertIsNone(result["terminationSignal"])

    def test_signal_exit_is_distinct(self):
        result, _ = self.run_child("import os,signal;os.kill(os.getpid(),signal.SIGTERM)")
        self.assertEqual(-signal.SIGTERM, result["exitCode"])
        self.assertEqual(signal.SIGTERM, result["terminationSignal"])
        self.assertFalse(result["cancelled"])

    def test_timeout_kills_owned_group_and_reaps_child(self):
        result, _ = self.run_child("import time;time.sleep(30)", 0.1)
        self.assertTrue(result["timedOut"])
        self.assertEqual(-signal.SIGKILL, result["exitCode"])
        self.assertLess(result["elapsedSeconds"], 3)
        self.assertNotEqual(os.getpgrp(), result["pid"])
        with self.assertRaises(ProcessLookupError):
            os.killpg(result["pid"], 0)
        with self.assertRaises(ChildProcessError):
            os.waitpid(result["pid"], os.WNOHANG)

    def test_peak_rss_is_per_run_not_cumulative(self):
        high, _ = self.run_child("data=bytearray(80*1024*1024);print(len(data))")
        low, _ = self.run_child("print('small')")
        self.assertGreater(high["maxRssKiB"], low["maxRssKiB"] + 20000)

    def test_cancellation_kills_and_reaps_only_owned_child(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            pidfile, stats, log = (directory / name for name in ("pid", "stats", "log"))
            source = f"import os,time;from pathlib import Path;Path({str(pidfile)!r}).write_text(str(os.getpid()));time.sleep(30)"
            driver = subprocess.Popen([sys.executable, str(Path(__file__).with_name("measure_process.py")),
                                       "--timeout", "30", "--stats", str(stats), "--log", str(log),
                                       "--", sys.executable, "-c", source],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                deadline = time.monotonic() + 3
                child = None
                while time.monotonic() < deadline:
                    try:
                        text = pidfile.read_text()
                        if text.isdigit():
                            child = int(text)
                            break
                    except FileNotFoundError:
                        pass
                    time.sleep(0.01)
                self.assertIsNotNone(child)
                driver.send_signal(signal.SIGTERM)
                _, error = driver.communicate(timeout=3)
                self.assertEqual(143, driver.returncode, error.decode())
                result = json.loads(stats.read_text())
                self.assertTrue(result["cancelled"])
                self.assertEqual(signal.SIGTERM, result["cancellationSignal"])
                self.assertEqual(-signal.SIGKILL, result["exitCode"])
                self.assertFalse(Path(f"/proc/{child}").exists())
                with self.assertRaises(ProcessLookupError):
                    os.killpg(child, 0)
            finally:
                if driver.poll() is None:
                    driver.kill()
                    driver.wait()


if __name__ == "__main__":
    unittest.main()
