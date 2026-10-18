#!/usr/bin/env python3
"""Reclaim exact obsolete setup caches only after round 2, holding its coordinator."""
import datetime
import hashlib
import json
import os
import pathlib
import shutil
import signal
import stat
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
ATTEMPT = ROOT / ".work/A02/full-reference-20261018T095104-746011"
RECEIPT = ROOT / ".work/coordinator/inter-round-capacity-18.json"
TOOLS = pathlib.Path("/workspace/.tools")
IMAGES = [
    "sha256:04468c397044e22f2311a8fd8e5ddb478ab686b67634da068b9bd4b2d2eb3252",
    "sha256:97f07998d0d78d3f5f5ef8260844ca5c271cca35a9924fc3fcdcf43582641343",
]
DOWNLOADS = ["OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz", "jdk-21.tar.gz",
             "maven.tar.gz", "maven.zip"]


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def save():
    temporary = RECEIPT.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n")
    temporary.replace(RECEIPT)


def sha(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def census(path):
    rows = {}
    paths = [path, *path.rglob("*")] if path.is_dir() else [path]
    assert len(paths) < 5000
    for item in paths:
        mode = item.lstat().st_mode
        name = str(item.relative_to(path)) if item != path else "."
        if stat.S_ISLNK(mode):
            rows[name] = dict(kind="symlink", target=os.readlink(item))
        elif stat.S_ISDIR(mode):
            rows[name] = dict(kind="directory", mode=stat.S_IMODE(mode))
        else:
            assert stat.S_ISREG(mode) and item.stat().st_size < 250 * 1024 ** 2
            rows[name] = dict(kind="file", mode=stat.S_IMODE(mode),
                              bytes=item.stat().st_size, sha256=sha(item))
    return rows


def copy(source, destination):
    if source.is_dir():
        shutil.copytree(source, destination, symlinks=True)
    else:
        shutil.copy2(source, destination)


def live_drivers():
    found = []
    for entry in pathlib.Path("/proc").iterdir():
        if entry.name.isdigit():
            try:
                args = (entry / "cmdline").read_bytes().split(b"\0")
                main = b"io.github.cheshmakzanoon.portfolio.durableflow.WorkloadMain"
                if (main in args or b"-Dloader.main=" + main in args
                        or any(b"run_main WorkloadMain " in arg for arg in args)):
                    found.append(int(entry.name))
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                pass
    return found


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=20)
    report.setdefault("commands", []).append(dict(args=args, exitCode=result.returncode,
                                                 stdout=result.stdout, stderr=result.stderr))
    save()
    result.check_returncode()
    return result.stdout


report = dict(startedAtUtc=utc(), status="WAITING_FOR_ROUND_2", mutations=[],
              helperSha256=sha(pathlib.Path(__file__)),
              runnerSha256Before=sha(ROOT / ".work/A02/run-full-reference.py"),
              scope="Exact obsolete image caches and verified unused installer/JDK21 copies. No raw evidence, application volumes, containers, current images, source or runner changes.")
save()
deadline = time.monotonic() + 3600
while True:
    if (ATTEMPT / "envelope.json").exists():
        raise SystemExit("Reference ended before maintenance window; nothing changed")
    records = [json.loads(line) for line in (ATTEMPT / "commands.jsonl").read_text().splitlines()]
    completed = [row for row in records if row.get("label") == "full-10000x8"
                 and row.get("stdout", "").endswith("run-2/full-10000x8.log")]
    if completed:
        assert completed[-1]["exitCode"] == 0
        break
    assert time.monotonic() < deadline
    time.sleep(1)

