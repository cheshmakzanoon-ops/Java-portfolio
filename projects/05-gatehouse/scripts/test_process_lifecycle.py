"""Exercise real descendants and startup failure ownership with controlled external adapters."""

import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPTS = Path(__file__).resolve().parent


def alive(pid):
    try:
        return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0] != "Z"
    except FileNotFoundError:
        return False


def await_file(path):
    deadline = time.monotonic() + 5
    while not path.exists():
        if time.monotonic() >= deadline:
            raise AssertionError(f"fixture startup deadline: {path.name}")
        time.sleep(0.01)


@unittest.skipUnless(hasattr(os, "killpg") and Path("/proc").exists(), "POSIX process ownership test")
class ProcessLifecycleTest(unittest.TestCase):
    def launch(self, directory, parent_waits, parent_resists=False):
        pid_file = directory / "descendant.pid"
        ready = directory / "ready"
        descendant = (
            "import signal,time; from pathlib import Path; "
            "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
            f"Path({str(ready)!r}).touch(); time.sleep(120)"
        )
        parent = (
            "import subprocess,sys,time,signal; from pathlib import Path; "
            + (f"signal.signal(signal.SIGTERM,lambda *args: Path({str(directory / 'cleanup')!r}).touch()); "
               if parent_resists else "") +
            f"child=subprocess.Popen([sys.executable,'-c',{descendant!r}]); "
            f"Path({str(pid_file)!r}).write_text(str(child.pid)); "
            f"ready=Path({str(ready)!r}); "
            "\nwhile not ready.exists(): time.sleep(.01)\n"
            + ("time.sleep(120)" if parent_waits else "sys.exit(0)")
        )
        return pid_file, [sys.executable, "-c", parent]

    def assert_stopped(self, pid):
        deadline = time.monotonic() + 3
        while alive(pid) and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertFalse(alive(pid), "owned descendant survived controller completion")

    def cleanup(self, pid_file):
        if pid_file.exists():
            pid = int(pid_file.read_text())
            if alive(pid):
                os.kill(pid, signal.SIGKILL)

    def test_successful_parent_exit_cannot_leave_a_descendant(self):
        with tempfile.TemporaryDirectory() as temporary:
            pid_file, command = self.launch(Path(temporary), False)
            try:
                result = subprocess.run([sys.executable, str(SCRIPTS / "run-bounded.py"), "5", *command],
                                        timeout=12, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                self.cleanup(pid_file)

    def test_deadline_kills_an_entire_group_including_term_resistant_descendant(self):
        with tempfile.TemporaryDirectory() as temporary:
            pid_file, command = self.launch(Path(temporary), True)
            try:
                result = subprocess.run([sys.executable, str(SCRIPTS / "run-bounded.py"), "1", *command],
                                        timeout=12, capture_output=True, text=True)
                self.assertEqual(result.returncode, 124, result.stderr)
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                self.cleanup(pid_file)

    def test_repeated_signals_cannot_interrupt_owned_group_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            pid_file, command = self.launch(directory, True, parent_resists=True)
            controller = subprocess.Popen([sys.executable, str(SCRIPTS / "run-bounded.py"), "30", *command],
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                await_file(directory / "ready")
                controller.terminate()
                # The child's handler proves the controller entered its bounded cleanup wait.
                await_file(directory / "cleanup")
                controller.terminate()
                _, errors = controller.communicate(timeout=12)
                self.assertEqual(controller.returncode, 143, errors)
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                if controller.poll() is None:
                    controller.kill()
                    controller.communicate()
                self.cleanup(pid_file)

    def test_signals_preserve_shell_status_and_release_owned_group(self):
        result = subprocess.run([sys.executable, str(SCRIPTS / "run-bounded.py"), "5", sys.executable,
                                 "-c", "import os,signal; os.kill(os.getpid(),signal.SIGTERM)"],
                                timeout=12, capture_output=True, text=True)
        self.assertEqual(result.returncode, 143, result.stderr)
        with tempfile.TemporaryDirectory() as temporary:
            pid_file, command = self.launch(Path(temporary), True)
            controller = subprocess.Popen([sys.executable, str(SCRIPTS / "run-bounded.py"), "30", *command],
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                await_file(Path(temporary) / "ready")
                controller.terminate()
                _, errors = controller.communicate(timeout=12)
                self.assertEqual(controller.returncode, 143, errors)
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                if controller.poll() is None:
                    controller.kill()
                    controller.communicate()
                self.cleanup(pid_file)


@unittest.skipUnless(hasattr(os, "killpg") and Path("/proc").exists(), "POSIX startup ownership test")
class StartupOwnershipTest(unittest.TestCase):
    def fixture(self, directory, fail_migration):
        scripts = directory / "scripts"
        scripts.mkdir()
        runtime = directory / "runtime"
        runtime.mkdir()
        binaries = directory / "bin"
        binaries.mkdir()
        (directory / "demo").mkdir()
        (directory / "demo" / "seed.sql").write_text("")
        (scripts / "up.sh").write_text((SCRIPTS / "up.sh").read_text())
        (scripts / "prepare-runtime.sh").write_text("#!/usr/bin/env bash\nexit 0\n")
        (scripts / "prepare-runtime.sh").chmod(0o700)
        (binaries / "java").write_text("#!/usr/bin/env bash\nexec sleep 120\n")
        (binaries / "java").chmod(0o700)
        (binaries / "docker").write_text(
            '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> "$STARTUP_FIXTURE_DOCKER_LOG"\n')
        (binaries / "docker").chmod(0o700)
        (scripts / "common.sh").write_text(
            'PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)\n'
            'RUNTIME_DIR="$PROJECT_DIR/runtime"\n'
            'compose() { case "$1" in\n'
            ' ps) echo old-container; [[ ! -f "$RUNTIME_DIR/started" ]] || echo new-container ;;\n'
            ' up) touch "$RUNTIME_DIR/started" ;;\n'
            ' exec) cat >/dev/null ;;\n'
            ' esac; return 0; }\n'
            f'bounded() {{ return {17 if fail_migration else 0}; }}\n'
            'classpath() { echo fixture; }\n'
            'stop_service() { :; }\n'
            'stop_process() { echo "$1" >> "$RUNTIME_DIR/stopped"; kill "$1" 2>/dev/null || true; wait "$1" 2>/dev/null || true; }\n'
            'wait_for_services() { return 19; }\n'
            'fingerprint() { echo fixture; }\n'
        )
        environment = dict(os.environ)
        environment["PATH"] = str(binaries) + os.pathsep + environment["PATH"]
        environment["STARTUP_FIXTURE_DOCKER_LOG"] = str(runtime / "docker.log")
        return scripts / "up.sh", runtime, environment

    def check_failure(self, fail_migration):
        with tempfile.TemporaryDirectory() as temporary:
            script, runtime, environment = self.fixture(Path(temporary), fail_migration)
            result = subprocess.run(["bash", str(script)], env=environment, capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 17 if fail_migration else 19, result.stderr)
            self.assertEqual((runtime / "docker.log").read_text().splitlines(), ["rm -f new-container"])
            if fail_migration:
                self.assertFalse((runtime / "stopped").exists())
            else:
                pids = (runtime / "stopped").read_text().splitlines()
                self.assertEqual(len(pids), 2)
                for pid in pids:
                    self.assertFalse(alive(int(pid)), "startup left an owned service alive")
                self.assertFalse(list(runtime.glob("*.pid")))

    def test_migration_failure_removes_only_new_containers(self):
        self.check_failure(True)

    def test_readiness_failure_stops_only_services_started_by_this_invocation(self):
        self.check_failure(False)


if __name__ == "__main__":
    unittest.main()
