#!/usr/bin/env python3
"""Unexecuted preparation: JSON-only assembly AFTER explicit terminal root release.

Does not run Java/JFR/Docker/Maven, scan recordings, hash raw files, edit product,
or pronounce project/release acceptance. Root must separately verify raw lineage.
"""
import argparse
import json
import math
import os
from pathlib import Path
import statistics

SOURCE = 'working-tree-sha256:2f7d000815cce016675463dcac431f93332bf2f4049a0b19a1330391fda940fc'

def read_small(path):
    path = Path(path)
    if path.stat().st_size > 1_000_000:
        raise ValueError('Refuse non-small JSON: ' + str(path))
    return json.loads(path.read_text())

def finite_positive(value):
    if not isinstance(value, (float, int)) or isinstance(value, bool) or not math.isfinite(value) or value <= 0:
        raise ValueError('Expected positive finite metric: ' + str(value))
    return value

def stats(values):
    values = [finite_positive(value) for value in values]
    mean = statistics.mean(values)
    sd = statistics.stdev(values)
    return {'n': len(values), 'arithmeticMean': mean, 'sampleStandardDeviation': sd,
            'sampleCVPercent': sd / mean * 100, 'minimum': min(values), 'maximum': max(values)}

def main():
    if os.environ.get('PULSE_POSTPROCESS_TERMINAL_RELEASE') != 'root-explicitly-released':
        raise SystemExit('Pending explicit root terminal postprocessing release; do not execute during active measurement.')
    parser = argparse.ArgumentParser()
    parser.add_argument('terminal_summary', type=Path)
    parser.add_argument('scratch_output', type=Path)
    args = parser.parse_args()
    scratch = Path(__file__).resolve().parent
    output = args.scratch_output.resolve()
    if not output.is_relative_to(scratch) or output.exists():
        raise ValueError('Output must be a new file under this scratch preparation directory')
    summary = read_small(args.terminal_summary)
    if summary.get('status') != 'MEASUREMENTS_PASSED' or summary.get('exitCode') != 0 or not summary.get('ended'):
        raise ValueError('No successful terminal reservation')
    teardown = summary.get('teardown', {})
    if not teardown.get('attempted') or not teardown.get('success') or teardown.get('errors'):
        raise ValueError('Terminal scoped teardown must pass')
    if summary.get('sourceRevision') != SOURCE:
        raise ValueError('Unexpected frozen source')
    by_name = {phase['name']: phase for phase in summary['phases']}
    names = ['engine-original', 'engine-current', 'durable-full-1', 'durable-full-2', 'durable-full-3']
    directories = {}
    for name in names:
        phase = by_name[name]
        if phase.get('exitCode') != 0 or len(phase.get('artifactDirectories', [])) != 1:
            raise ValueError('Incomplete phase: ' + name)
        directories[name] = Path(phase['artifactDirectories'][0])
    if len(set(directories.values())) != 5:
        raise ValueError('Reused measurement directory')
    full = []
    for name in names[2:]:
        value = read_small(directories[name] / 'result.json')
        measured = value['measurements']
        if (value['sourceRevision'] != SOURCE or value['workload'] != 'durable-v1-full' or value['seed'] != 4404
            or measured['acceptedMeasurements'] != 1_000_000 or measured['duplicateCount'] != 1000
            or value['assertions'] != {'passed': 6, 'failed': 0}
            or measured['watermark'] != 94000 or measured['publishedWindows'] != 20000
            or value['committedDigest'] != value['recoveryDigest'] or value['resultDigest'] != value['oracleDigest']):
            raise ValueError('Required full workload assertion failed: ' + name)
        elapsed = finite_positive(measured['elapsedSeconds'])
        rate = finite_positive(measured['acceptedEventsPerSecond'])
        if not math.isclose(rate, 1_000_000 / elapsed, rel_tol=1e-10):
            raise ValueError('Throughput arithmetic mismatch')
        if not math.isclose(measured['averageProcessCpuCores'], measured['processCpuSeconds'] / elapsed, rel_tol=1e-10):
            raise ValueError('CPU arithmetic mismatch')
        full.append({'phase': name, 'directory': str(directories[name]), 'result': value})
    if len({value['result']['run'] for value in full}) != 3 or len({value['result']['database'] for value in full}) != 3:
        raise ValueError('Full runs must have independent identities')
    if len({value['result']['resultDigest'] for value in full}) != 1:
        raise ValueError('Cross-round independent aggregate digest mismatch')
    engines = {}
    for name in names[:2]:
        rows = read_small(directories[name] / 'jmh.json')
        if len(rows) != 1:
            raise ValueError('Expected one primary engine benchmark')
        row = rows[0]
        raw = row['primaryMetric']['rawData']
        if row['forks'] != 3 or len(raw) != 3 or any(len(fork) != 3 for fork in raw):
            raise ValueError('Expected three forks, each with three measurements')
        means = [statistics.mean([finite_positive(value) for value in fork]) for fork in raw]
        engines[name] = {'directory': str(directories[name]), 'originalJmhResult': row,
                         'forkMeans': means, 'acrossForkMeanStatistics': stats(means)}
    rates = [value['result']['measurements']['acceptedEventsPerSecond'] for value in full]
    elapsed = [value['result']['measurements']['elapsedSeconds'] for value in full]
    result = {'classification': 'small-json-analysis-pending-independent-raw-review', 'sourceRevision': SOURCE,
              'terminalSummary': str(args.terminal_summary.resolve()), 'reservation': summary['reservation'],
              'durableRounds': full, 'throughputAcrossRoundStatistics': stats(rates),
              'aggregateAcceptedEventsPerSecond': 3_000_000 / sum(elapsed), 'engines': engines,
              'rawInventory': [{'phase': phase['name'], 'artifactDirectories': phase.get('artifactDirectories'),
                               'runnerRecordedArtifactFields': {key: value for key, value in phase.items()
                                                                if 'hash' in key.lower() or 'artifact' in key.lower()}}
                              for phase in summary['phases']],
              'boundaries': ['Raw inventory/hash verification and JFR/profile interpretation remain separate review tasks.',
                             'Three sequential single-host rounds are not a stable capacity or SLO claim.',
                             'Per-round latency quantiles are not pooled into global quantiles.',
                             'Closed-loop checkpoint latency excludes offered-load queue waiting.',
                             'Java process metrics exclude container resource costs; supervision overhead is included, not independently quantified.',
                             'Engine microbenchmark ratio does not establish durable causal benefit; allocation totals do not identify a dominant cause.']}
    output.write_text(json.dumps(result, indent=2, allow_nan=False) + '\n')
    print(output)

if __name__ == '__main__':
    main()
