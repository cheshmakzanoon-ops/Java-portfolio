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
import stat
import subprocess
import sys
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXPECTED_SOURCE = "working-tree:47936597eef8a1018263920d1d7b00a9b051fb2ecf293543d7e0e805c27f2bf5"
EXPECTED_POM = "3743d1ba382977b3f8d8609e1191190f220cdf272a5338d538869f96a7b7230d"
GIB = 1024**3
RESOURCE_INITIAL_EXTRA = 4_280_127_815
RESOURCE_WORKLOAD_EXTRA = 1_279_554_183 + GIB + 1_050_036_534 + GIB // 2
RESOURCE_LAUNCH_MINIMUM = 21 * GIB // 4  # 5.25 GiB, future opt-in attempts only.
RESOURCE_DESTINATION_RESERVE = 2 * GIB
# Covers bounded directory/file allocation rounding and worst-case escaped-path JSON.
RESOURCE_COPY_METADATA_ALLOWANCE = 128 * 1024**2
ARTIFACT_MAX_FILES = 2048
ARTIFACT_MAX_ENTRIES = 4096
ARTIFACT_MAX_DEPTH = 16
ARTIFACT_MAX_BYTES = 8 * GIB
MANAGED_OUTPUTS = ("target/benchmark", "target/microbenchmark/full",
                   "evidence/performance-current.json", "evidence/jmh-current.json")


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
        self.resource_mode = args.artifact_copy_root is not None
        self.manifest = {"schemaVersion": 1, "project": "clearledger", "reservation": args.reservation,
                         "startedAtUtc": utc(), "state": "PREPARING", "steps": self.steps,
                         "limitations": ["Current-checkout reference measurements; clean-checkout release remains separate.",
                                         "Catchable failure/signals run cleanup; SIGKILL or host loss requires coordinator recovery."]}
        if self.resource_mode:
            self.manifest["resourcePolicy"] = {
                "mode": "explicit-new-attempt-secondary-copy",
                "workspaceFloorBytes": args.min_free_gib * GIB,
                "initialMinimumBytes": RESOURCE_LAUNCH_MINIMUM,
                "initialAdditionalAllocationBytes": RESOURCE_INITIAL_EXTRA,
                "preworkloadAdditionalAllocationBytes": RESOURCE_WORKLOAD_EXTRA,
                "destinationReserveBytes": RESOURCE_DESTINATION_RESERVE,
                "destinationMetadataAllowanceBytes": RESOURCE_COPY_METADATA_ALLOWANCE,
                "artifactCopyRoot": str(args.artifact_copy_root),
                "monitorWaitSeconds": 4, "maximumMonitorGapSeconds": 5,
                "artifactLimits": {"files": ARTIFACT_MAX_FILES, "entries": ARTIFACT_MAX_ENTRIES,
                                   "depth": ARTIFACT_MAX_DEPTH, "bytes": ARTIFACT_MAX_BYTES},
                "scope": "Resource policy only; workload, retries, durability, assertions and gates unchanged.",
                "limitations": ["Allocation allowances are forecasts, not proven growth caps.",
                                "The secondary /tmp copy is tmpfs, not durable external backup."]}
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
                        code = self.wait_for_active(name, timeout, step)
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

    def wait_for_active(self, name, timeout, step):
        if not self.resource_mode or name not in {"full-jmh", "full-workload"}:
            return self.active.wait(timeout=timeout)
        deadline = time.monotonic() + timeout
        previous = None
        while True:
            now = time.monotonic()
            if now >= deadline:
                raise subprocess.TimeoutExpired(name, timeout)
            previous = self.monitor_workspace_floor(step, previous)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(name, timeout)
            try:
                code = self.active.wait(timeout=min(4, remaining))
            except subprocess.TimeoutExpired:
                continue
            self.monitor_workspace_floor(step, previous)
            return code

    def monitor_workspace_floor(self, step, previous):
        began = time.monotonic()
        free = shutil.disk_usage(self.root).free
        observed = time.monotonic()
        duration = observed - began
        gap = 0 if previous is None else observed - previous
        monitor = step.setdefault("resourceMonitor", {"checks": 0, "minimumFreeBytes": free,
                                                      "maximumObservedGapSeconds": 0,
                                                      "maximumObservationDurationSeconds": 0})
        monitor.update(checks=monitor["checks"] + 1, lastCheckedUtc=utc(), lastFreeBytes=free,
                       minimumFreeBytes=min(monitor["minimumFreeBytes"], free),
                       maximumObservedGapSeconds=max(monitor["maximumObservedGapSeconds"], gap),
                       maximumObservationDurationSeconds=max(monitor["maximumObservationDurationSeconds"], duration))
        self.save()
        if gap > 5 or duration > 5:
            raise StudyError("Resource monitor exceeded five-second observation interval; phase refused")
        if free < self.args.min_free_gib * GIB:
            raise StudyError("Workspace floor breached during measurement; evidence retained")
        return observed

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

    def disk_guard(self, phase, additional_bytes=0, minimum_bytes=0):
        free = shutil.disk_usage(self.root).free
        required = max(minimum_bytes, self.args.min_free_gib * 1024**3 + max(0, additional_bytes))
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
        if self.resource_mode:
            self.secondary_root()
            self.disk_guard("initial-resource-admission", RESOURCE_INITIAL_EXTRA,
                            minimum_bytes=RESOURCE_LAUNCH_MINIMUM)
            self.destination_guard("initial-destination-admission", 2 * GIB)
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
        if self.resource_mode:
            shutil.copy2(__file__, self.directory / "reference-runner.py")
            if sha(self.directory / "reference-runner.py") != self.manifest["runnerSha256"]:
                raise StudyError("Runner changed while capturing resource-policy provenance")
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
        if self.resource_mode:
            self.preserve_secondary_outputs()
            return
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

    @staticmethod
    def reject_symlink_ancestors(path):
        for parent in (path, *path.parents):
            try:
                metadata = parent.lstat()
            except FileNotFoundError:
                continue
            if not stat.S_ISDIR(metadata.st_mode):
                raise StudyError("Non-directory/symlink artifact parent refused: " + str(parent))

    def secondary_root(self):
        path = pathlib.Path(self.args.artifact_copy_root)
        if (not path.is_absolute() or ".." in path.parts
                or not path.is_relative_to(pathlib.Path("/tmp")) or path == pathlib.Path("/tmp")):
            raise StudyError("Explicit secondary artifact root must be a dedicated absolute /tmp path")
        self.reject_symlink_ancestors(path)
        return path

    @staticmethod
    def open_directory(path, create=False):
        path = pathlib.Path(path)
        if not path.is_absolute() or ".." in path.parts:
            raise StudyError("Absolute no-parent-traversal directory required")
        root_fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            return ReferenceStudy.open_relative_directory(root_fd, path.parts[1:], create)
        finally:
            os.close(root_fd)

    @staticmethod
    def open_relative_directory(root_fd, parts, create=False):
        current = os.dup(root_fd)
        try:
            for part in parts:
                if part in {"", ".", ".."} or "/" in part:
                    raise StudyError("Invalid relative directory component")
                if create:
                    try:
                        os.mkdir(part, 0o700, dir_fd=current)
                    except FileExistsError:
                        pass
                following = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=current)
                os.close(current)
                current = following
            return current
        except BaseException:
            os.close(current)
            raise

    @staticmethod
    def same_directory(first, second):
        a, b = os.fstat(first), os.fstat(second)
        if (a.st_dev, a.st_ino) != (b.st_dev, b.st_ino):
            raise StudyError("Artifact directory namespace changed")

    def destination_guard(self, phase, remaining_bytes, root_fd=None):
        path = self.secondary_root()
        owned_fd = None
        if root_fd is None:
            probe = path
            while True:
                try:
                    owned_fd = self.open_directory(probe)
                    root_fd = owned_fd
                    break
                except FileNotFoundError:
                    probe = probe.parent
        try:
            capacity = os.fstatvfs(root_fd)
            free = capacity.f_bavail * capacity.f_frsize
        finally:
            if owned_fd is not None:
                os.close(owned_fd)
        required = remaining_bytes + RESOURCE_DESTINATION_RESERVE + RESOURCE_COPY_METADATA_ALLOWANCE
        observation = {"phase": phase, "observedAtUtc": utc(), "path": str(path),
                       "freeBytes": free, "remainingCopyBytes": remaining_bytes,
                       "reserveBytes": RESOURCE_DESTINATION_RESERVE,
                       "metadataAllowanceBytes": RESOURCE_COPY_METADATA_ALLOWANCE, "requiredBytes": required}
        self.manifest.setdefault("destinationDiskChecks", []).append(observation)
        self.save()
        if free < required:
            raise StudyError("Secondary destination capacity insufficient; originals retained")

    @staticmethod
    def file_identity(metadata):
        return (metadata.st_dev, metadata.st_ino, metadata.st_size,
                metadata.st_mtime_ns, metadata.st_ctime_ns, metadata.st_mode, metadata.st_nlink)

    def artifact_inventory(self, root_fd=None, all_entries=False):
        entries, files, size = 0, [], 0

        def visit(parent_fd, relative):
            nonlocal entries, size
            entries += 1
            if entries > ARTIFACT_MAX_ENTRIES or len(relative.parts) > ARTIFACT_MAX_DEPTH:
                raise StudyError("Artifact entry/depth budget exceeded; no evidence omitted")
            metadata = os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
            if stat.S_ISDIR(metadata.st_mode):
                child_fd = os.open(relative.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
                try:
                    actual = os.fstat(child_fd)
                    if (actual.st_dev, actual.st_ino) != (metadata.st_dev, metadata.st_ino):
                        raise StudyError("Artifact directory changed during enumeration")
                    # scandir(fd) streams entries; Path.iterdir uses a materialized list
                    # on the supported Python runtime and cannot enforce a hard entry cap.
                    with os.scandir(child_fd) as iterator:
                        for child in iterator:
                            visit(child_fd, relative / child.name)
                finally:
                    os.close(child_fd)
            elif stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1:
                size += metadata.st_size
                files.append((relative, self.file_identity(metadata)))
                if len(files) > ARTIFACT_MAX_FILES or size > ARTIFACT_MAX_BYTES:
                    raise StudyError("Artifact file/byte budget exceeded; no evidence omitted")
            else:
                raise StudyError("Symlink/special/hardlinked artifact refused: " + str(relative))

        owned_fd = self.open_directory(self.project) if root_fd is None else None
        if root_fd is None:
            root_fd = owned_fd
        try:
            if all_entries:
                with os.scandir(root_fd) as iterator:
                    for child in iterator:
                        visit(root_fd, pathlib.Path(child.name))
            else:
                for name in MANAGED_OUTPUTS:
                    relative = pathlib.Path(name)
                    try:
                        parent_fd = self.open_relative_directory(root_fd, relative.parts[:-1])
                    except FileNotFoundError:
                        continue
                    try:
                        try:
                            os.stat(relative.name, dir_fd=parent_fd, follow_symlinks=False)
                        except FileNotFoundError:
                            continue
                        visit(parent_fd, relative)
                    finally:
                        os.close(parent_fd)
        finally:
            if owned_fd is not None:
                os.close(owned_fd)
        return sorted(files, key=lambda item: item[0]), size

    def copy_stable_artifact(self, source_root, destination_root, relative, expected_identity):
        source_parent = self.open_relative_directory(source_root, relative.parts[:-1])
        try:
            target_parent = self.open_relative_directory(destination_root, relative.parts[:-1], create=True)
            try:
                return self.copy_stable_file(source_root, destination_root, source_parent,
                                             target_parent, relative, expected_identity)
            finally:
                os.close(target_parent)
        finally:
            os.close(source_parent)

    def copy_stable_file(self, source_root, destination_root, source_parent, target_parent,
                         relative, expected_identity):
        fd = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=source_parent)
        digest = hashlib.sha256()
        copied = 0
        with os.fdopen(fd, "rb") as incoming:
            metadata = os.fstat(incoming.fileno())
            if (not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1
                    or self.file_identity(metadata) != expected_identity):
                raise StudyError("Artifact identity changed before copy: " + str(relative))
            output_fd = os.open(relative.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                0o600, dir_fd=target_parent)
            with os.fdopen(output_fd, "wb") as outgoing:
                while True:
                    block = incoming.read(min(1024**2, expected_identity[2] - copied + 1))
                    if not block:
                        break
                    copied += len(block)
                    if copied > expected_identity[2]:
                        raise StudyError("Artifact grew during copy: " + str(relative))
                    digest.update(block)
                    outgoing.write(block)
            if self.file_identity(os.fstat(incoming.fileno())) != expected_identity:
                raise StudyError("Artifact changed during copy: " + str(relative))
        verify = hashlib.sha256()
        verified_bytes = 0
        verify_fd = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=target_parent)
        with os.fdopen(verify_fd, "rb") as stream:
            metadata = os.fstat(stream.fileno())
            target_identity = self.file_identity(metadata)
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1 or metadata.st_size != copied:
                raise StudyError("Secondary artifact type/size changed before verification")
            for block in iter(lambda: stream.read(1024**2), b""):
                verified_bytes += len(block)
                if verified_bytes > copied:
                    raise StudyError("Secondary artifact grew during verification")
                verify.update(block)
            if self.file_identity(os.fstat(stream.fileno())) != target_identity:
                raise StudyError("Secondary artifact changed during verification")
        if self.file_identity(os.stat(relative.name, dir_fd=target_parent, follow_symlinks=False)) != target_identity:
            raise StudyError("Secondary artifact path changed during verification")
        if (copied != expected_identity[2] or verified_bytes != copied
                or verify.hexdigest() != digest.hexdigest()):
            raise StudyError("Secondary artifact size/hash verification failed: " + str(relative))
        if self.file_identity(os.stat(relative.name, dir_fd=source_parent, follow_symlinks=False)) != expected_identity:
            raise StudyError("Artifact path changed during verification: " + str(relative))
        for root_fd, pinned in ((source_root, source_parent), (destination_root, target_parent)):
            reopened = self.open_relative_directory(root_fd, relative.parts[:-1])
            try:
                self.same_directory(reopened, pinned)
            finally:
                os.close(reopened)
        return digest.hexdigest()

    def verify_complete_destination(self, root_fd, proof):
        entries, size = self.artifact_inventory(root_fd, all_entries=True)
        expected = {pathlib.Path(row["path"]): row for row in proof["files"]}
        if set(dict(entries)) != set(expected) or size != proof["expectedBytes"]:
            raise StudyError("Secondary destination file set/size differs")
        for relative, expected_identity in entries:
            parent = self.open_relative_directory(root_fd, relative.parts[:-1])
            try:
                fd = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
                digest, count = hashlib.sha256(), 0
                with os.fdopen(fd, "rb") as stream:
                    metadata = os.fstat(stream.fileno())
                    if (not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1
                            or self.file_identity(metadata) != expected_identity):
                        raise StudyError("Secondary destination changed before final verification")
                    while True:
                        block = stream.read(min(1024**2, expected_identity[2] - count + 1))
                        if not block:
                            break
                        count += len(block)
                        if count > expected_identity[2]:
                            raise StudyError("Secondary destination grew during final verification")
                        digest.update(block)
                    if self.file_identity(os.fstat(stream.fileno())) != expected_identity:
                        raise StudyError("Secondary destination changed during final verification")
                if count != expected[relative]["bytes"] or digest.hexdigest() != expected[relative]["sha256"]:
                    raise StudyError("Secondary destination final hash mismatch")
            finally:
                os.close(parent)
        after, after_size = self.artifact_inventory(root_fd, all_entries=True)
        if dict(after) != dict(entries) or after_size != size:
            raise StudyError("Secondary destination identities changed during final audit")

    def preserve_secondary_outputs(self):
        root = self.secondary_root()
        destination = root / self.directory.name
        source_fd = self.open_directory(self.project)
        try:
            parent_fd = self.open_directory(root, create=True)
            try:
                os.mkdir(self.directory.name, 0o700, dir_fd=parent_fd)
                destination_fd = os.open(self.directory.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                         dir_fd=parent_fd)
            finally:
                os.close(parent_fd)
            try:
                self.preserve_pinned_outputs(source_fd, destination_fd, destination)
            finally:
                os.close(destination_fd)
        finally:
            os.close(source_fd)

    def preserve_pinned_outputs(self, source_fd, destination_fd, destination):
        files, copy_bytes = self.artifact_inventory(source_fd)
        proof = {"state": "COPYING", "destination": str(destination),
                 "sourceWorkspace": str(self.root), "expectedFiles": len(files), "expectedBytes": copy_bytes,
                 "workspaceFreeBytesObserved": shutil.disk_usage(self.root).free,
                 "scope": "Secondary verified copy; workspace originals retained, /tmp is ephemeral tmpfs",
                 "destinationDirectoryIdentity": list(self.file_identity(os.fstat(destination_fd))),
                 "files": []}
        self.manifest["artifactPreservation"] = proof
        self.save()
        try:
            self.destination_guard("raw-output-copy", copy_bytes, destination_fd)
            remaining = copy_bytes
            for relative, identity in files:
                self.destination_guard("copy-file-" + str(len(proof["files"])), remaining, destination_fd)
                digest = self.copy_stable_artifact(source_fd, destination_fd, relative, identity)
                proof["files"].append({"path": str(relative),
                                       "bytes": identity[2], "sha256": digest})
                remaining -= identity[2]
            after, after_bytes = self.artifact_inventory(source_fd)
            if dict(after) != dict(files) or after_bytes != copy_bytes:
                raise StudyError("Artifact set/identity changed during preservation")
            self.verify_complete_destination(destination_fd, proof)
            self.destination_guard("copy-final", 0, destination_fd)
            for path, pinned in ((self.project, source_fd), (destination, destination_fd)):
                reopened = self.open_directory(path)
                try:
                    self.same_directory(reopened, pinned)
                finally:
                    os.close(reopened)
            proof.update(state="PASS", copiedFiles=len(proof["files"]), copiedBytes=copy_bytes,
                         finishedAtUtc=utc())
        except BaseException as error:
            proof.update(state="FAIL", failure=f"{type(error).__name__}: {error}", finishedAtUtc=utc())
            raise
        finally:
            # This exclusive anchored write cannot follow a swapped destination ancestor.
            fd = os.open("copy-manifest.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                         0o600, dir_fd=destination_fd)
            with os.fdopen(fd, "w") as output:
                json.dump(proof, output, indent=2)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            self.save()

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
            if self.resource_mode:
                self.disk_guard("nine-100000-input-runs", RESOURCE_WORKLOAD_EXTRA)
            else:
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
    parser.add_argument("--artifact-copy-root", type=pathlib.Path,
                        help="Opt in to the reviewed NEW-attempt resource policy and dedicated /tmp secondary copy root")
    parser.add_argument("--jmh-timeout-seconds", type=int, default=1800)
    parser.add_argument("--workload-timeout-seconds", type=int, default=21600)
    return parser.parse_args(argv)


def main(argv=None):
    args = arguments(argv)
    if not args.execute:
        plan = {"mode": "PLAN_ONLY_NO_COMMANDS_EXECUTED", "source": args.expected_source,
                          "rootPom": args.expected_root_pom, "requires": ["all3 coordinator-held build locks", "zero Java/apps/containers", "matching prepared artifacts", "at least6GiB free"],
                          "commands": ["microbenchmark.sh full --prepared", "summarize-jmh.py", "preserve prior named benchmark databases", "benchmark.sh 100000 3 --prepared", "summarize-performance.py", "always down"],
                          "failurePolicy": "retain primary status; stop child process group; preserve raw files and available failed database dumps; finally own down"}
        if args.artifact_copy_root is not None:
            initial = max(RESOURCE_LAUNCH_MINIMUM, args.min_free_gib * GIB + RESOURCE_INITIAL_EXTRA)
            plan["requires"][-1] = f"at least {initial} workspace bytes plus independent destination capacity"
            plan["resourcePolicy"] = {"mode": "explicit-new-attempt-secondary-copy",
                                      "workspaceFloorBytes": args.min_free_gib * GIB,
                                      "initialRequiredBytes": initial,
                                      "preworkloadRequiredBytes": args.min_free_gib * GIB + RESOURCE_WORKLOAD_EXTRA,
                                      "artifactCopyRoot": str(args.artifact_copy_root),
                                      "destinationReserveBytes": RESOURCE_DESTINATION_RESERVE,
                                      "destinationMetadataAllowanceBytes": RESOURCE_COPY_METADATA_ALLOWANCE}
        print(json.dumps(plan, indent=2))
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
