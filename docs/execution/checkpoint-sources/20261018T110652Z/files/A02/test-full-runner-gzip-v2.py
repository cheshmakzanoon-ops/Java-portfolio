#!/usr/bin/env python3
"""Tiny synthetic/mocked controls only. No Java, Docker, workload or probe process."""
import copy
import contextlib
import gzip
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import runpy
import shutil
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("full_runner", Path(__file__).with_name("run-full-reference-gzip-v2.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class FullRunnerControls(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="synthetic-runner-control-", dir=m.OWNER)
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.output = self.base / "output"
        self.output.mkdir()
        self.raw = self.base / "raw"
        self.raw.mkdir()
        self.runner = m.Runner(self.output, "SYNTHETIC-CONTROL-NO-MEASUREMENT")
        m.ACTIVE = None
        m.INTERRUPTED = None
        guard = patch.object(m.subprocess, "Popen", side_effect=AssertionError("unexpected subprocess forbidden in control tests"))
        guard.start()
        self.addCleanup(guard.stop)
        self.measurements = dict(elapsedSeconds=1.0, completionLatencyMs="{1,2,3}",
            scheduleToStartMs="{1,2,3}", timerLatenessMs="{1,2,3}", executions=10000,
            activities=80000, timerWaits=5000, completedActivities=80000,
            independentProviderEffects=80000, transientRejectedAttempts=16000,
            acceptedUniqueSignals=10000, firedBusinessTimers=5000, queuedAtEnd=0)
        self.roles = ["api", "provider", "scheduler-1", "scheduler-2", "driver"] + [f"worker-{n}" for n in range(1, 9)]
        self.frames = [dict(clockTicksPerSecond=100, processes=[dict(role=role, pid=n + 1,
            rssBytes=1, cpuTicks=1) for n, role in enumerate(self.roles)])]
        self.write_frames()
        (self.raw / "worker-1.jfr").write_text("SYNTHETIC NON-JFR BYTES, SCHEMA CONTROL ONLY")
        (self.raw / "allocation-samples.json").write_text(json.dumps(dict(recording=dict(events=[
            dict(type="jdk.ObjectAllocationSample", values=dict(weight=64))]))))
        (self.raw / "gc").mkdir()
        for role in self.roles:
            name = "WorkloadMain" if role == "driver" else role
            (self.raw / "gc" / (name + "-gc.log")).write_text("SYNTHETIC GC FIXTURE")
        (self.raw / "exit-code.txt").write_text("0\n")
        self.result = dict(exitCode=0, driverExitCode=0, profilingCaptured=True,
            sourceRevision=m.EXPECTED_SOURCE, startedAt="SYNTHETIC-NOT-A-REAL-TIMESTAMP",
            assertions=dict(completedExecutions=10000, failed=0), measurements=self.measurements)
        (self.raw / "workload-10000.json").write_text(json.dumps(self.result))
        self.environment = dict(sourceFingerprint=m.EXPECTED_SOURCE, rootPomSha256=m.EXPECTED_ROOT_POM,
            containerImage=m.EXPECTED_IMAGE, cpu="SYNTHETIC", cgroupLimits={"cpu.max": "SYNTHETIC"},
            jdk="SYNTHETIC", jvmFlags="SYNTHETIC")
        self.write_environment()

    def write_frames(self):
        (self.raw / "resource-samples.jsonl").write_text("".join(json.dumps(frame) + "\n" for frame in self.frames))

    def write_environment(self):
        (self.raw / "environment.json").write_text(json.dumps(self.environment))

    def test_complete_profile_schema_accepted_without_any_subprocess(self):
        result = m.validate_profile_evidence(self.raw, self.measurements)
        self.assertEqual(1, result["completeWorkingFrames"])
        self.assertEqual(13, len(result["perRoleGcFiles"]))
        self.assertEqual(64, result["worker1EstimatedAllocatedBytes"])

    def test_any_incomplete_driver_frame_is_rejected(self):
        self.frames.append(copy.deepcopy(self.frames[0]))
        self.frames[-1]["processes"] = [item for item in self.frames[-1]["processes"] if item["role"] != "worker-8"]
        self.write_frames()
        with self.assertRaisesRegex(ValueError, "incomplete live"):
            m.validate_profile_evidence(self.raw, self.measurements)

    def test_resource_evidence_requires_a_real_driver_frame(self):
        self.frames[0]["processes"] = [item for item in self.frames[0]["processes"] if item["role"] != "driver"]
        self.write_frames()
        with self.assertRaisesRegex(ValueError, "lacks live driver"):
            m.validate_profile_evidence(self.raw, self.measurements)

    def test_nonempty_but_invalid_allocation_json_rejected(self):
        (self.raw / "allocation-samples.json").write_text("NOT JSON")
        with self.assertRaises(json.JSONDecodeError):
            m.validate_profile_evidence(self.raw, self.measurements)

    def test_invalid_allocation_schema_or_weight_rejected(self):
        for events in ({}, [dict(type="jdk.ObjectAllocationSample", values=dict(weight=float("nan")))]):
            with self.subTest(events=events):
                (self.raw / "allocation-samples.json").write_text(json.dumps(dict(recording=dict(events=events))))
                with self.assertRaises(ValueError):
                    m.validate_profile_evidence(self.raw, self.measurements)

    def test_all_three_percentile_vectors_checked_per_run(self):
        for name in ("completionLatencyMs", "scheduleToStartMs", "timerLatenessMs"):
            for bad in ("{1,2}", "{1,NaN,3}", "{1,2,Infinity}"):
                with self.subTest(name=name, bad=bad):
                    values = dict(self.measurements, **{name: bad})
                    with self.assertRaisesRegex(ValueError, "percentile"):
                        m.validate_profile_evidence(self.raw, values)

    def test_any_gc_is_insufficient_when_a_process_log_missing(self):
        (self.raw / "gc/WorkloadMain-gc.log").unlink()
        with self.assertRaisesRegex(ValueError, "GC evidence for driver"):
            m.validate_profile_evidence(self.raw, self.measurements)

    def configure_round_stubs(self):
        phases = []
        def command(label, arguments, directory, timeout, required=True, monitor=True):
            phases.append(label)
            log = directory / (label + ".log")
            log.write_text("SYNTHETIC STUB, NO COMMAND EXECUTED\n")
            return 0, log
        self.runner.command = Mock(side_effect=command)
        self.runner.preflight = Mock()
        self.runner.prepared = Mock()
        self.runner.clean_database = Mock()
        self.runner.disk = Mock()
        self.runner.cleanup = Mock(return_value=[])
        self.runner.capture = Mock(return_value=(self.raw, []))
        return phases

    def test_bad_first_round_profile_stops_before_accept_or_another_reset(self):
        self.frames[0]["processes"] = [item for item in self.frames[0]["processes"] if item["role"] != "worker-8"]
        self.write_frames()
        phases = self.configure_round_stubs()
        with patch.object(m, "audit_provider") as audit:
            with self.assertRaises(m.CommandFailure):
                for number in (1, 2, 3):
                    self.runner.round(number)
            audit.assert_not_called()
        self.assertEqual(1, phases.count("reset-demo"))
        self.runner.cleanup.assert_called_once()
        self.assertFalse(self.runner.directories)
        self.assertEqual(1, self.runner.outcomes[0]["exitCode"])
        self.assertIn("incomplete live", self.runner.outcomes[0]["failure"])
        self.assertTrue((self.raw / "allocation-samples.json").is_file())
        self.assertEqual(["before-reset", "before-start", "before-full-workload"],
                         [call.args[0] for call in self.runner.disk.call_args_list])

    def test_environment_drift_rejected_before_accepting_second_round(self):
        directory = self.base / "validation"
        directory.mkdir()
        with patch.object(m, "audit_provider", return_value={"SYNTHETIC": True}) as audit:
            self.runner.validate(directory, self.raw)
            self.environment["jdk"] = "DIFFERENT-SYNTHETIC-JDK"
            self.write_environment()
            with self.assertRaisesRegex(ValueError, "environment differs"):
                self.runner.validate(directory, self.raw)
            self.assertEqual(1, audit.call_count)

    def test_low_space_refuses_before_reset_or_start(self):
        self.runner.preflight = Mock()
        self.runner.command = Mock()
        self.runner.cleanup = Mock()
        with patch.object(m.shutil, "disk_usage", return_value=Mock(free=1)):
            with self.assertRaises(m.CommandFailure):
                self.runner.round(1)
        self.runner.preflight.assert_not_called()
        self.runner.command.assert_not_called()
        self.runner.cleanup.assert_not_called()
        self.assertIn("disk reserve refused before-reset", self.runner.outcomes[0]["failure"])

    def test_monitored_floor_terminates_only_the_owned_mock_child(self):
        process = Mock(returncode=-15)
        process.poll.return_value = None
        directory = self.base / "command"
        directory.mkdir()
        with patch.object(m.subprocess, "Popen", return_value=process) as spawn, \
             patch.object(m.shutil, "disk_usage", return_value=Mock(free=1)), \
             patch.object(m, "stop_group") as stop:
            with self.assertRaisesRegex(OSError, "disk reserve refused"):
                self.runner.command("SYNTHETIC-MONITOR", ["FORBIDDEN-REAL-COMMAND"], directory, 10)
            stop.assert_called_once_with(process)
            spawn.assert_called_once()
        self.assertIsNone(m.ACTIVE)
        self.assertEqual(-15, self.runner.steps[-1]["exitCode"])

    def test_preservation_reserve_retains_original_and_aborts_copy(self):
        runtime = self.base / "runtime"
        runtime.mkdir()
        provider = runtime / "provider-effects.log"
        provider.write_text("SYNTHETIC RAW ORIGINAL\n" * 10)
        original = provider.read_bytes()
        performance = self.base / "performance"
        performance.mkdir()
        (performance / "SYNTHETIC-10000-capture").mkdir()
        directory = self.base / "preserve"
        directory.mkdir()
        free = m.PRESERVATION_RESERVE_BYTES + 1
        with patch.object(m, "RUNTIME", runtime), patch.object(m, "PERFORMANCE", performance), \
             patch.object(m.shutil, "disk_usage", return_value=Mock(free=free)):
            with self.assertRaisesRegex(OSError, "before-preservation"):
                self.runner.capture(directory, set(), "SYNTHETIC")
        self.assertEqual(original, provider.read_bytes())
        self.assertFalse((directory / "provider-effects.log").exists())
        self.assertTrue((directory / "preservation-refusal.json").is_file())

    def test_low_disk_cannot_block_always_down_cleanup_commands(self):
        directory = self.base / "cleanup"
        directory.mkdir()
        process = Mock(returncode=0)
        process.poll.return_value = 0
        with patch.object(m.subprocess, "Popen", return_value=process) as spawn, \
             patch.object(self.runner, "disk", side_effect=AssertionError("cleanup disk probe forbidden")) as disk:
            errors = self.runner.cleanup(directory)
        self.assertEqual([], errors)
        self.assertEqual(3, spawn.call_count)
        disk.assert_not_called()
        self.assertEqual(0, json.loads((directory / "cleanup.json").read_text())["exitCode"])

    def compress_allocation(self, raw=None):
        raw = raw or self.raw
        plain = raw / 'allocation-samples.json'
        compressed = raw / 'allocation-samples.json.gz'
        compressed.write_bytes(gzip.compress(plain.read_bytes(), mtime=0))
        plain.unlink()
        return compressed

    def test_gzip_and_plain_profile_evidence_are_identical(self):
        plain = m.validate_profile_evidence(self.raw, self.measurements)
        self.compress_allocation()
        self.assertEqual(plain, m.validate_profile_evidence(self.raw, self.measurements))

    def test_missing_or_ambiguous_plain_and_gzip_recordings_rejected(self):
        plain = self.raw / 'allocation-samples.json'
        compressed = self.raw / 'allocation-samples.json.gz'
        compressed.write_bytes(gzip.compress(plain.read_bytes(), mtime=0))
        with self.assertRaisesRegex(ValueError, 'Exactly one'):
            m.validate_profile_evidence(self.raw, self.measurements)
        plain.unlink()
        compressed.unlink()
        with self.assertRaisesRegex(ValueError, 'Exactly one'):
            m.validate_profile_evidence(self.raw, self.measurements)

    def test_gzip_truncation_crc_and_malformed_json_cannot_pass_profile_checks(self):
        compressed = self.compress_allocation()
        valid = compressed.read_bytes()
        bad_crc = valid[:-8] + bytes([valid[-8] ^ 1]) + valid[-7:]
        for content in (valid[:-5], bad_crc, gzip.compress(b'NOT JSON', mtime=0)):
            with self.subTest(content=content):
                compressed.write_bytes(content)
                with self.assertRaises((EOFError, OSError, ValueError)):
                    m.validate_profile_evidence(self.raw, self.measurements)

    def test_gzip_keeps_schema_finite_weight_and_all_role_validation(self):
        compressed = self.compress_allocation()
        for events in ({}, [dict(type='jdk.ObjectAllocationSample', values=dict(weight=True))],
                       [dict(type='jdk.ObjectAllocationSample', values=dict(weight=float('inf'))) ]):
            with self.subTest(events=events):
                compressed.write_bytes(gzip.compress(json.dumps(dict(recording=dict(events=events))).encode(), mtime=0))
                with self.assertRaises(ValueError):
                    m.validate_profile_evidence(self.raw, self.measurements)
        compressed.write_bytes(gzip.compress(json.dumps(dict(recording=dict(events=[]))).encode(), mtime=0))
        self.frames[0]['processes'] = [row for row in self.frames[0]['processes'] if row['role'] != 'worker-8']
        self.write_frames()
        with self.assertRaisesRegex(ValueError, 'incomplete live'):
            m.validate_profile_evidence(self.raw, self.measurements)

    def test_corrupt_gzip_first_round_stops_before_another_reset_and_preserves_blob(self):
        compressed = self.compress_allocation()
        compressed.write_bytes(compressed.read_bytes()[:-5])
        original = compressed.read_bytes()
        phases = self.configure_round_stubs()
        with patch.object(m, 'audit_provider') as audit:
            with self.assertRaises(m.CommandFailure):
                for number in (1, 2, 3):
                    self.runner.round(number)
            audit.assert_not_called()
        self.assertEqual(1, phases.count('reset-demo'))
        self.assertFalse(self.runner.directories)
        self.assertEqual(original, compressed.read_bytes())
        self.runner.cleanup.assert_called_once()

    def test_three_run_summary_statistics_identical_for_plain_and_gzip(self):
        plain_directories, gzip_directories = [], []
        for number in range(3):
            plain = self.base / ('summary-plain-' + str(number))
            zipped = self.base / ('summary-gzip-' + str(number))
            shutil.copytree(self.raw, plain)
            result = json.loads((plain / 'workload-10000.json').read_text())
            result['startedAt'] = 'SYNTHETIC-DISTINCT-' + str(number)
            (plain / 'workload-10000.json').write_text(json.dumps(result))
            shutil.copytree(plain, zipped)
            self.compress_allocation(zipped)
            plain_directories.append(str(plain))
            gzip_directories.append(str(zipped))

        def summarize(directories):
            output = io.StringIO()
            with patch.object(sys, 'argv', [str(m.SUMMARY), *directories]), contextlib.redirect_stdout(output):
                runpy.run_path(str(m.SUMMARY), run_name='__main__')
            result = json.loads(output.getvalue())
            for key in ('timestamp', 'command'):
                result.pop(key)
            for row in result['runs']:
                row.pop('directory')
            return result

        plain = summarize(plain_directories)
        self.assertEqual(plain, summarize(gzip_directories))
        self.assertEqual('established', plain['regressionBudget']['status'])
        self.assertEqual(3, plain['assertions']['fullRuns'])


if __name__ == "__main__":
    unittest.main(verbosity=2)
