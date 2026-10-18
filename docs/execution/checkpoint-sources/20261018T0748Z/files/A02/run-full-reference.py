#!/usr/bin/env python3
"""Coordinator full reference runner: three real 10000x8 runs; never prepares/builds.

Invoke only after root grants a quiet host and holds all three build locks:
  python3 .work/A02/run-full-reference.py --quiet-reservation root-quiet-N
Exit 0: three full runs and stable declared reference; 2: full runs passed but noisy
reference; 1: failed/incomplete run; 130/143: interrupted, with cleanup attempted.
All outputs stay ignored under .work/A02 and the existing target/performance tree.
"""
import argparse
import base64
import collections
import datetime
import hashlib
import json
import math
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import time
import traceback

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROJECT = ROOT / "projects/02-durableflow"
OWNER = ROOT / ".work/A02"
EXPECTED_SOURCE = "sha256:104e648ae642e84a02ce3031ac7439e4a71f20767285404b87a96b4019254187"
EXPECTED_ROOT_POM = "3743d1ba382977b3f8d8609e1191190f220cdf272a5338d538869f96a7b7230d"
EXPECTED_IMAGE = "postgres:18.3-alpine@sha256:54451ecb8ab38c24c3ec123f2fd501303a3a1856a5c66e98cecf2460d5e1e9d7"
PORTFOLIO = ROOT / "scripts/portfolio.sh"
PREPARED = PROJECT / "scripts/prepared-build.py"
BENCHMARK = PROJECT / "scripts/benchmark.sh"
SUMMARY = PROJECT / "scripts/summarize-performance.py"
PERFORMANCE = PROJECT / "target/performance"
RUNTIME = PROJECT / "target/runtime"
ACTIVE = None
INTERRUPTED = None
START_RESERVE_BYTES = 2 * 1024 ** 3
PRESERVATION_RESERVE_BYTES = 512 * 1024 ** 2
MONITORED_FLOOR_BYTES = 512 * 1024 ** 2
TMP_FLOOR_BYTES = 256 * 1024 ** 2


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def relative(path):
    return str(path.resolve().relative_to(ROOT))


def artifact(path):
    return dict(path=relative(path), bytes=path.stat().st_size, sha256=sha256(path))


def process_table():
    table = {}
    for entry in pathlib.Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            text = (entry / "stat").read_text()
            fields = text[text.rfind(")") + 2:].split()
            table[int(entry.name)] = dict(state=fields[0], parent=int(fields[1]), start=int(fields[19]))
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
    return table


def extend_tree(known, table):
    changed = True
    while changed:
        changed = False
        for pid, info in table.items():
            parent = info["parent"]
            if pid not in known and parent in known and table.get(parent, {}).get("start") == known[parent]:
                known[pid] = info["start"]
                changed = True


def signal_owned(pid, started, number):
    current = process_table().get(pid)
    if current and current["start"] == started and current["state"] not in ("Z", "X"):
        try:
            os.kill(pid, number)
        except ProcessLookupError:
            pass


def stop_group(process):
    # GNU timeout creates a nested process group. Record descendants before TERM,
    # so its real workload JVM cannot escape just because the wrapper exits first.
    table = process_table()
    if process.pid not in table:
        process.poll()
        return
    known = {process.pid: table[process.pid]["start"]}
    extend_tree(known, table)
    for pid in reversed(list(known)):
        signal_owned(pid, known[pid], signal.SIGTERM)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        process.poll()
        table = process_table()
        extend_tree(known, table)
        live = [pid for pid, stamp in known.items()
                if table.get(pid, {}).get("start") == stamp
                and table[pid]["state"] not in ("Z", "X")]
        if not live:
            return
        time.sleep(0.1)
    # Recheck each start time: never kill a different process after PID reuse.
    table = process_table()
    extend_tree(known, table)
    for pid in reversed(list(known)):
        signal_owned(pid, known[pid], signal.SIGKILL)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        pass


def remember_signal(number, _frame):
    global INTERRUPTED
    INTERRUPTED = number


def on_signal(number, _frame):
    global INTERRUPTED
    INTERRUPTED = number
    raise InterruptedError(f"root interrupted reference runner with signal {number}")


class CommandFailure(RuntimeError):
    pass


