#!/usr/bin/env python3
"""Quiet-safe pure mocks: no subprocess, real signals, sleeps, Java or Docker."""
import ast
import importlib.util
import pathlib
import signal
import tempfile
import types
import unittest
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("reference_cleanup", HERE / "run-full-reference.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CleanupAndDiskTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=HERE, prefix="mock-cleanup-")
        self.addCleanup(self.temp.cleanup)
        self.study = module.ReferenceStudy(module.arguments([]), root=pathlib.Path(self.temp.name))
        self.identity = {"pid": 1200, "pgid": 1200, "session": 1200, "startTicks": 100, "state": "S"}
        self.child = {"pid": 1201, "pgid": 1200, "session": 1200, "startTicks": 101, "state": "S"}
        self.study.active = mock.Mock(pid=1200)
        self.study.active.poll.return_value = 0  # Launcher already exited; child remains.
        self.study.active_identity = dict(self.identity)
        self.no_launch = mock.patch.object(module.subprocess, "Popen", side_effect=AssertionError("quiet: no child launch"))
        self.no_launch.start()
        self.addCleanup(self.no_launch.stop)

    def test_term_exited_launcher_does_not_skip_surviving_group_kill(self):
        with mock.patch.object(self.study, "group_members", return_value=[self.child]), \
             mock.patch.object(self.study, "wait_group", side_effect=[[self.child], []]), \
             mock.patch.object(module.os, "killpg") as send:
            self.study.stop_active()
        self.assertEqual([mock.call(1200, signal.SIGTERM), mock.call(1200, signal.SIGKILL)], send.call_args_list)
        self.assertIsNone(self.study.active_identity)
        proof = self.study.manifest["terminationAttempts"][-1]
        self.assertEqual([self.child], proof["afterTermGrace"])
        self.assertEqual([], proof["afterKillGrace"])
        self.assertTrue(proof["settled"])

    def test_successful_launcher_with_orphan_is_not_reported_as_phase_success(self):
        self.study.active = None
        self.study.active_identity = None
        launcher = mock.Mock(pid=1200, returncode=0)
        launcher.wait.return_value = 0
        launcher.poll.return_value = 0
        with mock.patch.object(module.subprocess, "Popen", return_value=launcher), \
             mock.patch.object(self.study, "process_identity", return_value=dict(self.identity)), \
             mock.patch.object(self.study, "group_members", return_value=[self.child]), \
             mock.patch.object(self.study, "wait_group", side_effect=[[self.child], []]), \
             mock.patch.object(module.os, "killpg") as send:
            with self.assertRaises(module.StudyError):
                self.study.command("offline-orphan", ["not-executed"])
        self.assertEqual([mock.call(1200, signal.SIGTERM), mock.call(1200, signal.SIGKILL)], send.call_args_list)
        self.assertTrue(self.study.steps[-1]["ownedDescendantsAfterLauncherExit"])
        self.assertEqual(0, self.study.steps[-1]["rawReturnCode"])
        self.assertIsNone(self.study.active_identity)

    def test_timeout_code_is_preserved_when_group_cleanup_also_fails(self):
        self.study.active = None
        self.study.active_identity = None
        launcher = mock.Mock(pid=1200, returncode=None)
        launcher.wait.side_effect = module.subprocess.TimeoutExpired("offline", 1)
        with mock.patch.object(module.subprocess, "Popen", return_value=launcher), \
             mock.patch.object(self.study, "process_identity", return_value=dict(self.identity)), \
             mock.patch.object(self.study, "group_members", return_value=[self.child]), \
             mock.patch.object(self.study, "stop_active", side_effect=module.StudyError("synthetic unkillable group")):
            with self.assertRaises(module.StudyError) as failure:
                self.study.command("offline-timeout-cleanup", ["not-executed"], timeout=1)
        self.assertEqual(124, failure.exception.code)
        self.assertIn("synthetic unkillable", self.study.steps[-1]["groupCleanupError"])
        self.assertEqual(self.identity, self.study.active_identity)

    def test_clean_term_needs_no_kill(self):
        with mock.patch.object(self.study, "group_members", return_value=[self.child]), \
             mock.patch.object(self.study, "wait_group", return_value=[]), \
             mock.patch.object(module.os, "killpg") as send:
            self.study.stop_active()
        send.assert_called_once_with(1200, signal.SIGTERM)

    def test_uncleared_group_keeps_start_identity_and_fails(self):
        with mock.patch.object(self.study, "group_members", return_value=[self.child]), \
             mock.patch.object(self.study, "wait_group", return_value=[self.child]), \
             mock.patch.object(module.os, "killpg"):
            with self.assertRaises(module.StudyError):
                self.study.stop_active()
        self.assertEqual(self.identity, self.study.active_identity)
        self.assertEqual(self.child, self.study.manifest["unresolvedOwnedGroups"][-1]["afterKillGrace"][0])

    def test_pid_reuse_refuses_unrelated_group_signals(self):
        replacement = dict(self.identity, startTicks=900)
        with mock.patch.object(self.study, "process_identity", return_value=replacement), \
             mock.patch.object(module.os, "killpg") as send:
            with self.assertRaises(module.StudyError):
                self.study.stop_active()
        send.assert_not_called()
        self.assertEqual(self.identity, self.study.active_identity)

    def test_group_wait_uses_members_after_launcher_poll_exits(self):
        with mock.patch.object(self.study, "group_members", side_effect=[[self.child], []]), \
             mock.patch.object(module.time, "monotonic", side_effect=[1, 2]), \
             mock.patch.object(module.time, "sleep") as pause:
            self.assertEqual([], self.study.wait_group(self.identity, 15))
        self.assertEqual(2, self.study.active.poll.call_count)
        pause.assert_called_once_with(.05)

    def test_disk_floor_rechecked_and_additional_copy_bytes_counted(self):
        with mock.patch.object(module.shutil, "disk_usage", side_effect=[types.SimpleNamespace(free=8*1024**3), types.SimpleNamespace(free=7*1024**3)]):
            self.study.disk_guard("before-jmh")
            with self.assertRaises(module.StudyError):
                self.study.disk_guard("raw-copy", 2*1024**3)
        checks = self.study.manifest["diskChecks"]
        self.assertEqual(["before-jmh", "raw-copy"], [row["phase"] for row in checks])
        self.assertEqual(8*1024**3, checks[-1]["requiredBytes"])

    def test_raw_copy_low_disk_refuses_without_deleting_existing_evidence(self):
        raw = self.study.project / "target/benchmark/console.log"
        raw.parent.mkdir(parents=True)
        raw.write_bytes(b"retained failure")
        with mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=1)):
            with self.assertRaises(module.StudyError):
                self.study.preserve_current_outputs()
        self.assertEqual(b"retained failure", raw.read_bytes())
        self.assertFalse((self.study.directory / "current").exists())

    def test_source_is_syntax_valid_without_compiling_or_launching(self):
        ast.parse((HERE / "run-full-reference.py").read_text())


if __name__ == "__main__":
    unittest.main()
