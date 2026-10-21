#!/usr/bin/env python3
"""Explicit local runtime preparation receipt; never replaces a verification gate."""
import argparse
import datetime
import hashlib
import json
import pathlib
import sys


COMMAND = "./mvnw -f projects/02-handoff/pom.xml -Dmaven.repo.local=.work/A02/m2 -B -ntp verify"
MODULES = ("workflow-model", "workflow-engine", "workflow-persistence",
           "workflow-worker-sdk", "workflow-api", "workflow-verification")


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def snapshot(project):
    h = hashlib.sha256()
    for file in sorted(project.rglob("*")):
        relative = file.relative_to(project)
        if (file.is_file() and not {"target", ".jqwik-database", "__pycache__", "docs", "evidence"}.intersection(relative.parts)
                and file.name != "README.md"):
            h.update(str(relative).encode())
            h.update(b"\0")
            h.update(file.read_bytes())
            h.update(b"\0")
    return {"sourceFingerprint": "sha256:" + h.hexdigest(),
            "rootPomSha256": digest(project.parents[1] / "pom.xml")}


def artifacts(project):
    names = [f"{module}/target/handoff-{module}-1.0.0-SNAPSHOT.jar" for module in MODULES]
    names += [f"{module}/target/handoff-{module}-1.0.0-SNAPSHOT-exec.jar"
              for module in ("workflow-api", "workflow-verification")]
    return {name: digest(project / name) for name in names}


def ensure_stopped(project):
    # Inspect live argv, including an owned process whose PID file was lost.
    # Linux cloud runtime is required for the project resource/quiet-run protocol.
    proc = pathlib.Path("/proc")
    if not proc.is_dir():
        raise ValueError("prepared mode requires Linux process ownership checks")
    jars = {str(project / f"{module}/target/handoff-{module}-1.0.0-SNAPSHOT-exec.jar").encode()
            for module in ("workflow-api", "workflow-verification")}
    for process in proc.iterdir():
        if not process.name.isdigit():
            continue
        try:
            argv = (process / "cmdline").read_bytes().split(b"\0")
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
        if jars.intersection(argv):
            raise ValueError("owned runtime is still alive (PID " + process.name
                             + "); run down before prepare")


def stamp(project, expected):
    ensure_stopped(project)
    current = snapshot(project)
    if current != expected:
        raise ValueError("source/root POM changed during verification; prepare again")
    log = project / "target/preparation.log"
    # prepare.sh calls this only after the real wrapper exits successfully.
    if "BUILD SUCCESS" not in log.read_text():
        raise ValueError("successful verification log is required")
    receipt = dict(schemaVersion=1, timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   **current, artifacts=artifacts(project),
                   verification={"command": COMMAND, "exitCode": 0, "logSha256": digest(log)})
    target = project / "target/prepared-build.json"
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(receipt, indent=2) + "\n")
    temporary.replace(target)
    return receipt


def verify(project):
    receipt = json.loads((project / "target/prepared-build.json").read_text())
    if receipt.get("schemaVersion") != 1:
        raise ValueError("unsupported preparation receipt")
    current = snapshot(project)
    if any(receipt.get(key) != value for key, value in current.items()):
        raise ValueError("prepared source/root POM is stale; run scripts/prepare.sh")
    proof = receipt.get("verification", {})
    if (proof.get("command") != COMMAND or proof.get("exitCode") != 0
            or proof.get("logSha256") != digest(project / "target/preparation.log")):
        raise ValueError("verification receipt/log changed; run scripts/prepare.sh")
    if receipt.get("artifacts") != artifacts(project):
        raise ValueError("prepared JAR changed; run scripts/prepare.sh")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("snapshot", "stamp", "verify", "idle"))
    parser.add_argument("--project", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[1])
    parser.add_argument("--expected")
    args = parser.parse_args()
    project = args.project.resolve()
    try:
        if args.command == "snapshot":
            print(json.dumps(snapshot(project), sort_keys=True))
        elif args.command == "stamp":
            print(stamp(project, json.loads(args.expected))["sourceFingerprint"])
        elif args.command == "idle":
            ensure_stopped(project)
            print("No owned runtime process is alive")
        else:
            print("Validated prepared runtime: " + verify(project)["sourceFingerprint"])
    except (OSError, ValueError, TypeError, KeyError) as failure:
        print("Prepared runtime refused: " + str(failure), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
