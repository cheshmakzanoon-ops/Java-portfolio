#!/usr/bin/env python3
"""Offline orchestration tests only: no Java, Maven, Docker or real measurements."""
import contextlib
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("clearledger_reference_runner", HERE / "run-full-reference.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class SimulatedStudy(runner.ReferenceStudy):
    def __init__(self, root, fail=None, cleanup=0, preflight_failure=False):
        self.calls = []
        self.fail = fail
        self.cleanup = cleanup
        self.preflight_failure = preflight_failure
        self.database_up = False
        super().__init__(runner.arguments([]), root=root)
        self.manifest["offlineSimulationOnly"] = True

    def command(self, name, argv, timeout=120, binary_output=None, check=True):
        self.calls.append(name)
        if name == self.fail:
            raise runner.StudyError("synthetic controlled failure", 23)
        if name == "database-start":
            self.database_up = True
        if name == "down":
            self.database_up = False
            return self.cleanup
        return 0

    def disk_guard(self, phase, additional_bytes=0):
        self.calls.append("disk-" + phase)
        return 12 * 1024**3

    def preflight(self):
        self.calls.append("preflight")
        if self.preflight_failure:
            raise runner.StudyError("synthetic preflight refusal", 17)
        self.baseline = {"sourceRevision": "synthetic fixture; no metrics"}

    def dump_databases(self, label):
        self.calls.append("dump-" + label)

    def assert_boundary(self, name, allow_database=False):
        self.calls.append("boundary-" + name)
        if self.database_up and not allow_database:
            raise runner.StudyError("synthetic unclean boundary")

    def validate_identity(self, prefix="identity", environments=None):
        self.calls.append(prefix)

    def current_containers(self, name):
        return ["java-portfolio-clearledger-postgres-1"] if self.database_up else []


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="runner-offline-", dir=HERE)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.project = self.root / "projects/01-clearledger"
        self.project.mkdir(parents=True)

    def test_default_plan_executes_no_subprocess(self):
        with mock.patch.object(runner.subprocess, "Popen", side_effect=AssertionError("must remain inert")), \
             mock.patch.object(runner.subprocess, "run", side_effect=AssertionError("must remain inert")), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, runner.main([]))
        self.assertEqual("PLAN_ONLY_NO_COMMANDS_EXECUTED", json.loads(output.getvalue())["mode"])

    def test_success_exact_workload_and_cleanup_order(self):
        study = SimulatedStudy(self.root)
        self.assertEqual(0, study.run())
        self.assertLess(study.calls.index("identity-jmh"), study.calls.index("jmh-summary"))
        self.assertLess(study.calls.index("dump-prior"), study.calls.index("full-workload"))
        self.assertLess(study.calls.index("identity-workload"), study.calls.index("workload-summary"))
        self.assertLess(study.calls.index("down"), study.calls.index("boundary-after-down"))
        self.assertIn("disk-raw-output-copy", study.calls)
        self.assertEqual("PASS", json.loads((study.directory / "run.json").read_text())["state"])

    def test_workload_failure_preserves_primary_status_dumps_then_down(self):
        study = SimulatedStudy(self.root, fail="full-workload", cleanup=7)
        self.assertEqual(23, study.run())
        self.assertIn("dump-failed-current", study.calls)
        self.assertLess(study.calls.index("dump-failed-current"), study.calls.index("down"))
        self.assertNotIn("workload-summary", study.calls)
        self.assertEqual(7, study.manifest["downExitCode"])

    def test_jmh_failure_does_not_start_database_but_runs_cleanup(self):
        study = SimulatedStudy(self.root, fail="full-jmh")
        self.assertEqual(23, study.run())
        self.assertNotIn("database-start", study.calls)
        self.assertNotIn("jmh-summary", study.calls)
        self.assertIn("down", study.calls)

    def test_preflight_refusal_does_not_touch_other_services(self):
        study = SimulatedStudy(self.root, preflight_failure=True)
        self.assertEqual(17, study.run())
        self.assertEqual(["preflight"], study.calls)
        self.assertIsNone(study.manifest["downExitCode"])

    def test_cleanup_failure_prevents_false_pass(self):
        study = SimulatedStudy(self.root, cleanup=9)
        self.assertEqual(9, study.run())
        self.assertEqual("FAIL", study.manifest["state"])

    def test_keyboard_interruption_still_dumps_and_cleans_up(self):
        study = SimulatedStudy(self.root)
        original = study.command
        def interrupted(name, *args, **kwargs):
            if name == "full-workload":
                raise KeyboardInterrupt("offline signal fixture")
            return original(name, *args, **kwargs)
        study.command = interrupted
        self.assertEqual(130, study.run())
        self.assertIn("dump-failed-current", study.calls)
        self.assertIn("down", study.calls)

    def test_real_python_child_nonzero_status_and_timeout_are_preserved(self):
        study = runner.ReferenceStudy(runner.arguments([]), root=self.root)
        import sys
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(11, study.command("offline-nonzero", [sys.executable, "-c", "raise SystemExit(11)"], check=False))
            self.assertEqual(124, study.command("offline-timeout", [sys.executable, "-c", "import time;time.sleep(30)"], timeout=.05, check=False))
        self.assertEqual(11, study.steps[0]["rawReturnCode"])
        self.assertTrue(study.steps[1]["timedOut"])
        self.assertLess(study.steps[1]["rawReturnCode"], 0)
        self.assertEqual(124, study.steps[1]["exitCode"])
        self.assertIsNone(study.active)

    def test_old_raw_failure_and_summary_are_moved_not_overwritten(self):
        raw = self.project / "target/benchmark/r1-w8/console.log"
        raw.parent.mkdir(parents=True)
        raw.write_bytes(b"original real failure placeholder for filesystem test")
        summary = self.project / "evidence/performance-current.json"
        summary.parent.mkdir()
        summary.write_bytes(b"prior summary placeholder")
        study = SimulatedStudy(self.root, fail="full-jmh")
        self.assertEqual(23, study.run())
        self.assertEqual(raw.read_bytes() if raw.exists() else b"absent", b"absent")
        self.assertEqual(b"original real failure placeholder for filesystem test",
                         (study.directory / "prior/target/benchmark/r1-w8/console.log").read_bytes())
        self.assertEqual(b"prior summary placeholder",
                         (study.directory / "prior/evidence/performance-current.json").read_bytes())
        self.assertEqual(2, len(json.loads((study.directory / "prior-relocations.json").read_text())))

    def test_archives_have_unique_directories(self):
        first = SimulatedStudy(self.root)
        second = SimulatedStudy(self.root)
        self.assertNotEqual(first.directory, second.directory)

    def test_build_locks_must_already_be_held(self):
        study = SimulatedStudy(self.root)
        slots = self.root / ".work/build-slots"
        slots.mkdir(parents=True)
        handles = [(slots / f"{n}.lock").open("w") for n in range(3)]
        self.addCleanup(lambda: [handle.close() for handle in handles])
        with self.assertRaises(runner.StudyError):
            study.locks_held()
        for handle in handles:
            runner.fcntl.flock(handle, runner.fcntl.LOCK_EX | runner.fcntl.LOCK_NB)
        self.assertEqual(3, len(study.locks_held()))


if __name__ == "__main__":
    unittest.main()
