#!/usr/bin/env python3
"""Produce a small audit index and actual sampling summary from retained raw files."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

run = Path(sys.argv[1])
mode = sys.argv[2]
exit_code = int((run / 'exit-code.txt').read_text())
summary = {'schemaVersion': 1, 'mode': mode, 'exitCode': exit_code, 'rawFiles': {}}
for path in sorted(run.iterdir()):
    if path.is_file() and path.name != 'capture-summary.json':
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        summary['rawFiles'][path.name] = {'bytes': path.stat().st_size, 'sha256': digest.hexdigest()}
if (run / 'jfr-events.json').exists():
    events = json.loads((run / 'jfr-events.json').read_text())['recording']['events']
    types = Counter(event['type'] for event in events)
    cpu = Counter()
    allocations = Counter()
    for event in events:
        values = event['values']
        if event['type'] == 'jdk.ExecutionSample':
            trace = values.get('stackTrace') or {}
            frames = trace.get('frames') or []
            if frames:
                method = frames[0].get('method') or {}
                owner = (method.get('type') or {}).get('name', '?')
                cpu[owner + '.' + method.get('name', '?')] += 1
        if event['type'] == 'jdk.ObjectAllocationSample':
            cls = (values.get('objectClass') or {}).get('name', '?')
            allocations[cls] += values.get('weight', 0)
    summary['jfrSampleCounts'] = dict(types)
    summary['sampledCpuTopFrames'] = cpu.most_common(15)
    summary['allocationSampleWeightsByClass'] = allocations.most_common(15)
    summary['samplingLimitations'] = 'JFR is sampling evidence covering process setup, measured loop and verification/recovery; weights are not exact matching-only allocations or CPU time.'
if mode != 'jmh':
    rows = []
    for line in (run / 'stdout.txt').read_text().splitlines():
        try:
            value = json.loads(line)
            if isinstance(value, dict) and 'businessResultCounts' in value:
                rows.append(value)
        except json.JSONDecodeError:
            pass
    if exit_code == 0 and len(rows) != 1:
        raise SystemExit('Successful driver must emit exactly one result object')
    if rows:
        (run / 'result.json').write_text(json.dumps(rows[-1], indent=2) + '\n')
        summary['resultFile'] = 'result.json'
(run / 'capture-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(str(run))