class Runner:
    def __init__(self, directory, reservation):
        self.directory = directory
        self.reservation = reservation
        self.environment = dict(os.environ)
        # Bind this destructive reset workflow to the disposable local Compose DB.
        # No credentials/environment dump is written to evidence.
        self.environment.update(
            DURABLEFLOW_PREPARED="1", TENANT="demo", PORT="18200",
            PROVIDER_PORT="18201", DURABLEFLOW_DB_PORT="18202",
            DB_URL="jdbc:postgresql://127.0.0.1:18202/durableflow",
            DB_USER="durableflow", DB_PASSWORD="disposable-local-only",
            PROVIDER_URL="http://127.0.0.1:18201/effects",
            EFFECT_LOG=str(RUNTIME / "provider-effects.log"),
            JAVA_HOME="/workspace/.tools/jdk-25")
        self.environment["PATH"] = "/workspace/.tools/jdk-25/bin:" + self.environment.get("PATH", "")
        self.steps = []
        self.directories = []
        self.outcomes = []
        self.reference_identity = None
        self.started_times = set()

    def disk(self, phase, required=MONITORED_FLOOR_BYTES):
        workspace = shutil.disk_usage(ROOT).free
        temporary = shutil.disk_usage("/tmp").free
        sample = dict(timestamp=utc(), phase=phase, workspaceFreeBytes=workspace,
                      tmpFreeBytes=temporary, workspaceRequiredBytes=required,
                      tmpRequiredBytes=TMP_FLOOR_BYTES)
        with (self.directory / "disk-samples.jsonl").open("a") as journal:
            journal.write(json.dumps(sample) + "\n")
        if workspace < required or temporary < TMP_FLOOR_BYTES:
            raise OSError(f"disk reserve refused {phase}: workspace {workspace} < {required} "
                          f"or tmp {temporary} < {TMP_FLOOR_BYTES}; no files deleted")
        return sample

    def command(self, label, arguments, directory, timeout, required=True, monitor=True):
        global ACTIVE
        start = utc()
        monotonic = time.monotonic()
        output = directory / (label + ".log")
        code = None
        failure = None
        print(f"{start} {label}", flush=True)
        with output.open("wb") as stream:
            try:
                ACTIVE = subprocess.Popen(
                    [str(value) for value in arguments], cwd=ROOT,
                    env=self.environment, stdout=stream, stderr=subprocess.STDOUT,
                    start_new_session=True)
                deadline = time.monotonic() + timeout
                next_disk = time.monotonic()
                while ACTIVE.poll() is None:
                    if time.monotonic() >= deadline:
                        raise subprocess.TimeoutExpired(arguments, timeout)
                    if monitor and time.monotonic() >= next_disk:
                        self.disk(label + "-running")
                        next_disk = time.monotonic() + 5
                    try:
                        ACTIVE.wait(timeout=min(1, max(0.001, deadline - time.monotonic())))
                    except subprocess.TimeoutExpired:
                        pass
                code = ACTIVE.returncode
            except BaseException as error:
                failure = f"{type(error).__name__}: {error}"
                if ACTIVE is not None:
                    stop_group(ACTIVE)
                    code = ACTIVE.returncode
                raise
            finally:
                entry = dict(label=label, command=[str(value) for value in arguments],
                             startedAt=start, finishedAt=utc(),
                             elapsedSeconds=time.monotonic() - monotonic,
                             exitCode=code, failure=failure, stdout=relative(output))
                self.steps.append(entry)
                with (self.directory / "commands.jsonl").open("a") as journal:
                    journal.write(json.dumps(entry) + "\n")
                ACTIVE = None
        if required and code != 0:
            raise CommandFailure(f"{label} exited {code}; see {output}")
        return code, output

    def prepared(self, directory, label):
        self.command(label, [sys.executable, PREPARED, "verify"], directory, 60)
        receipt = json.loads((PROJECT / "target/prepared-build.json").read_text())
        if (receipt["sourceFingerprint"] != EXPECTED_SOURCE
                or receipt["rootPomSha256"] != EXPECTED_ROOT_POM):
            raise ValueError("prepared source/root differs from the frozen reviewed reference")

    def preflight(self, directory, label):
        self.prepared(directory, label + "-prepared")
        self.command(label + "-idle", [sys.executable, PREPARED, "idle"], directory, 30)
        _, output = self.command(label + "-docker", ["docker", "ps", "--format", "{{json .}}"], directory, 30)
        rows = [json.loads(line) for line in output.read_text().splitlines() if line.strip()]
        competing = [row for row in rows if not row["Image"].startswith("testcontainers/ryuk:")]
        if competing:
            raise ValueError("quiet preflight found a running application container; root must stop it")
        java = []
        for process in pathlib.Path("/proc").iterdir():
            if not process.name.isdigit():
                continue
            try:
                argv = (process / "cmdline").read_bytes().split(b"\0")
                if argv and pathlib.Path(os.fsdecode(argv[0])).name in ("java", "javac"):
                    java.append(int(process.name))
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                continue
        write_json(directory / (label + "-quiet-observation.json"), dict(
            timestamp=utc(), quietReservation=self.reservation,
            coordinatorDeclaresAllThreeBuildLocks=True,
            lockEvidence="caller declaration, not independently observed; runner never acquires or bypasses build locks",
            liveJavaPids=java, dockerRows=rows,
            loadAverage=pathlib.Path("/proc/loadavg").read_text().strip()))
        if java:
            raise ValueError("quiet preflight found a live Java/javac process; root must stop competing work")

    def cleanup(self, directory):
        # An interrupt has already been recorded; allow bounded cleanup to finish.
        # A second signal can still be handled externally if the host is unhealthy.
        global ACTIVE
        if ACTIVE is not None:
            stop_group(ACTIVE)
            ACTIVE = None
        old_int = signal.signal(signal.SIGINT, remember_signal)
        old_term = signal.signal(signal.SIGTERM, remember_signal)
        errors = []
        try:
            try:
                code, _ = self.command("always-down", [PORTFOLIO, "down", "durableflow"], directory, 180, required=False, monitor=False)
                if code:
                    errors.append(f"down exit {code}")
            except BaseException as error:
                errors.append(f"down: {type(error).__name__}: {error}")
            try:
                code, _ = self.command("idle-after-down", [sys.executable, PREPARED, "idle"], directory, 30, required=False, monitor=False)
                if code:
                    errors.append(f"owned runtime remains, idle exit {code}")
            except BaseException as error:
                errors.append(f"idle: {type(error).__name__}: {error}")
            try:
                _, output = self.command("containers-after-down", ["docker", "ps", "--format", "{{json .}}"], directory, 30, monitor=False)
                for line in output.read_text().splitlines():
                    row = json.loads(line)
                    if row.get("Names", "").startswith("java-portfolio-durableflow-"):
                        errors.append("owned application container still running")
            except BaseException as error:
                errors.append(f"container observation: {type(error).__name__}: {error}")
        finally:
            signal.signal(signal.SIGINT, old_int)
            signal.signal(signal.SIGTERM, old_term)
        write_json(directory / "cleanup.json", dict(timestamp=utc(), errors=errors, exitCode=1 if errors else 0))
        return errors

    def clean_database(self, directory):
        _, output = self.command("clean-database", ["docker", "compose", "-f", PROJECT / "infra/compose.yaml",
            "exec", "-T", "postgres", "psql", "-U", "durableflow", "-d", "durableflow", "-tAc",
            "SELECT count(*) FROM workflow_instance"], directory, 30)
        if output.read_text().strip() != "0":
            raise ValueError("reset did not produce exactly zero workflow instances")
        log = RUNTIME / "provider-effects.log"
        if log.exists() and log.stat().st_size:
            raise ValueError("reset left provider receipts/effects from an earlier run")

    def capture(self, directory, before, started):
        errors = []
        candidates = sorted(set(PERFORMANCE.glob("*-10000-*")) - before)
        write_json(directory / "capture-discovery.json", dict(
            benchmarkStartedAt=started, newDirectories=[relative(path) for path in candidates]))
        raw = None
        if len(candidates) != 1:
            errors.append(f"expected one newly created full performance capture, found {len(candidates)}")
        else:
            raw = candidates[0]
        # Copy raw provider evidence and live-service logs only after down quiesces them.
        # All originals and large recordings stay in ignored target/.work, never Git evidence.
        provider = RUNTIME / "provider-effects.log"
        sources = ([provider] if provider.exists() else []) + [
            file for file in RUNTIME.glob("*") if file.is_file() and file != provider]
        if raw is not None:
            sources += [raw / name for name in ("workload-10000.json", "environment.json", "resource-samples.jsonl")
                        if (raw / name).is_file()]
        copy_bytes = sum(file.stat().st_size for file in sources)
        try:
            self.disk("before-preservation", copy_bytes + PRESERVATION_RESERVE_BYTES)
        except OSError as error:
            write_json(directory / "preservation-refusal.json", dict(
                failure=str(error), requiredCopyBytes=copy_bytes,
                originalFilesRetained=[dict(path=relative(file), bytes=file.stat().st_size) for file in sources],
                note="No originals deleted; no next reset/run will be attempted"))
            raise
        if provider.exists():
            shutil.copy2(provider, directory / "provider-effects.log")
        else:
            errors.append("independent provider log missing")
        runtime_copy = directory / "runtime"
        runtime_copy.mkdir(exist_ok=True)
        for file in RUNTIME.glob("*"):
            if file.is_file() and file.name != "provider-effects.log":
                shutil.copy2(file, runtime_copy / file.name)
        for name in ("workload-10000.json", "environment.json", "resource-samples.jsonl"):
            file = raw / name if raw is not None else None
            if file is not None and file.exists():
                shutil.copy2(file, directory / name)
        artifacts = []
        if raw is not None:
            for file in sorted(raw.rglob("*")):
                if file.is_file():
                    artifacts.append(artifact(file))
        for file in sorted(directory.rglob("*")):
            if file.is_file() and file.name not in ("raw-artifacts.json", "outcome.json"):
                artifacts.append(artifact(file))
        write_json(directory / "raw-artifacts.json", dict(
            timestamp=utc(), rawPerformanceDirectory=relative(raw) if raw else None,
            providerCopyQuiesced=not json.loads((directory / "cleanup.json").read_text())["errors"],
            files=artifacts, errors=errors))
        return raw, errors

    def validate(self, directory, raw):
        if raw is None:
            raise ValueError("no complete full capture")
        if (raw / "exit-code.txt").read_text().strip() != "0":
            raise ValueError("benchmark capture has nonzero exit status")
        result = json.loads((raw / "workload-10000.json").read_text())
        environment = json.loads((raw / "environment.json").read_text())
        if (result.get("exitCode") != 0 or result.get("driverExitCode") != 0
                or not result.get("profilingCaptured")
                or result.get("sourceRevision") != EXPECTED_SOURCE):
            raise ValueError("full driver/profiling/source result is incomplete")
        if (environment["sourceFingerprint"] != EXPECTED_SOURCE
                or environment["rootPomSha256"] != EXPECTED_ROOT_POM
                or environment["containerImage"] != EXPECTED_IMAGE):
            raise ValueError("full run source/root/image differs from the frozen reference")
        required = dict(executions=10000, activities=80000, timerWaits=5000,
                        completedActivities=80000, independentProviderEffects=80000,
                        transientRejectedAttempts=16000, acceptedUniqueSignals=10000,
                        firedBusinessTimers=5000, queuedAtEnd=0)
        for key, expected in required.items():
            if int(result["measurements"][key]) != expected:
                raise ValueError(f"full workload dimension {key} differs from {expected}")
        if result["assertions"]["completedExecutions"] != 10000 or result["assertions"]["failed"] != 0:
            raise ValueError("full driver assertion envelope differs")
        if not (raw / "worker-1.jfr").stat().st_size or not (raw / "allocation-samples.json").stat().st_size:
            raise ValueError("raw JFR/allocation recording missing")
        profile = validate_profile_evidence(raw, result["measurements"])
        identity = tuple(json.dumps(environment[name], sort_keys=True)
                         for name in ("sourceFingerprint", "rootPomSha256", "cpu", "cgroupLimits", "jdk", "jvmFlags"))
        if self.reference_identity is not None and identity != self.reference_identity:
            raise ValueError("run source/reference environment differs from the first accepted round")
        if result["startedAt"] in self.started_times:
            raise ValueError("distinct full-run timestamp required before accepting another round")
        provider = audit_provider(directory / "provider-effects.log")
        write_json(directory / "independent-provider-audit.json", provider)
        write_json(directory / "per-run-profile-validation.json", profile)
        self.reference_identity = identity
        self.started_times.add(result["startedAt"])
        return dict(rawPerformanceDirectory=relative(raw), sourceRevision=EXPECTED_SOURCE,
                    startedAt=result["startedAt"], independentProviderAudit=provider,
                    profileEvidence=profile, measurements=result["measurements"])

    def round(self, number):
        directory = self.directory / f"run-{number}"
        directory.mkdir()
        failure = None
        cleanup_errors = []
        capture_errors = []
        raw = None
        started = None
        touched = False
        before = set(PERFORMANCE.glob("*-10000-*"))
        observation = None
        try:
            self.disk("before-reset", START_RESERVE_BYTES)
            self.preflight(directory, "preflight")
            touched = True
            self.command("reset-demo", [PORTFOLIO, "reset-demo", "durableflow"], directory, 180)
            self.disk("before-start", START_RESERVE_BYTES)
            self.command("prepared-up", [PORTFOLIO, "up", "durableflow"], directory, 300)
            self.clean_database(directory)
            self.disk("before-full-workload", START_RESERVE_BYTES)
            started = utc()
            self.command("full-10000x8", [BENCHMARK], directory, 2280)
        except BaseException as error:
            failure = f"{type(error).__name__}: {error}"
            (directory / "exception.txt").write_text(traceback.format_exc())
        finally:
            if touched:
                cleanup_errors = self.cleanup(directory)
                try:
                    raw, capture_errors = self.capture(directory, before, started)
                except BaseException as error:
                    capture_errors.append(f"capture: {type(error).__name__}: {error}")
                    (directory / "capture-exception.txt").write_text(traceback.format_exc())
            else:
                write_json(directory / "cleanup.json", dict(exitCode=0, errors=[],
                    note="preflight refused before touching any owned stack; no startup/reset attempted"))
        if INTERRUPTED:
            failure = failure or f"interrupted by signal {INTERRUPTED}; no next round will start"
        if not failure and not cleanup_errors and not capture_errors:
            try:
                self.prepared(directory, "post-run-prepared")
                observation = self.validate(directory, raw)
            except BaseException as error:
                failure = f"validation: {type(error).__name__}: {error}"
                (directory / "validation-exception.txt").write_text(traceback.format_exc())
        outcome = dict(run=number, timestamp=utc(), failure=failure,
                       cleanupErrors=cleanup_errors, captureErrors=capture_errors,
                       observation=observation, exitCode=1 if failure or cleanup_errors or capture_errors else 0)
        write_json(directory / "outcome.json", outcome)
        self.outcomes.append(outcome)
        if outcome["exitCode"]:
            raise CommandFailure(f"full round {number} failed/incomplete; retained {directory}")
        self.directories.append(raw)