assert not (ATTEMPT / "run-3").exists()
coordinators = []
identities = {}
for entry in pathlib.Path("/proc").iterdir():
    if entry.name.isdigit():
        try:
            args = (entry / "cmdline").read_bytes().split(b"\0")
            if any(arg.endswith(b"/A02/run-full-reference.py") for arg in args):
                coordinators.append(int(entry.name))
                raw_stat = (entry / "stat").read_text()
                identities[int(entry.name)] = (raw_stat[raw_stat.rfind(")") + 2:].split()[19], args)
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            pass
assert len(coordinators) == 1
pid = coordinators[0]
pidfd = os.pidfd_open(pid)
raw_stat = pathlib.Path(f"/proc/{pid}/stat").read_text()
assert identities[pid] == (raw_stat[raw_stat.rfind(")") + 2:].split()[19], pathlib.Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0"))
report.update(status="HOLD_INTENT", heldCoordinatorPid=pid, heldCoordinatorStartTicks=identities[pid][0], round2MeasuredCommand=completed[-1])
save()
held = False
def interrupt(number, _frame):
    raise InterruptedError(f"Maintenance interrupted by signal {number}")

signal.signal(signal.SIGTERM, interrupt)
signal.signal(signal.SIGINT, interrupt)
try:
    held = True
    signal.pidfd_send_signal(pidfd, signal.SIGSTOP)
    stop_deadline = time.monotonic() + 2
    while True:
        process_stat = pathlib.Path(f"/proc/{pid}/stat").read_text()
        if process_stat[process_stat.rfind(")") + 2:].split()[0] in {"T", "t"}:
            break
        assert time.monotonic() < stop_deadline
        time.sleep(.01)
    report.update(status="INTER_ROUND_MAINTENANCE", heldAtUtc=utc(),
                  freeBytesBefore=shutil.disk_usage(ROOT).free)
    save()
    assert not (ATTEMPT / "run-3").exists() and not live_drivers()
    assert shutil.disk_usage("/tmp").free > 3 * 1024 ** 3
    assert (TOOLS / "jdk-25/bin/java").is_file()
    assert (TOOLS / "maven-3.10.0/bin/mvn").is_file()
    for entry in pathlib.Path("/proc").iterdir():
        if entry.name.isdigit():
            try:
                assert not os.readlink(entry / "exe").startswith(str(TOOLS / "jdk-21") + "/")
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                pass
    backup = pathlib.Path(tempfile.mkdtemp(prefix="portfolio-unused-setup-preservation-", dir="/tmp"))
    report["backupRoot"] = str(backup)
    inputs = [TOOLS / "downloads" / name for name in DOWNLOADS] + [TOOLS / "jdk-21"]
    captures = []
    for index, source in enumerate(inputs):
        assert source.exists() and not source.is_symlink()
        before = census(source)
        target = backup / (str(index) + "-" + source.name)
        copy(source, target)
        assert census(target) == before
        restored = backup / ("restore-" + str(index))
        copy(target, restored)
        assert census(restored) == before == census(source)
        if restored.is_dir():
            shutil.rmtree(restored)
        else:
            restored.unlink()
        captures.append(dict(source=str(source), backup=str(target), entries=before))
    assert sum(row.get("bytes", 0) for capture in captures for row in capture["entries"].values()) < 1024 ** 3
    (backup / "manifest.json").write_text(json.dumps(captures, indent=2) + "\n")
    report.update(verifiedBackupManifest=str(backup / "manifest.json"), physicalRestoreVerified=True)
    save()
    for image in IMAGES:
        assert not command(["docker", "ps", "-aq", "--filter", "ancestor=" + image]).strip()
        command(["docker", "image", "inspect", image, "--format", "{{.Id}} {{json .RepoDigests}} {{.Size}}"])
        command(["docker", "image", "rm", image])
        report["mutations"].append(dict(kind="obsolete-unreferenced-image-cache", image=image))
        save()
    for capture in captures:
        source = pathlib.Path(capture["source"])
        assert census(source) == capture["entries"]
        if source.is_dir():
            shutil.rmtree(source)
        else:
            source.unlink()
        report["mutations"].append(dict(kind="verified-unused-setup-relocation", source=str(source), backup=capture["backup"]))
        save()
    assert sha(ROOT / ".work/A02/run-full-reference.py") == report["runnerSha256Before"]
    report.update(status="COMPLETE", freeBytesAfter=shutil.disk_usage(ROOT).free,
                  freeBytesReclaimed=shutil.disk_usage(ROOT).free - report["freeBytesBefore"],
                  finishedAtUtc=utc(), rawEvidenceUnchanged=True)
except BaseException as error:
    report.update(status="FAILED", failure=type(error).__name__ + ": " + str(error), finishedAtUtc=utc())
    raise
finally:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    try:
        if held:
            try:
                signal.pidfd_send_signal(pidfd, signal.SIGCONT)
                report["resumedAtUtc"] = utc()
            except ProcessLookupError:
                report["heldCoordinatorAlreadyExited"] = True
            except BaseException as error:
                report["resumeError"] = type(error).__name__ + ": " + str(error)
                raise
    finally:
        os.close(pidfd)
        save()
print(json.dumps({key: value for key, value in report.items() if key not in {"commands", "round2MeasuredCommand"}}, indent=2))
