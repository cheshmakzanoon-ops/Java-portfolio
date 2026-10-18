#!/usr/bin/env python3
"""Publish only completed prescribed multi-fork JMH observations, retaining raw output."""
import datetime
import json
import pathlib

project = pathlib.Path(__file__).resolve().parents[1]
base = project / "target/microbenchmark/full"
environment = json.loads((base / "environment.json").read_text())
results = json.loads((base / "results.json").read_text())
expected = {
    ("canonicalDigest", "2"), ("canonicalDigest", "100"),
    ("parseExactMoney", "2"), ("parseExactMoney", "100"),
}
assert len(results) == 4
assert {(row["benchmark"].split(".")[-1], row["params"]["lines"]) for row in results} == expected
cases = []
for row in results:
    assert row["forks"] == 3 and row["threads"] == 1
    assert row["warmupIterations"] == 3 and row["measurementIterations"] == 5
    assert row["warmupTime"] == "1 s" and row["measurementTime"] == "1 s"
    assert row["jmhVersion"] == "1.37" and row["mode"] == "avgt"
    metric = row["primaryMetric"]
    assert metric["scoreUnit"] == "ns/op" and metric["score"] > 0
    assert len(metric["rawData"]) == 3 and all(len(fork) == 5 for fork in metric["rawData"])
    allocation = row["secondaryMetrics"]["gc.alloc.rate.norm"]
    assert allocation["scoreUnit"] == "B/op" and allocation["score"] > 0
    cases.append({
        "benchmark": row["benchmark"], "lines": row["params"]["lines"],
        "primaryMetric": metric, "allocationBytesPerOperation": allocation,
        "jvm": row["jvm"], "jvmArgs": row["jvmArgs"], "jdkVersion": row["jdkVersion"],
    })
summary = {
    "schemaVersion": 1, "project": "clearledger", "status": "PASS_FULL_JMH_OBSERVATIONS",
    "recordedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "sourceRevision": environment["sourceRevision"], "sourceCommit": environment["sourceCommit"],
    "rootPomSha256": environment["rootPomSha256"], "command": environment["launcherCommand"],
    "jmhVersion": "1.37", "forks": 3, "warmups": "3 x 1s", "measurements": "5 x 1s",
    "cases": cases, "rawResults": str(base / "results.json"),
    "environment": str(base / "environment.json"), "console": str(base / "console.log"),
    "limitations": [
        "Pure CPU exact parsing/digest operations only; excludes database and HTTP.",
        "Returned values are consumed by JMH; GC profiling is enabled.",
        "Reference-host observations do not predict production service capacity.",
        "Host isolation is a separately recorded coordinator reservation; source hashes cannot prove isolation.",
    ],
}
output = project / "evidence/jmh-current.json"
output.write_text(json.dumps(summary, indent=2) + "\n")
print(output)