def validate_profile_evidence(raw, measurements):
    """Apply per-run summary prerequisites before accepting/resetting the next round."""
    elapsed = float(measurements["elapsedSeconds"])
    if not math.isfinite(elapsed) or elapsed <= 0:
        raise ValueError("invalid elapsed duration")
    vectors = {}
    for name in ("completionLatencyMs", "scheduleToStartMs", "timerLatenessMs"):
        values = [float(value) for value in measurements[name].strip("{}").split(",")]
        if len(values) != 3 or not all(math.isfinite(value) for value in values):
            raise ValueError("invalid finite percentile vector: " + name)
        vectors[name] = values
    required_roles = {"api", "provider", "scheduler-1", "scheduler-2", "driver"} | {
        f"worker-{number}" for number in range(1, 9)}
    frames = 0
    working_frames = 0
    with (raw / "resource-samples.jsonl").open() as samples:
        for line in samples:
            frame = json.loads(line)
            frames += 1
            if not isinstance(frame["clockTicksPerSecond"], (int, float)) or frame["clockTicksPerSecond"] <= 0:
                raise ValueError("invalid CPU tick frequency in resource evidence")
            roles = {process["role"] for process in frame["processes"]}
            if "driver" in roles:
                working_frames += 1
                if not required_roles.issubset(roles):
                    raise ValueError("incomplete live eight-worker/two-scheduler/driver frame")
    if not frames or not working_frames:
        raise ValueError("resource evidence lacks live driver frames")
    events = json.loads((raw / "allocation-samples.json").read_text())["recording"]["events"]
    if not isinstance(events, list):
        raise ValueError("allocation recording events must be an array")
    allocation = []
    for event in events:
        if event["type"] == "jdk.ObjectAllocationSample":
            weight = event["values"]["weight"]
            if type(weight) not in (int, float) or not math.isfinite(weight) or weight < 0:
                raise ValueError("invalid allocation sample weight")
            allocation.append(weight)
    gc_files = {}
    for role in sorted(required_roles):
        name = "WorkloadMain" if role == "driver" else role
        files = [file for file in (raw / "gc").glob(name + "-gc.log*")
                 if file.is_file() and file.stat().st_size > 0]
        if not files:
            raise ValueError("missing per-process GC evidence for " + role)
        gc_files[role] = [file.name for file in files]
    return dict(resourceFrames=frames, completeWorkingFrames=working_frames,
                requiredRoles=sorted(required_roles), finitePercentileVectors=vectors,
                worker1AllocationSamples=len(allocation), worker1EstimatedAllocatedBytes=sum(allocation),
                perRoleGcFiles=gc_files)


