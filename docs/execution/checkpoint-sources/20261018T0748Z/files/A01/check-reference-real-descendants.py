#!/usr/bin/env python3
"""Two tiny real process-control checks. Inert unless explicitly released by coordinator."""
import argparse
import ctypes
import hashlib
import importlib.util
import json
import os
import pathlib
import signal
import subprocess
import sys
import tempfile
import time
import unittest

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("reference_real_descendants", HERE / "run-full-reference.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

CHILD = """
import json,os,pathlib,signal,sys,time
signal.signal(signal.SIGTERM,signal.SIG_IGN)
fields=pathlib.Path('/proc/self/stat').read_text().rsplit(')',1)[1].split()
target=pathlib.Path(sys.argv[1]);temporary=target.with_suffix('.tmp')
temporary.write_text(json.dumps({'pid':os.getpid(),'startTicks':int(fields[19])}));temporary.replace(target)
time.sleep(60)
"""
LAUNCHER = """
import pathlib,subprocess,sys,time
subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2]])
while not pathlib.Path(sys.argv[2]).exists():time.sleep(.01)
if sys.argv[3]=='exit-first':raise SystemExit(0)
time.sleep(60)
"""


class RealDescendantChecks(unittest.TestCase):
    def run_case(self, mode):
        with tempfile.TemporaryDirectory(dir=HERE, prefix="real-descendant-") as temp:
            root = pathlib.Path(temp)
            ready = root / "child-ready.json"
            study = module.ReferenceStudy(module.arguments([]), root=root)
            study.termination_grace_seconds = .15
            study.kill_grace_seconds = 2
            process = subprocess.Popen([sys.executable, "-c", LAUNCHER, CHILD, str(ready), mode],
                                       start_new_session=True, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL)
            identity = module.ReferenceStudy.process_identity(process.pid)
            study.active = process
            study.active_identity = identity
            child = None
            try:
                deadline = time.monotonic() + 5
                while not ready.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue(ready.exists(), "child installed TERM handler and reported identity")
                child = json.loads(ready.read_text())
                if mode == "exit-first":
                    self.assertEqual(0, process.wait(timeout=2))
                study.stop_active()
                proof = study.manifest["terminationAttempts"][-1]
                self.assertTrue(any(row["pid"] == child["pid"] for row in proof["afterTermGrace"]))
                self.assertEqual([], proof["afterKillGrace"])
                self.assertIsNone(study.active_identity)
                self.assertEqual([], study.group_members(identity))
                process.wait(timeout=2)
            finally:
                # Fixture recovery is independent of the cleanup implementation under test.
                # The direct launcher PID cannot be reused before wait, and a surviving session
                # keeps its group identity; still check any present leader's start identity.
                current_leader = module.ReferenceStudy.process_identity(process.pid)
                if current_leader is None or identity is None or current_leader["startTicks"] == identity["startTicks"]:
                    try:os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:pass
                for candidate in [child, identity]:
                    if candidate:
                        current = module.ReferenceStudy.process_identity(candidate["pid"])
                        if current and current["startTicks"] == candidate["startTicks"]:
                            try:os.kill(candidate["pid"], signal.SIGKILL)
                            except ProcessLookupError:pass
                process.wait(timeout=2)
                if child:
                    deadline = time.monotonic() + 2
                    while time.monotonic() < deadline:
                        try:
                            pid, _ = os.waitpid(child["pid"], os.WNOHANG)
                        except ChildProcessError:
                            break
                        if pid:
                            break
                        time.sleep(.01)

    def test_term_exiting_launcher_with_term_resistant_grandchild(self):
        self.run_case("term-exit")

    def test_already_exited_launcher_keeps_owned_grandchild_identity(self):
        self.run_case("exit-first")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute-after-quiet-release", action="store_true")
    args = parser.parse_args()
    if not args.execute_after_quiet_release:
        print("PENDING: coordinator quiet release required; no child launched.")
        return 0
    # Adopt/reap the fixture grandchild when its launcher exits, avoiding orphan zombies.
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0:  # Linux PR_SET_CHILD_SUBREAPER
        raise SystemExit("Cannot become fixture subreaper; refusing real-child check")
    result = unittest.TextTestRunner(verbosity=2).run(
        unittest.defaultTestLoader.loadTestsFromTestCase(RealDescendantChecks))
    output = {"scope": "tiny real process-control checks; no Java or Docker", "tests": result.testsRun,
              "failures": len(result.failures), "errors": len(result.errors),
              "runnerSha256": hashlib.sha256((HERE / 'run-full-reference.py').read_bytes()).hexdigest()}
    (HERE / "full-reference-real-descendants-result.json").write_text(json.dumps(output, indent=2) + "\n")
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
