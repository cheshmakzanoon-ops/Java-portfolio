#!/usr/bin/env python3
"""Real JVM ownership and fake-Compose failure regressions; no Docker or Maven invocation."""
import errno
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("durableflow_lifecycle", SCRIPTS / "lifecycle.py")
lifecycle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lifecycle)


class LifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compilation = tempfile.TemporaryDirectory(prefix="durableflow-lifecycle-fixture-")
        root = Path(cls.compilation.name)
        source = root / "PropertiesLauncher.java"
        source.write_text('''package org.springframework.boot.loader.launch;
public class PropertiesLauncher {
  public static void main(String[] arguments) throws Exception {
    if ("1".equals(System.getenv("FAIL_PROVIDER")) && arguments.length > 0 && "provider".equals(arguments[0])) System.exit(19);
    Thread.sleep(120000);
  }
}
''')
        subprocess.run(["javac", "-proc:none", "-d", str(root), str(source)], check=True)
        cls.jar = root / "fixture.jar"
        subprocess.run(["jar", "--create", "--file", str(cls.jar), "--main-class",
                        "org.springframework.boot.loader.launch.PropertiesLauncher", "-C", str(root), "org"], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.compilation.cleanup()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="durableflow-lifecycle-")
        self.root = Path(self.directory.name)
        self.project = self.root / "projects/02-durableflow"
        (self.project / "scripts").mkdir(parents=True)
        for name in ("common.sh", "up.sh", "lifecycle.py"):
            shutil.copyfile(SCRIPTS / name, self.project / "scripts" / name)
        for module in ("workflow-api", "workflow-verification"):
            target = self.project / module / "target"
            target.mkdir(parents=True)
            shutil.copyfile(self.jar, target / ("durableflow-" + module + "-1.0.0-SNAPSHOT-exec.jar"))
        self.state = self.root / "docker-state.json"
        self.state.write_text(json.dumps({"all": ["prior-running", "prior-stopped"],
                                          "running": ["prior-running"], "commands": []}))
        self.fake = self.root / "docker"
        self.fake.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
path=Path(os.environ["FAKE_DOCKER_STATE"]);state=json.loads(path.read_text());args=sys.argv[1:]
state["commands"].append(args)
code=0
if args[0]=="compose":
  args=args[3:]
  if args[0]=="ps": print("\\n".join(state["running"] if "--status" in args else state["all"]))
  elif args[0]=="up":
    assert "--no-recreate" in args
    state["all"]=sorted(set(state["all"]+["new-container"]))
    state["running"]=state["all"][:]
    code=int(os.environ.get("FAIL_COMPOSE","0"))
  elif args[0]=="stop": state["running"]=[]
elif args[0]=="stop": state["running"].remove(args[-1])
elif args[0]=="rm":
  state["all"].remove(args[-1])
  if args[-1] in state["running"]:state["running"].remove(args[-1])
