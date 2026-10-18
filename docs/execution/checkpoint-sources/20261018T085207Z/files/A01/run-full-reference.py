#!/usr/bin/env python3
"""Coordinator-only prepared reference study. Default is an inert execution plan."""
from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXPECTED_SOURCE = "working-tree:47936597eef8a1018263920d1d7b00a9b051fb2ecf293543d7e0e805c27f2bf5"
EXPECTED_POM = "3743d1ba382977b3f8d8609e1191190f220cdf272a5338d538869f96a7b7230d"


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def atomic_json(path, data):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, indent=2) + "\n")
    temporary.replace(path)


class StudyError(Exception):
    def __init__(self, message, code=1):
        super().__init__(message)
        self.code = code


class ReferenceStudy:
    termination_grace_seconds = 15
    kill_grace_seconds = 5

    def __init__(self, args, root=ROOT):
        self.args = args
        self.root = pathlib.Path(root)
        self.project = self.root / "projects/01-clearledger"
        stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        self.directory = self.root / ".work/A01/full-reference" / (stamp + "-" + uuid.uuid4().hex[:8])
        self.directory.mkdir(parents=True, exist_ok=False)
        self.active = None
        self.active_identity = None
        self.steps = []
        self.armed = False
        self.failure = None
        self.cleanup_code = None
        self.baseline = None
        self.manifest = {"schemaVersion": 1, "project": "clearledger", "reservation": args.reservation,
                         "startedAtUtc": utc(), "state": "PREPARING", "steps": self.steps,
                         "limitations": ["Current-checkout reference measurements; clean-checkout release remains separate.",
                                         "Catchable failure/signals run cleanup; SIGKILL or host loss requires coordinator recovery."]}
        self.save()

    def save(self):
        atomic_json(self.directory / "run.json", self.manifest)

    @staticmethod
    def process_identity(pid):
        try:
            fields = (pathlib.Path("/proc") / str(pid) / "stat").read_text().rsplit(")", 1)[1].split()
        except (OSError, IndexError):
            return None
        return {"pid": int(pid), "state": fields[0], "pgid": int(fields[2]),
                "session": int(fields[3]), "startTicks": int(fields[19])}

    def group_members(self, identity):
        leader = self.process_identity(identity["pid"])
        if leader is not None and leader["startTicks"] != identity["startTicks"]:
            raise StudyError("Owned group leader PID was reused; refusing unrelated signals")
        members = []
        for entry in pathlib.Path("/proc").iterdir():
            if not entry.name.isdecimal():
                continue
            process = self.process_identity(entry.name)
            if (process is not None and process["pgid"] == identity["pgid"]
                    and process["session"] == identity["session"] and process["state"] not in {"Z", "X"}):
                members.append(process)
        return members

    def wait_group(self, identity, seconds):
        deadline = time.monotonic() + seconds
        while True:
            # Reap the launcher promptly, but never use its exit as group completion.
            if self.active is not None:
                self.active.poll()
            members = self.group_members(identity)
            if not members or time.monotonic() >= deadline:
                return members
            time.sleep(.05)

    def stop_active(self):
        identity = self.active_identity
        if identity is None:
            return
        attempt = {"identity": dict(identity), "startedAtUtc": utc()}
        self.manifest.setdefault("terminationAttempts", []).append(attempt)
        self.save()
        members = self.group_members(identity)
        attempt["beforeTerm"] = members
        if members:
            try:
                os.killpg(identity["pgid"], signal.SIGTERM)
            except ProcessLookupError:
                pass
            survivors = self.wait_group(identity, self.termination_grace_seconds)
            attempt["afterTermGrace"] = survivors
            if survivors:
                # The launcher may have exited immediately; its surviving group still gets KILL.
                try:
                    os.killpg(identity["pgid"], signal.SIGKILL)
                except ProcessLookupError:
                    pass
                survivors = self.wait_group(identity, self.kill_grace_seconds)
            attempt["afterKillGrace"] = survivors
            if survivors:
                attempt["finishedAtUtc"] = utc()
                self.manifest.setdefault("unresolvedOwnedGroups", []).append(attempt)
                self.save()
                raise StudyError("Owned process group survived TERM/KILL; identities retained")
        if self.active is not None:
            self.active.poll()
        attempt["finishedAtUtc"] = utc()
        attempt["settled"] = True
        self.active_identity = None
        self.save()

    def command(self, name, argv, timeout=120, binary_output=None, check=True):
        if self.active_identity is not None:
            if name != "down":
                raise StudyError("Unsettled owned group; refusing another phase")
            self.manifest.setdefault("unresolvedOwnedGroups", []).append(
                {"identity": dict(self.active_identity), "cleanupContinues": "owned PostgreSQL down"})
            self.active_identity = None
        log = self.directory / (name + ".log")
        step = {"name": name, "command": [str(x) for x in argv], "startedAtUtc": utc(),
                "log": str(log), "timeoutSeconds": timeout}
        if binary_output:
            step["stdoutArtifact"] = str(binary_output)
            pathlib.Path(binary_output).parent.mkdir(parents=True, exist_ok=True)
        self.steps.append(step)
        self.save()
        started = time.monotonic()
        try:
            with log.open("wb") as stderr:
                if binary_output:
                    output = pathlib.Path(binary_output).open("wb")
                else:
                    output = stderr
                try:
                    self.active = subprocess.Popen([str(x) for x in argv], cwd=self.root,
                                                   stdout=output, stderr=stderr,
                                                   start_new_session=True)
                    self.active_identity = self.process_identity(self.active.pid)
                    if self.active_identity is None:
                        raise StudyError("Cannot retain launched process identity")
                    try:
                        code = self.active.wait(timeout=timeout)
                    except subprocess.TimeoutExpired:
                        code = 124
                        step["timedOut"] = True
                        try:
                            self.stop_active()
                        except BaseException as group_failure:
                            step["groupCleanupError"] = str(group_failure)
                    step["processReturnCode"] = self.active.returncode
                    if not step.get("timedOut") and self.active_identity is not None and self.group_members(self.active_identity):
                        step["ownedDescendantsAfterLauncherExit"] = True
                        self.stop_active()
                        raise StudyError("Launcher exited with live owned descendants; phase refused")
                finally:
                    if binary_output:
                        output.close()
        except BaseException:
            try:
                self.stop_active()
            except BaseException as group_failure:
                step["groupCleanupError"] = str(group_failure)
            step["interrupted"] = True
            if self.active is not None and self.active.returncode is not None:
                interrupted_code = self.active.returncode
                step["rawReturnCode"] = interrupted_code
                step["exitCode"] = interrupted_code if interrupted_code >= 0 else 128 - interrupted_code
            raise
        finally:
            # Keep an unresolved group identity until a recorded cleanup decision; do not lose it
            # merely because Popen.wait() reaped the launcher.
            if self.active_identity is not None:
                try:
                    if not self.group_members(self.active_identity):
                        self.active_identity = None
                except StudyError as identity_failure:
                    self.manifest.setdefault("groupIdentityErrors", []).append(str(identity_failure))
            self.active = None
            step["finishedAtUtc"] = utc()
            step["elapsedSeconds"] = time.monotonic() - started
            self.save()
        step["rawReturnCode"] = step.get("processReturnCode", code)
        step["exitCode"] = code if code >= 0 else 128 - code
        self.save()
        print(f"{utc()} {name}: exit {step['exitCode']} ({log})", flush=True)
        if code and check:
            raise StudyError(name + " failed; raw output retained in " + str(log), step["exitCode"])
        return step["exitCode"]

    def snapshot(self):
        result = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard",
                                 "projects/01-clearledger"], cwd=self.root, check=True,
                                capture_output=True, text=True)
        files = {}
        digest = hashlib.sha256()
        for relative in sorted(set(result.stdout.splitlines())):
            path = self.root / relative
            if not path.is_file() or "/docs/" in relative or "/evidence/" in relative:
                continue
            data = path.read_bytes()
            files[relative] = hashlib.sha256(data).hexdigest()
            digest.update(relative.encode() + b"\0" + data + b"\0")
        return {"sourceRevision": "working-tree:" + digest.hexdigest(),
                "rootPomSha256": sha(self.root / "pom.xml"), "sourceManifest": files}

    def locks_held(self):
        held = []
        for slot in range(3):
            path = self.root / f".work/build-slots/{slot}.lock"
            if not path.is_file():
                raise StudyError("Missing build lock; coordinator must reserve all three first")
            with path.open("rb") as stream:
                try:
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    held.append(str(path))
                else:
                    fcntl.flock(stream, fcntl.LOCK_UN)
                    raise StudyError("A build slot is free; coordinator must hold all three")
        return held

    @staticmethod
    def java_processes(proc_root=pathlib.Path("/proc")):
        processes = []
        for path in pathlib.Path(proc_root).iterdir():
            if not path.name.isdecimal():
                continue
            try:
                argv = (path / "cmdline").read_bytes().split(b"\0")
                comm = (path / "comm").read_text().strip()
            except OSError:
                continue
            executable = pathlib.Path(argv[0].decode(errors="replace")).name if argv else ""
            if executable in {"java", "javaw"} or comm in {"java", "javaw"}:
                state = "UNKNOWN"
                try:
                    prefix, suffix = (path / "stat").read_text().rsplit(")", 1)
                    fields = suffix.split()
                    if prefix.split(" ", 1)[0] == path.name and len(fields) >= 20:
                        state = fields[0]
                except (OSError, ValueError):
                    pass
                # Zombies cannot execute Java. Missing/malformed state is not proof
                # of a zombie: retain the process conservatively, including empty argv.
                if state == "Z":
                    continue
                processes.append({"pid": int(path.name), "executable": executable, "state": state})
        return processes

    def current_containers(self, name):
        self.command(name, ["docker", "ps", "--format", "{{.Names}}"], timeout=30)
        return [line for line in (self.directory / (name + ".log")).read_text().splitlines() if line]

    def assert_boundary(self, name, allow_database=False):
        locks = self.locks_held()
        java = self.java_processes()
        containers = self.current_containers(name + "-containers")
        allowed = {"java-portfolio-clearledger-postgres-1"} if allow_database else set()
        proof = {"observedAtUtc": utc(), "heldBuildLocks": locks, "javaProcesses": java,
                 "containers": containers, "allowedContainers": sorted(allowed)}
        atomic_json(self.directory / (name + "-quiet.json"), proof)
        if java or set(containers) - allowed:
            raise StudyError("Quiet boundary violated: active Java or unrelated containers")
        return proof

    def cache_checks(self, prefix):
        for helper in ["runtime-cache.py", "jmh-cache.py"]:
            self.command(prefix + "-" + helper.removesuffix(".py"),
                         [sys.executable, self.project / "scripts" / helper, "check"])

    def disk_guard(self, phase, additional_bytes=0):
        free = shutil.disk_usage(self.root).free
        required = self.args.min_free_gib * 1024**3 + max(0, additional_bytes)
        observation = {"phase": phase, "observedAtUtc": utc(), "freeBytes": free,
                       "reserveBytes": self.args.min_free_gib * 1024**3,
                       "additionalBytes": max(0, additional_bytes), "requiredBytes": required}
        self.manifest.setdefault("diskChecks", []).append(observation)
        self.save()
        if free < required:
            raise StudyError("Insufficient disk before " + phase + "; evidence and volumes retained")
        return free

    @staticmethod
    def output_bytes(path):
        if path.is_file():
            return path.stat().st_size
        return sum(entry.stat().st_size for entry in path.rglob("*") if entry.is_file())

    def preflight(self):
        for tool in ["java", "jfr", "docker", "python3", "git"]:
            if shutil.which(tool) is None:
                raise StudyError("Missing prepared prerequisite: " + tool)
        free = self.disk_guard("preflight")
        self.manifest["initialFreeDiskBytes"] = free
        self.baseline = self.snapshot()
        if self.baseline["sourceRevision"] != self.args.expected_source:
            raise StudyError("Frozen project source differs from requested baseline")
        if self.baseline["rootPomSha256"] != self.args.expected_root_pom:
            raise StudyError("Root POM differs from prepared baseline")
        self.manifest["baseline"] = self.baseline
        self.manifest["sourceCommit"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()
        self.manifest["runnerSha256"] = sha(__file__)
        self.cache_checks("preflight")
        self.assert_boundary("preflight")
        for relative in self.baseline["sourceManifest"]:
            destination = self.directory / "source" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(self.root / relative, destination)
        shutil.copy2(self.root / "pom.xml", self.directory / "source/root-pom.xml")
        for name in ["prepared-runtime.json", "prepared-jmh.json"]:
            shutil.copy2(self.project / "target" / name, self.directory / name)
        self.save()

    def preserve_prior_outputs(self):
        self.disk_guard("prior-output-preservation")
        relocations = []
        atomic_json(self.directory / "prior-relocations.json", relocations)
        for relative in ["target/benchmark", "target/microbenchmark/full",
                         "evidence/performance-current.json", "evidence/jmh-current.json"]:
            original = self.project / relative
            if not original.exists():
                continue
            if original.is_symlink():
                raise StudyError("Refusing a symlink in managed output: " + relative)
            destination = self.directory / "prior" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(original), destination)
            relocations.append({"original": str(original), "preserved": str(destination)})
            atomic_json(self.directory / "prior-relocations.json", relocations)

    @property
    def compose(self):
        return ["docker", "compose", "-f", str(self.project / "infra/compose.yaml")]

    def dump_databases(self, label):
        self.disk_guard(label + "-database-preservation")
        query = ("SELECT datname,pg_database_size(datname) FROM pg_database WHERE "
                 "datname ~ '^clearledger_benchmark_r[1-3]_w(1|8|32)$' ORDER BY datname")
        listing = self.directory / label / "database-names.txt"
        self.command(label + "-database-list", self.compose +
                     ["exec", "-T", "postgres", "psql", "-At", "-v", "ON_ERROR_STOP=1",
                      "-U", "postgres", "-d", "postgres", "-F", "\t", "-c", query], binary_output=listing)
        databases = listing.read_text().splitlines()
        for row in databases:
            database, size = row.split("\t")
            if not size.isdecimal():
                raise StudyError("Invalid read-only database-size observation")
            self.disk_guard(label + "-dump-" + database, int(size) + 16 * 1024**2)
            if not re.fullmatch(r"clearledger_benchmark_r[1-3]_w(1|8|32)", database):
                raise StudyError("Unexpected database name; refusing dump/drop workflow")
            output = self.directory / label / "databases" / (database + ".dump")
            self.command(label + "-dump-" + database, self.compose +
                         ["exec", "-T", "postgres", "pg_dump", "-Fc", "-U", "postgres", "-d", database],
                         timeout=900, binary_output=output)
            if output.stat().st_size == 0:
                raise StudyError("Empty database preservation dump")

    def validate_identity(self, prefix="identity", environments=None):
        if self.snapshot() != self.baseline:
            raise StudyError("Project/root source changed during reference measurements")
        self.cache_checks(prefix)
        for relative in (environments or ["target/microbenchmark/full/environment.json", "target/benchmark/environment.json"]):
            environment = json.loads((self.project / relative).read_text())
            if (environment["sourceRevision"] != self.baseline["sourceRevision"]
                    or environment["rootPomSha256"] != self.baseline["rootPomSha256"]):
                raise StudyError("Measured environment/source does not match frozen baseline")

    def preserve_current_outputs(self):
        managed = [self.project / relative for relative in ["target/benchmark", "target/microbenchmark/full",
                   "evidence/performance-current.json", "evidence/jmh-current.json"]]
        copy_bytes = sum(self.output_bytes(path) for path in managed if path.exists())
        self.disk_guard("raw-output-copy", copy_bytes)
        inventory = {}
        for relative in ["target/benchmark", "target/microbenchmark/full",
                         "evidence/performance-current.json", "evidence/jmh-current.json"]:
            source = self.project / relative
            if not source.exists():
                continue
            target = self.directory / "current" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
                files = [path for path in target.rglob("*") if path.is_file()]
            else:
                shutil.copy2(source, target)
                files = [target]
            for path in files:
                inventory[str(path.relative_to(self.directory))] = sha(path)
        atomic_json(self.directory / "current-artifacts-sha256.json", inventory)

    def run(self):
        code = 0
        try:
            self.preflight()
            self.armed = True
            self.preserve_prior_outputs()
            self.manifest["state"] = "MEASURING"
            self.save()
            self.disk_guard("full-jmh")
            self.command("full-jmh", ["bash", self.project / "scripts/microbenchmark.sh", "full", "--prepared"],
                         timeout=self.args.jmh_timeout_seconds)
            self.validate_identity("identity-jmh", ["target/microbenchmark/full/environment.json"])
            self.command("jmh-summary", [sys.executable, self.project / "scripts/summarize-jmh.py"])
            self.assert_boundary("after-jmh")
            self.disk_guard("database-start")
            self.command("database-start", self.compose + ["up", "-d", "--wait", "--wait-timeout", "90"])
            self.dump_databases("prior")
            self.assert_boundary("before-workload", allow_database=True)
            self.disk_guard("nine-100000-input-runs")
            self.command("full-workload", ["bash", self.project / "scripts/benchmark.sh", "100000", "3", "--prepared"],
                         timeout=self.args.workload_timeout_seconds)
            self.validate_identity("identity-workload")
            self.command("workload-summary", [sys.executable, self.project / "scripts/summarize-performance.py"])
            self.assert_boundary("after-workload", allow_database=True)
        except BaseException as failure:
            code = failure.code if isinstance(failure, StudyError) else 130 if isinstance(failure, KeyboardInterrupt) else 1
            self.failure = f"{type(failure).__name__}: {failure}"
            self.manifest["failure"] = self.failure
            try:
                self.stop_active()
            except BaseException as group_failure:
                self.manifest["ownedGroupCleanupError"] = str(group_failure)
            if self.armed:
                try:
                    containers = self.current_containers("failure-containers")
                    if "java-portfolio-clearledger-postgres-1" in containers:
                        self.dump_databases("failed-current")
                except BaseException as preservation_failure:
                    self.manifest["failureDatabasePreservationError"] = str(preservation_failure)
        finally:
            if self.armed:
                cleanup_handlers = {sig: signal.signal(sig, signal.SIG_IGN)
                                    for sig in [signal.SIGINT, signal.SIGTERM]}
                try:
                    self.cleanup_code = self.command("down", ["bash", self.project / "scripts/down.sh"],
                                                     timeout=120, check=False)
                    if self.cleanup_code:
                        code = code or self.cleanup_code
                    self.assert_boundary("after-down")
                except BaseException as cleanup_failure:
                    self.manifest["cleanupError"] = str(cleanup_failure)
                    code = code or 1
                try:
                    self.preserve_current_outputs()
                except BaseException as copy_failure:
                    self.manifest["currentArtifactPreservationError"] = str(copy_failure)
                    code = code or 1
                finally:
                    for sig, handler in cleanup_handlers.items():
                        signal.signal(sig, handler)
            self.manifest.update(state="PASS" if code == 0 else "FAIL", finishedAtUtc=utc(),
                                 exitCode=code, downExitCode=self.cleanup_code,
                                 currentFreeDiskBytes=shutil.disk_usage(self.root).free)
            self.save()
        return code


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="execute only within the coordinator's full quiet reservation")
    parser.add_argument("--reservation", default="clearledger-full-reference", help="coordinator reservation label recorded in evidence")
    parser.add_argument("--expected-source", default=EXPECTED_SOURCE)
    parser.add_argument("--expected-root-pom", default=EXPECTED_POM)
    parser.add_argument("--min-free-gib", type=int, default=6)
    parser.add_argument("--jmh-timeout-seconds", type=int, default=1800)
    parser.add_argument("--workload-timeout-seconds", type=int, default=21600)
    return parser.parse_args(argv)


