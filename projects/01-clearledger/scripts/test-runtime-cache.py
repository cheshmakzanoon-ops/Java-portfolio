#!/usr/bin/env python3
"""Filesystem and live-child CLI regression for launcher cache refusal boundaries."""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent


class RuntimeCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.project = self.root / "projects/01-clearledger"
        (self.project / "scripts").mkdir(parents=True)
        for name in ["runtime-cache.py", "runtime-process.py", "common.sh", "up.sh"]:
            shutil.copyfile(SCRIPTS / name, self.project / "scripts" / name)
        (self.root / "pom.xml").write_text("root version one")
        (self.project / "pom.xml").write_text("project version one")
        for module, cls in [("ledger-domain", "Money"), ("ledger-persistence", "LedgerStore"),
                            ("ledger-api", "Api"), ("ledger-verification", "Workload")]:
            folder = self.project / module
            (folder / "src").mkdir(parents=True)
            (folder / "src/Source.java").write_text("synthetic cache input")
            (folder / "pom.xml").write_text("module configuration")
            target = folder / "target/classes/io/github/cheshmakzanoon/portfolio/clearledger"
            target.mkdir(parents=True)
            (target / (cls + ".class")).write_bytes(b"synthetic artifact, not executable Java")
        self.jar = self.project / "ledger-api/target/clearledger-api-1.0.0-SNAPSHOT-exec.jar"
        self.jar.write_bytes(b"synthetic executable artifact")
        deps = self.project / "ledger-verification/target/dependency"
        deps.mkdir()
        (deps / "postgresql-test.jar").write_bytes(b"synthetic dependency")
        (self.project / "target").mkdir()

    def command(self, *args):
        return subprocess.run([sys.executable, str(self.project / "scripts/runtime-cache.py"), *args],
                              text=True, capture_output=True, timeout=10)

    def stamp(self):
        before = self.command("snapshot")
        self.assertEqual(0, before.returncode, before.stderr)
        result = self.command("write", before.stdout.strip())
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_receipt_refuses_and_unchanged_preparation_is_reusable(self):
        self.assertNotEqual(0, self.command("check").returncode)
        self.stamp()
        self.assertEqual(0, self.command("check").returncode)
        self.assertEqual(0, self.command("check").returncode)

    def test_source_and_root_changes_refuse(self):
        self.stamp()
        (self.project / "pom.xml").write_text("changed module configuration")
        self.assertNotEqual(0, self.command("check").returncode)
        self.stamp()
        (self.root / "pom.xml").write_text("changed dependency baseline")
        self.assertNotEqual(0, self.command("check").returncode)

    def test_source_change_during_build_cannot_stamp(self):
        before = self.command("snapshot").stdout.strip()
        (self.project / "ledger-domain/src/Source.java").write_text("changed after compile")
        result = self.command("write", before)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("changed during preparation", result.stderr)
        self.assertFalse((self.project / "target/prepared-runtime.json").exists())

    def test_changed_class_jar_and_missing_dependency_refuse(self):
        self.stamp()
        original = self.jar.read_bytes()
        self.jar.write_bytes(original + b"changed")
        self.assertNotEqual(0, self.command("check").returncode)
        self.jar.write_bytes(original)
        self.assertEqual(0, self.command("check").returncode)
        money = self.project / "ledger-domain/target/classes/io/github/cheshmakzanoon/portfolio/clearledger/Money.class"
        money.write_bytes(b"changed class")
        self.assertNotEqual(0, self.command("check").returncode)
        (self.project / "ledger-verification/target/dependency/postgresql-test.jar").unlink()
        self.assertNotEqual(0, self.command("check").returncode)

    def test_live_old_process_blocks_new_artifact_receipt_without_a_pid_file(self):
        self.stamp()
        # A real child holds an owned Java-shaped argv; it does not execute or prove Java behavior.
        child = subprocess.Popen(["java", "-c", "import time; time.sleep(30)", str(self.jar)],
                                 executable=sys.executable, env=dict(os.environ, PYTHONHOME=sys.prefix))
        self.addCleanup(lambda: child.poll() is None and child.kill())
        try:
            self.assertNotEqual(0, self.command("idle").returncode)
            self.jar.write_bytes(b"replacement while old process lives")
            self.assertNotEqual(0, self.command("check").returncode)
            before = self.command("snapshot").stdout.strip()
            result = self.command("write", before)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("still alive", result.stderr)
        finally:
            child.terminate()
            child.wait(timeout=10)
        self.stamp()
        self.assertEqual(0, self.command("check").returncode)

    def test_unknown_healthy_port_is_refused_before_build_or_resource_mutation(self):
        tools = self.root / "fake-tools"
        tools.mkdir()
        for name in ["curl", "docker"]:
            path = tools / name
            path.write_text("#!/bin/sh\n" + ("exit 0\n" if name == "curl" else "for arg; do [ \"$arg\" != ps ] || exit 0; done\ntouch '" + str(self.root / "unexpected-side-effect") + "'\nexit 1\n"))
            path.chmod(0o755)
        maven = self.root / "mvnw"
        maven.write_text("#!/bin/sh\ntouch '" + str(self.root / "unexpected-side-effect") + "'\nexit 1\n")
        maven.chmod(0o755)
        (self.root / "scripts").mkdir()
        (self.root / "scripts/cloud-env.sh").write_text("")
        env = dict(os.environ, PATH=str(tools) + ":" + os.environ["PATH"])
        result = subprocess.run(["bash", str(self.project / "scripts/up.sh")], env=env,
                                text=True, capture_output=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("untracked API", result.stderr)
        self.assertFalse((self.root / "unexpected-side-effect").exists())


if __name__ == "__main__":
    unittest.main()