def audit_provider(path):
    """Independently verify raw provider evidence, not successful HTTP response counts."""
    keys = {}
    effects = set()
    instances = {}
    ordinal_ids = {}
    runs = set()
    total_calls = 0
    transient_calls = 0
    with path.open() as stream:
        while True:
            line = stream.readline(16385)
            if not line:
                break
            if len(line) > 16384 or not line.endswith("\n"):
                raise ValueError("truncated or oversized independent provider record")
            parts = line.rstrip("\n").split("\t", 3)
            if len(parts) != 4 or parts[0] not in ("CALL", "EFFECT"):
                raise ValueError("malformed provider record")
            identity = re.fullmatch(r"([0-9a-f-]{36}):a([0-7])", parts[1])
            if not identity:
                raise ValueError("unexpected non-workload provider key after fresh reset")
            instance, task = identity.group(1), int(identity.group(2))
            body = base64.b64decode(parts[2], validate=True).decode()
            operation, payload = body.split(":", 1)
            if operation != "reserve":
                raise ValueError("unexpected full-workload provider activity")
            data = json.loads(payload)["execution"]
            context = data["context"]
            ordinal = context["ordinal"]["data"]
            run = context["run"]["data"]
            if (context["ordinal"]["type"] != "INTEGER" or type(ordinal) is not int
                    or not 0 <= ordinal < 10000 or context["run"]["type"] != "TEXT"
                    or not isinstance(run, str) or not re.fullmatch(r"w-[0-9a-f-]{36}", run)
                    or data["payload"] != {"type": "TEXT", "data": "seed-42"}):
                raise ValueError("independent recorded workload inputs differ")
            runs.add(run)
            previous = ordinal_ids.setdefault(ordinal, instance)
            if previous != instance:
                raise ValueError("one workload ordinal produced different execution IDs")
            info = instances.setdefault(instance, {"ordinal": ordinal, "tasks": set()})
            if info["ordinal"] != ordinal:
                raise ValueError("an execution changed its ordinal")
            info["tasks"].add(task)
            digest = hashlib.sha256(parts[2].encode()).hexdigest()
            key = keys.setdefault(parts[1], {"parameterSha256": digest, "calls": 0,
                                           "transientCalls": 0, "expectedTransient": (ordinal * 8 + task) % 5 == 0})
            if key["parameterSha256"] != digest:
                raise ValueError("retry/effect parameters changed for a logical key")
            if parts[0] == "CALL":
                if parts[3] not in ("normal", "transient"):
                    raise ValueError("unexpected non-workload provider failure mode")
                key["calls"] += 1
                total_calls += 1
                if parts[3] == "transient":
                    key["transientCalls"] += 1
                    transient_calls += 1
            else:
                if not key["calls"] or parts[1] in effects:
                    raise ValueError("effect without dispatch or duplicate independent effect")
                if not re.fullmatch(r"effect-[0-9a-f-]{36}", parts[3]):
                    raise ValueError("malformed independent effect receipt")
                effects.add(parts[1])
    if (len(runs) != 1 or len(keys) != 80000 or len(effects) != 80000
            or len(instances) != 10000 or set(ordinal_ids) != set(range(10000))
            or transient_calls != 16000 or total_calls < 96000):
        raise ValueError("independent full10000x8 provider dimensions differ")
    for info in instances.values():
        if info["tasks"] != set(range(8)):
            raise ValueError("execution did not record all eight logical activities")
    for key in keys.values():
        expected = int(key["expectedTransient"])
        if key["transientCalls"] != expected or key["calls"] < 1 + expected:
            raise ValueError("transient first-attempt selection differs from seeded workload")
    return dict(runId=next(iter(runs)), instances=10000, logicalKeys=80000,
                independentUniqueEffects=80000, providerCalls=total_calls,
                transientFirstCalls=16000, additionalSafeIdempotentDispatches=total_calls - 96000,
                providerLog=artifact(path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet-reservation", required=True,
                        help="coordinator's active quiet-host/build-lock reservation label")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,80}", args.quiet_reservation):
        parser.error("quiet reservation must be a short identifier")
    directory = OWNER / ("full-reference-" + datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + str(os.getpid()))
    directory.mkdir(parents=True, exist_ok=False)
    (OWNER / "latest-full-reference-path").write_text(str(directory) + "\n")
    runner = Runner(directory, args.quiet_reservation)
    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)
    code = 1
    failure = None
    budget = None
    write_json(directory / "reservation.json", dict(timestamp=utc(), quietReservation=args.quiet_reservation,
        coordinatorContract="Root explicitly grants quiet host/application slot and holds all three build locks before invoking this runner",
        sourceRevision=EXPECTED_SOURCE, rootPomSha256=EXPECTED_ROOT_POM,
        runnerArtifact=artifact(pathlib.Path(__file__)),
        intendedFullRuns=3, executionsPerRun=10000, activitiesPerExecution=8,
        diskSafeguards=dict(beforeExpensivePhaseBytes=START_RESERVE_BYTES,
            monitoredWorkspaceFloorBytes=MONITORED_FLOOR_BYTES,
            tmpFloorBytes=TMP_FLOOR_BYTES, monitorIntervalSeconds=5,
            preservation="exact prospective copy bytes +512MiB; originals retained on refusal; cleanup never disk-gated"),
        warmupExecutions=0, mode="DURABLEFLOW_PREPARED=1; never prepare/build/Maven"))
    print(f"Full reference evidence: {directory}", flush=True)
    try:
        for number in range(1, 4):
            if INTERRUPTED:
                raise InterruptedError(f"interrupted by signal {INTERRUPTED}")
            runner.round(number)
        _, summary = runner.command("summarize-three-full-runs", [sys.executable, SUMMARY, *runner.directories], directory, 300)
        result = json.loads(summary.read_text())
        write_json(directory / "summary.json", result)
        budget = result["regressionBudget"]["status"]
        code = 0 if budget == "established" else 2
    except BaseException as error:
        failure = f"{type(error).__name__}: {error}"
        (directory / "exception.txt").write_text(traceback.format_exc())
        code = 128 + INTERRUPTED if INTERRUPTED else 1
    finally:
        report = dict(schemaVersion=1, project="durableflow", timestamp=utc(),
            sourceRevision=EXPECTED_SOURCE, rootPomSha256=EXPECTED_ROOT_POM,
            quietReservation=args.quiet_reservation, exitCode=code,
            assertions=dict(successfulFullRuns=len(runner.directories),
                            failedOrIncompleteRuns=sum(value["exitCode"] != 0 for value in runner.outcomes)),
            regressionBudgetStatus=budget, failure=failure, outcomes=runner.outcomes,
            commands="commands.jsonl", summary="summary.json" if budget else None,
            limitations=["Three real full runs are required; there is no smoke/count override.",
                         "Noisy reference returns exit2 with actual results and no numeric budget.",
                         "Closed-loop offered load can hide coordinated omission.",
                         "JFR estimates worker1 allocations; GC logs include startup.",
                         "Binary/large originals stay ignored; do not stage them in Git."])
        write_json(directory / "envelope.json", report)
        (directory / "exit-code.txt").write_text(str(code) + "\n")
        print(f"Full reference exit {code}; evidence {directory}", flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
