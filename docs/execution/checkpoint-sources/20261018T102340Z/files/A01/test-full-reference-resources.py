#!/usr/bin/env python3
"""Pure resource controls: tiny filesystem fixtures, mocked clocks/processes, no child launch."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import tempfile
import types
import unittest
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("reference_resources", HERE / "run-full-reference.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ResourceControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="a01-pure-resources-", dir="/tmp")
        self.addCleanup(self.temp.cleanup)
        self.base = pathlib.Path(self.temp.name)
        self.root = self.base / "workspace"
        self.root.mkdir()
        self.args = module.arguments(["--min-free-gib", "1", "--artifact-copy-root", str(self.base / "secondary")])
        self.study = module.ReferenceStudy(self.args, root=self.root)
        self.study.project.mkdir(parents=True)
        for name in ("Popen", "run", "check_output"):
            patcher = mock.patch.object(module.subprocess, name, side_effect=AssertionError("pure controls prohibit processes"))
            patcher.start()
            self.addCleanup(patcher.stop)
        for name in ("kill", "killpg"):
            patcher = mock.patch.object(module.os, name, side_effect=AssertionError("pure controls prohibit real signals"))
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = mock.patch.object(module.time, "sleep", side_effect=AssertionError("pure controls do not sleep"))
        patcher.start()
        self.addCleanup(patcher.stop)

    def raw(self, name="target/benchmark/r1-w1/raw.bin", data=b"retained raw bytes"):
        path = self.study.project / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def roomy(self):
        return mock.patch.object(module.os, "fstatvfs", return_value=types.SimpleNamespace(f_bavail=20 * module.GIB, f_frsize=1))

    def test_default_legacy_plan_is_inert_and_keeps_six_gib_default(self):
        self.assertIsNone(module.arguments([]).artifact_copy_root)
        self.assertEqual(6, module.arguments([]).min_free_gib)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, module.main([]))
        self.assertEqual("PLAN_ONLY_NO_COMMANDS_EXECUTED", json.loads(output.getvalue())["mode"])

    def test_opt_in_plan_describes_actual_admission_and_remains_inert(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, module.main(["--artifact-copy-root", str(self.args.artifact_copy_root), "--min-free-gib", "1"]))
        plan = json.loads(output.getvalue())
        self.assertEqual(module.RESOURCE_LAUNCH_MINIMUM, plan["resourcePolicy"]["initialRequiredBytes"])
        self.assertEqual(module.GIB, plan["resourcePolicy"]["workspaceFloorBytes"])
        self.assertFalse(self.args.artifact_copy_root.exists())

    def test_initial_admission_refuses_one_byte_below_five_point_two_five_gib(self):
        with mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=module.RESOURCE_LAUNCH_MINIMUM - 1)):
            with self.assertRaisesRegex(module.StudyError, "Insufficient disk"):
                self.study.preflight()
        check = self.study.manifest["diskChecks"][-1]
        self.assertEqual(module.RESOURCE_LAUNCH_MINIMUM, check["requiredBytes"])
        self.assertFalse(self.args.artifact_copy_root.exists())

    def test_initial_admission_captures_runner_without_launching_anything(self):
        (self.root / "pom.xml").write_text("fixture-root")
        self.args.expected_root_pom = module.sha(self.root / "pom.xml")
        target = self.study.project / "target"
        target.mkdir()
        for name in ("prepared-runtime.json", "prepared-jmh.json"):
            (target / name).write_text("fixture-receipt")
        source = self.study.project / "fixture-source.txt"
        source.write_text("fixture source")
        snapshot = {"sourceRevision": self.args.expected_source,
                    "rootPomSha256": self.args.expected_root_pom,
                    "sourceManifest": {str(source.relative_to(self.root)): module.sha(source)}}
        with mock.patch.object(module.shutil, "which", return_value="not-executed"), \
                mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=module.RESOURCE_LAUNCH_MINIMUM)), \
                self.roomy(), mock.patch.object(self.study, "snapshot", return_value=snapshot), \
                mock.patch.object(self.study, "cache_checks"), mock.patch.object(self.study, "assert_boundary"), \
                mock.patch.object(module.subprocess, "check_output", return_value="fixture-revision"):
            self.study.preflight()
        self.assertEqual(module.sha(HERE / "run-full-reference.py"), module.sha(self.study.directory / "reference-runner.py"))
        self.assertEqual(1, self.study.manifest["resourcePolicy"]["workspaceFloorBytes"] // module.GIB)

    def test_preworkload_full_remaining_allowance_is_additional_to_the_floor(self):
        required = module.GIB + module.RESOURCE_WORKLOAD_EXTRA
        with mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=required)):
            self.study.disk_guard("nine-100000-input-runs", module.RESOURCE_WORKLOAD_EXTRA)
        with mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=required - 1)):
            with self.assertRaises(module.StudyError):
                self.study.disk_guard("nine-100000-input-runs", module.RESOURCE_WORKLOAD_EXTRA)
        self.assertEqual(required, self.study.manifest["diskChecks"][-1]["requiredBytes"])

    def test_measured_wait_chunks_and_completed_observation_gaps_are_bounded(self):
        clock = [0.0]
        waits = []
        def wait(timeout):
            waits.append(timeout)
            clock[0] += timeout
            if len(waits) < 3:
                raise module.subprocess.TimeoutExpired("fixture", timeout)
            return 0
        self.study.active = mock.Mock()
        self.study.active.wait.side_effect = wait
        step = {}
        with mock.patch.object(module.time, "monotonic", side_effect=lambda: clock[0]), \
                mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=2 * module.GIB)):
            self.assertEqual(0, self.study.wait_for_active("full-workload", 30, step))
        self.assertEqual([4, 4, 4], waits)
        self.assertEqual(4, step["resourceMonitor"]["checks"])
        self.assertEqual(4, step["resourceMonitor"]["maximumObservedGapSeconds"])

    def test_monitor_preserves_total_command_timeout_and_short_final_wait(self):
        clock, waits = [0.0], []
        def wait(timeout):
            waits.append(timeout)
            clock[0] += timeout
            raise module.subprocess.TimeoutExpired("fixture", timeout)
        self.study.active = mock.Mock()
        self.study.active.wait.side_effect = wait
        with mock.patch.object(module.time, "monotonic", side_effect=lambda: clock[0]), \
                mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=2 * module.GIB)):
            with self.assertRaises(module.subprocess.TimeoutExpired):
                self.study.wait_for_active("full-jmh", 9, {})
        self.assertEqual([4, 4, 1], waits)

    def test_delayed_terminal_metadata_observation_cannot_look_on_time(self):
        clock = [0.0]
        observations = [0]
        def disk(path):
            observations[0] += 1
            if observations[0] == 2:
                clock[0] += 6
            return types.SimpleNamespace(free=2 * module.GIB)
        self.study.active = mock.Mock()
        def wait(timeout):
            clock[0] += 4
            return 0
        self.study.active.wait.side_effect = wait
        step = {}
        with mock.patch.object(module.time, "monotonic", side_effect=lambda: clock[0]), \
                mock.patch.object(module.shutil, "disk_usage", side_effect=disk):
            with self.assertRaisesRegex(module.StudyError, "five-second"):
                self.study.wait_for_active("full-workload", 30, step)
        self.assertEqual(10, step["resourceMonitor"]["maximumObservedGapSeconds"])
        self.assertEqual(6, step["resourceMonitor"]["maximumObservationDurationSeconds"])

    def test_initial_slow_metadata_and_between_check_descheduling_are_explicit_failures(self):
        with mock.patch.object(module.time, "monotonic", side_effect=[0, 6]), \
                mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=2 * module.GIB)):
            with self.assertRaisesRegex(module.StudyError, "five-second"):
                self.study.monitor_workspace_floor({}, None)
        with mock.patch.object(module.time, "monotonic", side_effect=[6, 6]), \
                mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=2 * module.GIB)):
            with self.assertRaisesRegex(module.StudyError, "five-second"):
                self.study.monitor_workspace_floor({}, 0)

    def test_floor_failure_enters_owned_cleanup_and_cleanup_command_is_exempt(self):
        leader = {"pid": 1200, "state": "S", "pgid": 1200, "session": 1200, "startTicks": 100}
        process = mock.Mock(pid=1200, returncode=-15)
        def settled():
            self.study.active_identity = None
        with mock.patch.object(module.subprocess, "Popen", return_value=process), \
                mock.patch.object(self.study, "process_identity", return_value=leader), \
                mock.patch.object(self.study, "group_members", return_value=[]), \
                mock.patch.object(self.study, "stop_active", side_effect=settled) as cleanup, \
                mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=module.GIB - 1)):
            with self.assertRaisesRegex(module.StudyError, "floor breached"):
                self.study.command("full-workload", ["not-executed"])
            cleanup.assert_called_once()
        process = mock.Mock(pid=1200, returncode=0)
        process.wait.return_value = 0
        with mock.patch.object(module.subprocess, "Popen", return_value=process), \
                mock.patch.object(self.study, "process_identity", return_value=leader), \
                mock.patch.object(self.study, "group_members", return_value=[]), \
                mock.patch.object(module.shutil, "disk_usage", side_effect=AssertionError("cleanup must not be monitored")):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, self.study.command("down", ["not-executed"]))

    def test_destination_uses_its_own_fd_capacity_plus_two_gib_and_metadata_allowance(self):
        payload = 17
        required = payload + 2 * module.GIB + module.RESOURCE_COPY_METADATA_ALLOWANCE
        with mock.patch.object(module.os, "fstatvfs", return_value=types.SimpleNamespace(f_bavail=required - 1, f_frsize=1)):
            with self.assertRaisesRegex(module.StudyError, "destination capacity"):
                self.study.destination_guard("fixture", payload)
        with mock.patch.object(module.os, "fstatvfs", return_value=types.SimpleNamespace(f_bavail=required, f_frsize=1)):
            self.study.destination_guard("fixture", payload)
        self.assertEqual(required, self.study.manifest["destinationDiskChecks"][-1]["requiredBytes"])

    def test_verified_copy_retains_originals_and_is_not_blocked_by_cleanup_workspace_floor(self):
        first = self.raw(data=b"raw\x00\xffdata")
        second = self.raw("target/microbenchmark/full/results.json", b"[]")
        third = self.raw("target/benchmark/empty.bin", b"")
        before = {path: path.read_bytes() for path in [first, second, third]}
        self.study.manifest["failure"] = "ORIGINAL_MEASUREMENT_FAILURE"
        with self.roomy(), mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=1)):
            self.study.preserve_current_outputs()
        proof = self.study.manifest["artifactPreservation"]
        self.assertEqual("PASS", proof["state"])
        self.assertEqual(3, proof["copiedFiles"])
        self.assertEqual(sum(map(len, before.values())), proof["copiedBytes"])
        destination = pathlib.Path(proof["destination"])
        for path, data in before.items():
            self.assertEqual(data, path.read_bytes())
            self.assertEqual(data, (destination / path.relative_to(self.study.project)).read_bytes())
        self.assertEqual("ORIGINAL_MEASUREMENT_FAILURE", self.study.manifest["failure"])
        self.assertFalse((self.study.directory / "current").exists(), "No workspace copy fallback")
        self.assertEqual("PASS", json.loads((destination / "copy-manifest.json").read_text())["state"])

    def test_destination_shortage_cannot_delete_or_relabel_original_failure(self):
        raw = self.raw()
        before = raw.read_bytes()
        self.study.manifest["failure"] = "ORIGINAL_MEASUREMENT_FAILURE"
        with mock.patch.object(module.os, "fstatvfs", return_value=types.SimpleNamespace(f_bavail=0, f_frsize=1)):
            with self.assertRaises(module.StudyError):
                self.study.preserve_current_outputs()
        self.assertEqual(before, raw.read_bytes())
        self.assertEqual("FAIL", self.study.manifest["artifactPreservation"]["state"])
        self.assertEqual("ORIGINAL_MEASUREMENT_FAILURE", self.study.manifest["failure"])
        self.assertFalse((self.study.directory / "current").exists())

    def test_existing_attempt_destination_is_never_overwritten(self):
        raw = self.raw()
        destination = self.args.artifact_copy_root / self.study.directory.name
        destination.mkdir(parents=True)
        marker = destination / "previous"
        marker.write_bytes(b"immutable previous attempt")
        with self.roomy(), self.assertRaises(FileExistsError):
            self.study.preserve_current_outputs()
        self.assertEqual(b"immutable previous attempt", marker.read_bytes())
        self.assertEqual(b"retained raw bytes", raw.read_bytes())
        self.assertEqual(["previous"], [p.name for p in destination.iterdir()])

    def test_non_tmp_root_parent_traversal_and_symlink_root_are_refused(self):
        original = self.args.artifact_copy_root
        for name in ("relative", "/workspace/new-artifacts", "/tmp", "/tmp/../workspace/artifacts"):
            self.args.artifact_copy_root = pathlib.Path(name)
            with self.assertRaises(module.StudyError):
                self.study.secondary_root()
        outside = self.base / "outside"
        outside.mkdir()
        original.symlink_to(outside, target_is_directory=True)
        self.args.artifact_copy_root = original
        with self.assertRaises(module.StudyError):
            self.study.secondary_root()
        self.assertEqual([], list(outside.iterdir()))

    def test_source_symlinks_fifo_and_hardlinks_are_refused_without_following(self):
        raw = self.raw()
        outside = self.base / "outside-secret"
        outside.write_bytes(b"outside value")
        bad = raw.parent / "link"
        bad.symlink_to(outside)
        with self.assertRaisesRegex(module.StudyError, "Symlink/special/hardlinked"):
            self.study.artifact_inventory()
        bad.unlink()
        os.mkfifo(bad)
        with self.assertRaisesRegex(module.StudyError, "Symlink/special/hardlinked"):
            self.study.artifact_inventory()
        bad.unlink()
        os.link(raw, bad)
        with self.assertRaisesRegex(module.StudyError, "Symlink/special/hardlinked"):
            self.study.artifact_inventory()
        self.assertEqual(b"outside value", outside.read_bytes())

    def test_all_entry_file_depth_and_byte_bounds_refuse_without_large_fixtures(self):
        raw = self.raw(data=b"1234")
        cases = [("ARTIFACT_MAX_FILES", 0), ("ARTIFACT_MAX_ENTRIES", 1),
                 ("ARTIFACT_MAX_DEPTH", 1), ("ARTIFACT_MAX_BYTES", 3)]
        for constant, limit in cases:
            with self.subTest(constant=constant), mock.patch.object(module, constant, limit):
                with self.assertRaisesRegex(module.StudyError, "budget exceeded"):
                    self.study.artifact_inventory()
        self.assertEqual(b"1234", raw.read_bytes())

    def test_streaming_scandir_stops_at_entry_budget_without_materializing_tail(self):
        self.raw()
        consumed = [0]
        class SyntheticScanner:
            def __enter__(self): return self
            def __exit__(self, *args): return False
            def __iter__(self):
                for unused in range(1000000):
                    consumed[0] += 1
                    if consumed[0] > 3:
                        raise AssertionError("Unbounded scanner consumption")
                    yield types.SimpleNamespace(name="r1-w1")
        with mock.patch.object(module, "ARTIFACT_MAX_ENTRIES", 2), \
                mock.patch.object(module.os, "scandir", side_effect=lambda fd: SyntheticScanner()):
            with self.assertRaisesRegex(module.StudyError, "entry/depth budget"):
                self.study.artifact_inventory()
        self.assertLessEqual(consumed[0], 3)

    def test_destination_ancestor_substitution_cannot_create_outside_file_or_false_pass(self):
        raw = self.raw()
        outside = self.base / "outside"
        outside.mkdir()
        original = self.study.copy_stable_file
        swapped = [False]
        def race(source_root, destination_root, source_parent, target_parent, relative, identity):
            if not swapped[0]:
                destination = self.args.artifact_copy_root / self.study.directory.name
                parent = destination / relative.parent
                parent.rename(parent.with_name(parent.name + "-pinned"))
                parent.symlink_to(outside, target_is_directory=True)
                swapped[0] = True
            return original(source_root, destination_root, source_parent, target_parent, relative, identity)
        with self.roomy(), mock.patch.object(self.study, "copy_stable_file", side_effect=race):
            with self.assertRaises((OSError, module.StudyError)):
                self.study.preserve_current_outputs()
        self.assertTrue(swapped[0])
        self.assertFalse((outside / raw.name).exists())
        self.assertEqual([], list(outside.iterdir()))
        self.assertEqual("FAIL", self.study.manifest["artifactPreservation"]["state"])

    def test_top_destination_swap_keeps_manifest_write_on_pinned_directory(self):
        self.raw()
        outside = self.base / "outside"
        outside.mkdir()
        pinned = [None]
        original = self.study.copy_stable_artifact
        def race(*args):
            result = original(*args)
            destination = self.args.artifact_copy_root / self.study.directory.name
            moved = destination.with_name(destination.name + "-pinned")
            destination.rename(moved)
            destination.symlink_to(outside, target_is_directory=True)
            pinned[0] = moved
            return result
        with self.roomy(), mock.patch.object(self.study, "copy_stable_artifact", side_effect=race):
            with self.assertRaises((OSError, module.StudyError)):
                self.study.preserve_current_outputs()
        self.assertEqual([], list(outside.iterdir()))
        self.assertEqual("FAIL", json.loads((pinned[0] / "copy-manifest.json").read_text())["state"])

    def test_source_ancestor_swap_never_reads_replacement_secret(self):
        raw = self.raw()
        outside = self.base / "outside"
        outside.mkdir()
        (outside / raw.name).write_bytes(b"replacement secret")
        original = self.study.copy_stable_file
        def race(source_root, destination_root, source_parent, target_parent, relative, identity):
            raw.parent.rename(raw.parent.with_name("r1-w1-pinned"))
            raw.parent.symlink_to(outside, target_is_directory=True)
            return original(source_root, destination_root, source_parent, target_parent, relative, identity)
        with self.roomy(), mock.patch.object(self.study, "copy_stable_file", side_effect=race):
            with self.assertRaises((OSError, module.StudyError)):
                self.study.preserve_current_outputs()
        destination = self.args.artifact_copy_root / self.study.directory.name
        self.assertEqual(b"retained raw bytes", (destination / raw.relative_to(self.study.project)).read_bytes())
        self.assertEqual(b"replacement secret", (outside / raw.name).read_bytes())
        self.assertEqual("FAIL", self.study.manifest["artifactPreservation"]["state"])

    def test_source_change_new_file_and_target_corruption_cannot_pass(self):
        for mode in ("source-change", "new-file", "target-corruption"):
            with self.subTest(mode=mode):
                args = module.arguments(["--min-free-gib", "1", "--artifact-copy-root", str(self.base / ("secondary-" + mode))])
                study = module.ReferenceStudy(args, root=self.root)
                raw = self.raw(data=b"original")
                original = study.copy_stable_artifact
                def race(source_fd, destination_fd, relative, identity):
                    if mode == "source-change":
                        raw.write_bytes(b"changed!")
                    result = original(source_fd, destination_fd, relative, identity)
                    if mode == "new-file":
                        (raw.parent / "new-file").write_bytes(b"late")
                    if mode == "target-corruption":
                        parent = study.open_relative_directory(destination_fd, relative.parts[:-1])
                        try:
                            fd = os.open(relative.name, os.O_WRONLY | os.O_TRUNC | os.O_NOFOLLOW, dir_fd=parent)
                            os.write(fd, b"corrupt!")
                            os.close(fd)
                        finally:
                            os.close(parent)
                    return result
                with self.roomy(), mock.patch.object(study, "copy_stable_artifact", side_effect=race):
                    if mode == "target-corruption":
                        # Post-return corruption must also be caught by final destination audit.
                        with self.assertRaises(module.StudyError): study.preserve_current_outputs()
                    else:
                        with self.assertRaises(module.StudyError): study.preserve_current_outputs()
                self.assertEqual("FAIL", study.manifest["artifactPreservation"]["state"])
                late = raw.parent / "new-file"
                if late.exists(): late.unlink()

    def test_observed_database_dump_requirement_retains_size_plus_sixteen_mib(self):
        database = "clearledger_benchmark_r1_w1"
        observed = 142172687
        def command(name, argv, **options):
            path = pathlib.Path(options["binary_output"])
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((database + "\t" + str(observed) + "\n").encode()
                             if name.endswith("database-list") else b"synthetic dump control")
            return 0
        with mock.patch.object(self.study, "command", side_effect=command), \
                mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=4 * module.GIB)):
            self.study.dump_databases("fixture")
        check = self.study.manifest["diskChecks"][-1]
        self.assertEqual(module.GIB + observed + 16 * 1024**2, check["requiredBytes"])
        self.assertEqual("fixture-dump-" + database, check["phase"])

    def run_simulated_attempt(self, workspace_free, primary_code):
        self.raw()
        self.study.manifest["pureOrchestrationFixtureOnly"] = True
        calls = []
        def command(name, argv, **options):
            calls.append((name, [str(arg) for arg in argv]))
            if name == "full-workload" and primary_code:
                raise module.StudyError("synthetic actual-command-failure placeholder", primary_code)
            return 0
        with mock.patch.object(self.study, "preflight"), \
                mock.patch.object(self.study, "preserve_prior_outputs"), \
                mock.patch.object(self.study, "assert_boundary"), \
                mock.patch.object(self.study, "validate_identity"), \
                mock.patch.object(self.study, "dump_databases"), \
                mock.patch.object(self.study, "current_containers", return_value=[]), \
                mock.patch.object(self.study, "command", side_effect=command), self.roomy(), \
                mock.patch.object(module.shutil, "disk_usage", return_value=types.SimpleNamespace(free=workspace_free)):
            code = self.study.run()
        return code, calls

    def test_real_orchestrator_gates_workload_on_full_remaining_allowance_then_cleans_up(self):
        code, calls = self.run_simulated_attempt(module.GIB + module.RESOURCE_WORKLOAD_EXTRA - 1, 0)
        self.assertEqual(1, code)
        self.assertNotIn("full-workload", [name for name, _ in calls])
        self.assertIn("down", [name for name, _ in calls])
        self.assertEqual("FAIL", self.study.manifest["state"])
        self.assertEqual("PASS", self.study.manifest["artifactPreservation"]["state"])

    def test_primary_command_failure_stays_failed_even_when_secondary_preservation_succeeds(self):
        code, calls = self.run_simulated_attempt(10 * module.GIB, 23)
        self.assertEqual(23, code)
        self.assertEqual("FAIL", self.study.manifest["state"])
        self.assertEqual(23, self.study.manifest["exitCode"])
        self.assertEqual("PASS", self.study.manifest["artifactPreservation"]["state"])
        workload = next(argv for name, argv in calls if name == "full-workload")
        self.assertEqual(["100000", "3", "--prepared"], workload[-3:])
        jmh = next(argv for name, argv in calls if name == "full-jmh")
        self.assertEqual(["full", "--prepared"], jmh[-2:])

    def test_late_destination_hardlink_is_rejected_by_final_independent_audit(self):
        self.raw()
        original = self.study.copy_stable_artifact
        def race(source_root, destination_root, relative, identity):
            result = original(source_root, destination_root, relative, identity)
            parent = self.study.open_relative_directory(destination_root, relative.parts[:-1])
            try:
                os.link(relative.name, "late-hardlink", src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
            finally:
                os.close(parent)
            return result
        with self.roomy(), mock.patch.object(self.study, "copy_stable_artifact", side_effect=race):
            with self.assertRaisesRegex(module.StudyError, "hardlinked"):
                self.study.preserve_current_outputs()
        self.assertEqual("FAIL", self.study.manifest["artifactPreservation"]["state"])

    def test_copy_root_substitution_between_validation_and_open_cannot_escape(self):
        self.raw()
        root = self.args.artifact_copy_root
        root.mkdir()
        outside = self.base / "outside"
        outside.mkdir()
        original = self.study.open_directory
        raced = [False]
        def opened(path, create=False):
            if pathlib.Path(path) == root and create and not raced[0]:
                root.rename(root.with_name(root.name + "-pinned"))
                root.symlink_to(outside, target_is_directory=True)
                raced[0] = True
            return original(path, create)
        with self.roomy(), mock.patch.object(self.study, "open_directory", side_effect=opened):
            with self.assertRaises(OSError):
                self.study.preserve_current_outputs()
        self.assertTrue(raced[0])
        self.assertEqual([], list(outside.iterdir()))


if __name__ == "__main__":
    unittest.main()
