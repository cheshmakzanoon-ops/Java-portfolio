#!/usr/bin/env python3
"""Independent filesystem/CLI regressions for preparation refusal paths."""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


HELPER = pathlib.Path(__file__).with_name("prepared-build.py").resolve()


class PreparedRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.project = self.root / "projects/02-durableflow"
        (self.project / "scripts").mkdir(parents=True)
        (self.project / "target").mkdir()
        (self.root / "pom.xml").write_text("root version one")
        (self.project / "pom.xml").write_text("project version one")
        for module in ("workflow-model", "workflow-engine", "workflow-persistence",
                       "workflow-worker-sdk", "workflow-api", "workflow-verification"):
            target = self.project / module / "target"
            target.mkdir(parents=True)
            (target / f"durableflow-{module}-1.0.0-SNAPSHOT.jar").write_bytes(b"built module")
            if module in ("workflow-api", "workflow-verification"):
                (target / f"durableflow-{module}-1.0.0-SNAPSHOT-exec.jar").write_bytes(b"runtime JAR")
        (self.project / "target/preparation.log").write_text("[INFO] BUILD SUCCESS\n")

    def command(self, *args):
        return subprocess.run([sys.executable, str(HELPER), *args, "--project", str(self.project)],
                              capture_output=True, text=True, timeout=10)

    def stamp(self):
        snapshot = self.command("snapshot")
        self.assertEqual(0, snapshot.returncode, snapshot.stderr)
        result = self.command("stamp", "--expected", snapshot.stdout)
        self.assertEqual(0, result.returncode, result.stderr)

    def refused(self):
        result = self.command("verify")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Prepared runtime refused", result.stderr)

    def test_unchanged_preparation_is_accepted_repeatedly(self):
        self.stamp()
        for _ in range(2):
            result = self.command("verify")
            self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_receipt_refuses(self):
        self.refused()

    def test_source_change_refuses(self):
        self.stamp()
        (self.project / "scripts/new-source.sh").write_text("changed source")
        self.refused()

    def test_root_dependency_change_refuses(self):
        self.stamp()
        (self.root / "pom.xml").write_text("changed dependency baseline")
        self.refused()

    def test_runtime_jar_tampering_refuses(self):
        self.stamp()
        (self.project / "workflow-api/target/durableflow-workflow-api-1.0.0-SNAPSHOT-exec.jar").write_bytes(b"tampered")
        self.refused()

    def test_deleted_module_jar_refuses(self):
        self.stamp()
        (self.project / "workflow-engine/target/durableflow-workflow-engine-1.0.0-SNAPSHOT.jar").unlink()
        self.refused()

    def test_changed_verification_log_or_exit_status_refuses(self):
        self.stamp()
        receipt_file = self.project / "target/prepared-build.json"
        receipt = json.loads(receipt_file.read_text())
        receipt["verification"]["exitCode"] = 1
        receipt_file.write_text(json.dumps(receipt))
        self.refused()
        self.stamp()
        (self.project / "target/preparation.log").write_text("changed log")
        self.refused()

    def test_failed_build_or_source_change_during_build_cannot_stamp(self):
        expected = self.command("snapshot").stdout
        (self.project / "target/preparation.log").write_text("BUILD FAILURE")
        self.assertNotEqual(0, self.command("stamp", "--expected", expected).returncode)
        (self.project / "target/preparation.log").write_text("BUILD SUCCESS")
        (self.project / "pom.xml").write_text("changed during build")
        self.assertNotEqual(0, self.command("stamp", "--expected", expected).returncode)
        self.assertFalse((self.project / "target/prepared-build.json").exists())

    def test_old_live_process_prevents_new_disk_preparation_without_pid_file(self):
        self.stamp()
        jar = self.project / "workflow-api/target/durableflow-workflow-api-1.0.0-SNAPSHOT-exec.jar"
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)", str(jar)])
        try:
            # Simulate rebuilt bytes while the earlier process still holds old loaded classes.
            jar.write_bytes(b"new disk JAR while old process lives")
            expected = self.command("snapshot").stdout
            stopped = self.command("idle")
            self.assertNotEqual(0, stopped.returncode)
            self.assertIn(str(child.pid), stopped.stderr)
            self.assertNotEqual(0, self.command("stamp", "--expected", expected).returncode)
            self.refused()
        finally:
            child.terminate()
            child.wait(timeout=5)
        self.assertEqual(0, self.command("idle").returncode)
        self.stamp()
        self.assertEqual(0, self.command("verify").returncode)


if __name__ == "__main__":
    unittest.main()