else:raise AssertionError(args)
path.write_text(json.dumps(state));sys.exit(code)
''')
        self.fake.chmod(0o755)
        self.environment = mock.patch.dict(os.environ, {"FAKE_DOCKER_STATE": str(self.state), "DURABLEFLOW_PREPARED": "0"})
        self.environment.start()
        self.runtime = lifecycle.Runtime(self.project, [str(self.fake)], readiness_timeout=0.2)

    def tearDown(self):
        try:
            self.runtime.stop(self.runtime.existing(), grace=1)
        finally:
            self.environment.stop()
            self.directory.cleanup()

    def assertContainersRestored(self):
        state = json.loads(self.state.read_text())
        self.assertEqual(["prior-running", "prior-stopped"], sorted(state["all"]))
        self.assertEqual(["prior-running"], state["running"])
        self.assertFalse(any("--volumes" in command or "-v" in command for command in state["commands"]))

    def test_build_failure_happens_before_any_acquisition_and_keeps_status(self):
        wrapper = self.root / "mvnw"
        wrapper.write_text("#!/usr/bin/env bash\nexit 37\n")
        wrapper.chmod(0o755)
        with mock.patch.dict(os.environ, {"PATH": str(self.root) + os.pathsep + os.environ["PATH"]}):
            result = subprocess.run(["bash", str(self.project / "scripts/up.sh")], capture_output=True, text=True, timeout=10)
        self.assertEqual(37, result.returncode, result.stderr)
        self.assertEqual([], json.loads(self.state.read_text())["commands"])
        self.assertEqual({}, self.runtime.existing())

    def test_live_identity_failure_cannot_fall_through_to_build_or_compose(self):
        record, _ = self.runtime.start_role("api")
        metadata = self.runtime.paths("api")[1]
        metadata.write_text(json.dumps({**record, "startTicks": record["startTicks"] + 1}))
        try:
            with mock.patch.dict(os.environ, {"PATH": str(self.root) + os.pathsep + os.environ["PATH"]}):
                result = subprocess.run(["bash", str(self.project / "scripts/up.sh")],
                                        capture_output=True, text=True, timeout=10)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertIn("identity differs", result.stderr)
            self.assertEqual([], json.loads(self.state.read_text())["commands"])
            self.assertIsNotNone(lifecycle.process(record["pid"]))
        finally:
            metadata.write_text(json.dumps(record))

    def test_process_snapshot_discards_an_exit_between_stat_and_cmdline(self):
        def stat(state):
            return "123 (java) " + " ".join([state] + ["0"] * 18 + ["456"])
        with mock.patch.object(lifecycle.Path, "read_text", side_effect=[stat("S"), stat("Z")]), \
             mock.patch.object(lifecycle.Path, "read_bytes", return_value=b""), \
             mock.patch.object(lifecycle.Path, "resolve", return_value=Path("/usr/bin/java")):
            self.assertIsNone(lifecycle.process(123))

    def test_partial_compose_failure_restores_prior_states_and_retains_failure(self):
        with mock.patch.dict(os.environ, {"FAIL_COMPOSE": "27"}):
            with self.assertRaises(subprocess.CalledProcessError) as failure:
                self.runtime.up()
        self.assertEqual(27, failure.exception.returncode)
        self.assertContainersRestored()
        self.assertEqual({}, self.runtime.existing())

    def test_readiness_failure_stops_only_acquired_processes_and_containers(self):
        prior, created = self.runtime.start_role("api")
        self.assertTrue(created)
        with self.assertRaisesRegex(lifecycle.LifecycleError, "readiness"):
            self.runtime.up()
        self.assertEqual(prior, self.runtime.owned("api"))
        self.assertEqual({"api"}, set(self.runtime.existing()))
        self.assertContainersRestored()

    def test_provider_exit_rolls_back_all_new_processes(self):
        with mock.patch.dict(os.environ, {"FAIL_PROVIDER": "1"}):
            with self.assertRaises(lifecycle.LifecycleError):
                self.runtime.up()
        self.assertEqual({}, self.runtime.existing())
        self.assertContainersRestored()

    def test_stale_start_identity_and_wrong_role_never_authorize_a_signal(self):
        record, _ = self.runtime.start_role("api")
        metadata = self.runtime.paths("api")[1]
        for changed in ({**record, "startTicks": record["startTicks"] + 1},
                        {**record, "arguments": record["arguments"][:-1] + ["provider"]}):
            metadata.write_text(json.dumps(changed))
            with self.assertRaises(lifecycle.LifecycleError):
                self.runtime.stop({"api": record}, grace=0.1)
            self.assertIsNotNone(lifecycle.process(record["pid"]))
            metadata.write_text(json.dumps(record))

    def test_identity_is_rechecked_after_the_kernel_handle_is_opened(self):
        record, _ = self.runtime.start_role("api")
        metadata = self.runtime.paths("api")[1]
        real_open = os.pidfd_open
        def opened(pid):
            descriptor = real_open(pid)
            metadata.write_text(json.dumps({**record, "startTicks": record["startTicks"] + 1}))
            return descriptor
        with mock.patch.object(lifecycle.os, "pidfd_open", side_effect=opened):
            with self.assertRaises(lifecycle.LifecycleError):
                self.runtime.stop({"api": record}, grace=0.1)
        self.assertIsNotNone(lifecycle.process(record["pid"]))
        metadata.write_text(json.dumps(record))

    def test_restricted_executable_observation_retains_kernel_and_exact_argument_fences(self):
        record, _ = self.runtime.start_role("api")
        real_resolve = Path.resolve
        def resolve(path, *arguments, **options):
            if str(path) == f"/proc/{record['pid']}/exe":
                raise PermissionError(errno.EACCES, "managed procfs restriction")
            return real_resolve(path, *arguments, **options)
        with mock.patch.object(Path, "iterdir", side_effect=PermissionError(errno.EACCES, "managed procfs restriction")):
            with self.assertRaisesRegex(lifecycle.LifecycleError, "run down, then up"):
                lifecycle.listener_belongs_to(record["pid"], 18200)
        with mock.patch.object(Path, "resolve", resolve):
            self.assertEqual(record, self.runtime.owned("api"))
            metadata = self.runtime.paths("api")[1]
            metadata.write_text(json.dumps({**record, "arguments": record["arguments"][:-1] + ["provider"]}))
            try:
                with self.assertRaises(lifecycle.LifecycleError):
                    self.runtime.stop({"api": record}, grace=0.1)
                self.assertIsNotNone(lifecycle.process(record["pid"]))
            finally:
                metadata.write_text(json.dumps(record))
            self.runtime.stop({"api": record}, grace=1)
            self.assertIsNone(lifecycle.process(record["pid"]))

    def test_numeric_pid_without_start_record_is_not_adopted(self):
        record, _ = self.runtime.start_role("api")
        metadata = self.runtime.paths("api")[1]
        metadata.unlink()
        with self.assertRaises(lifecycle.LifecycleError):
            self.runtime.owned("api")
        self.assertIsNotNone(lifecycle.process(record["pid"]))
        metadata.write_text(json.dumps(record))


if __name__ == "__main__":
    unittest.main()
