#!/usr/bin/env python3
"""Validate exact prepared JMH inputs and artifacts before a build-free quiet phase."""
import hashlib
import json
import pathlib
import sys

project = pathlib.Path(__file__).resolve().parents[1]
root = project.parents[1]
marker = project / "target/prepared-jmh.json"


def fingerprint(files):
    digest = hashlib.sha256()
    for path in sorted(set(files)):
        digest.update(str(path.relative_to(root)).encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


sources = [root / "pom.xml", project / "pom.xml"]
for name in ["ledger-domain", "ledger-benchmarks"]:
    module = project / name
    sources.append(module / "pom.xml")
    sources.extend(path for path in (module / "src").rglob("*") if path.is_file())
source_sha = fingerprint(sources)
if sys.argv[1] == "snapshot":
    print(source_sha)
    sys.exit(0)
classes = project / "ledger-benchmarks/target/classes"
artifacts = [path for path in classes.rglob("*") if path.is_file()]
dependencies = list((project / "ledger-benchmarks/target/dependency").glob("*.jar"))
artifacts.extend(dependencies)
if not (classes / "META-INF/BenchmarkList").is_file():
    sys.exit(1)
for prefix in ["jmh-core-", "daybook-domain-"]:
    if not any(path.name.startswith(prefix) for path in dependencies):
        sys.exit(1)
state = {
    "schemaVersion": 1,
    "sourceSha256": source_sha,
    "artifactSha256": fingerprint(artifacts),
    "artifactCount": len(artifacts),
}
if sys.argv[1] == "check":
    try:
        previous = json.loads(marker.read_text())
    except (OSError, ValueError):
        sys.exit(1)
    sys.exit(0 if previous == state else 1)
elif sys.argv[1] == "write":
    if len(sys.argv) != 3 or sys.argv[2] != source_sha:
        raise SystemExit("Source/root changed during JMH preparation; build again.")
    marker.parent.mkdir(parents=True, exist_ok=True)
    temporary = marker.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n")
    temporary.replace(marker)
else:
    raise SystemExit("usage: jmh-cache.py snapshot|check|write EXPECTED_SOURCE_HASH")
