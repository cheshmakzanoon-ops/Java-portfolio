#!/usr/bin/env python3
"""Bind launcher artifacts to unchanged inputs; refuse rebuilding a live owned runtime."""
import hashlib
import json
import pathlib
import sys

project = pathlib.Path(__file__).resolve().parents[1]
root = project.parents[1]
marker = project / "target/prepared-runtime.json"
modules = ["ledger-domain", "ledger-persistence", "ledger-api", "ledger-verification"]


def fingerprint(files):
    digest = hashlib.sha256()
    for path in sorted(set(files)):
        digest.update(str(path.relative_to(root)).encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def require_idle():
    proc = pathlib.Path("/proc")
    if not proc.is_dir():
        raise SystemExit("Cannot inspect owned Java runtimes on this platform; preparation refused.")
    for entry in proc.iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            argv = (entry / "cmdline").read_bytes().split(b"\0")
        except OSError:
            continue
        if (argv and pathlib.Path(argv[0].decode(errors="replace")).name == "java"
                and any(str(project).encode() + b"/" in arg and b"/target/" in arg for arg in argv[1:])):
            raise SystemExit("Owned Java runtime is still alive (PID " + entry.name
                             + "); run down before rebuilding.")


command = sys.argv[1]
if command == "idle":
    require_idle()
    sys.exit(0)
sources = [root / "pom.xml", project / "pom.xml"]
for name in modules:
    module = project / name
    sources.append(module / "pom.xml")
    sources.extend(path for path in (module / "src").rglob("*") if path.is_file())
source_sha = fingerprint(sources)
if command == "snapshot":
    print(source_sha)
    sys.exit(0)
artifacts = [path for name in modules for path in (project / name / "target/classes").rglob("*")
             if path.is_file()]
jar = project / "ledger-api/target/daybook-api-1.0.0-SNAPSHOT-exec.jar"
artifacts.append(jar)
dependencies = list((project / "ledger-verification/target/dependency").glob("*.jar"))
artifacts.extend(dependencies)
required = [project / (name + "/target/classes/io/github/cheshmakzanoon/portfolio/daybook/" + cls)
            for name, cls in [("ledger-domain", "Money.class"),
                              ("ledger-persistence", "LedgerStore.class"),
                              ("ledger-verification", "Workload.class")]] + [jar]
if not all(path.is_file() for path in required) or not any(
        path.name.startswith("postgresql-") for path in dependencies):
    sys.exit(1)
state = {"schemaVersion": 1, "sourceSha256": source_sha,
         "artifactSha256": fingerprint(artifacts), "artifactCount": len(artifacts)}
if command == "check":
    try:
        previous = json.loads(marker.read_text())
    except (OSError, ValueError):
        sys.exit(1)
    sys.exit(0 if previous == state else 1)
elif command == "write":
    if len(sys.argv) != 3 or sys.argv[2] != source_sha:
        raise SystemExit("Source/root changed during preparation; prepare again.")
    require_idle()
    marker.parent.mkdir(parents=True, exist_ok=True)
    temporary = marker.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n")
    temporary.replace(marker)
else:
    raise SystemExit("usage: runtime-cache.py idle|snapshot|check|write EXPECTED_SOURCE_HASH")