def main(argv=None):
    args = arguments(argv)
    if not args.execute:
        print(json.dumps({"mode": "PLAN_ONLY_NO_COMMANDS_EXECUTED", "source": args.expected_source,
                          "rootPom": args.expected_root_pom, "requires": ["all3 coordinator-held build locks", "zero Java/apps/containers", "matching prepared artifacts", "at least6GiB free"],
                          "commands": ["microbenchmark.sh full --prepared", "summarize-jmh.py", "preserve prior named benchmark databases", "benchmark.sh 100000 3 --prepared", "summarize-performance.py", "always down"],
                          "failurePolicy": "retain primary status; stop child process group; preserve raw files and available failed database dumps; finally own down"}, indent=2))
        return 0
    if min(args.min_free_gib, args.jmh_timeout_seconds, args.workload_timeout_seconds) <= 0:
        raise SystemExit("Disk/timeout bounds must be positive")
    lock = ROOT / ".work/A01/full-reference.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open("a+") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit("Another A01 reference runner is active")
        study = ReferenceStudy(args)
        previous = {}
        def interrupted(signum, frame):
            raise StudyError("coordinator signal " + str(signum), 128 + signum)
        for signum in [signal.SIGINT, signal.SIGTERM]:
            previous[signum] = signal.signal(signum, interrupted)
        try:
            print("Reference archive: " + str(study.directory), flush=True)
            return study.run()
        finally:
            for signum, handler in previous.items():
                signal.signal(signum, handler)


if __name__ == "__main__":
    sys.exit(main())
