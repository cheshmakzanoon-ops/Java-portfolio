#!/usr/bin/env python3
"""Check committed SQL cardinalities and every raw latency sample after a driver exits."""
import csv
import json
import pathlib
import sys

output = pathlib.Path(sys.argv[1])
count = int(sys.argv[2])
result = json.loads((output / "results.json").read_text())
observed = json.loads((output / "postconditions.json").read_text())
assert result["exitCode"] == 0 and result["journals"] == count
outcomes = json.loads((output / "client-outcomes.json").read_text())
assert outcomes["completedInputGroups"] == count and outcomes["failedInputGroups"] == 0
assert outcomes["clientRetryExhaustions"] == 0
for key, value in outcomes.items():
    assert result[key] == value, (key, result[key], value)
expected_journals = count + (count + 999) // 1000
for field in ["journals", "receipts", "outboxEvents"]:
    assert observed[field] == expected_journals, (field, observed[field], expected_journals)
assert observed["pendingAfterReplay"] == 0
assert observed["tenantsTouched"] == result["tenantsTouched"]
assert observed["accountsTouched"] == result["accountsTouched"]
with (output / "latencies.csv").open(newline="") as stream:
    rows = list(csv.DictReader(stream))
assert len(rows) == count
assert [int(row["index"]) for row in rows] == list(range(count))
assert all(int(row["latency_nanos"]) > 0 for row in rows)
summary = {
    "schemaVersion": 1,
    "sourceRevision": result["sourceRevision"],
    "exitCode": 0,
    "completedInputGroups": count,
    "failedInputGroups": 0,
    "latencySamples": len(rows),
    "observedSql": observed,
    "basis": "Every worker future completed; each input has one positive latency sample; committed SQL counts and replay backlog checked independently.",
}
(output / "validated-postconditions.json").write_text(json.dumps(summary, indent=2) + "\n")
