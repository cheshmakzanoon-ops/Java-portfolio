import collections
import datetime
import hashlib
import json
import pathlib
import re
import statistics

ROOT = pathlib.Path('/workspace/Java-portfolio')
BASE = ROOT / '.work/A02/full-reference-20261018T095104-746011'
OUT = pathlib.Path(__file__).resolve().parent
rows = []
for number in [1, 2]:
    folder = BASE / f'run-{number}'
    workload = json.loads((folder / 'workload-10000.json').read_text())
    frames = [json.loads(line) for line in (folder / 'resource-samples.jsonl').open()]
    time_of = lambda frame: datetime.datetime.fromisoformat(frame['timestamp']).timestamp()
    complete = [f for f in frames if len({p['role'] for p in f['processes']}) == 13]
    first, last = complete[0], complete[-1]
    interval = time_of(last) - time_of(first)
    by_role = {}
    for role in sorted(p['role'] for p in first['processes']):
        selected = [next(p for p in f['processes'] if p['role'] == role) for f in complete]
        assert len({p['pid'] for p in selected}) == 1
        cpu = (selected[-1]['cpuTicks'] - selected[0]['cpuTicks']) / first['clockTicksPerSecond']
        by_role[role] = {'sampledCpuDeltaSeconds': cpu, 'sampledAverageCores': cpu / interval,
                         'peakSampledRssBytes': max(p['rssBytes'] for p in selected),
                         'meanSampledRssBytes': statistics.mean(p['rssBytes'] for p in selected)}
    gc = {}
    for file in sorted((OUT / f'run-{number}' / 'gc').glob('*.log')):
        lines = file.read_text().splitlines()
        pauses = []
        for line in lines:
            match = re.search(r'^\[([^]]+)\].*\[gc\s*\].*GC\(\d+\) (Pause .*?) ([0-9.]+)ms$', line)
            if match:
                pauses.append((datetime.datetime.fromisoformat(match[1]), match[2], float(match[3])))
        started = datetime.datetime.fromisoformat(workload['startedAt'])
        ended = started + datetime.timedelta(seconds=workload['measurements']['elapsedSeconds'])
        measured = [p for p in pauses if started <= p[0] <= ended]
        gc[file.name] = {'sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                         'wholeLogPauseCount': len(pauses),
                         'wholeLogPauseMilliseconds': sum(p[2] for p in pauses),
                         'workloadWindowPauseCount': len(measured),
                         'workloadWindowPauseMilliseconds': sum(p[2] for p in measured),
                         'workloadWindowMaximumPauseMilliseconds': max((p[2] for p in measured), default=0),
                         'workloadWindowFullPauseCount': sum('Full' in p[1] for p in measured)}
    row = {'run': number, 'source': workload['sourceRevision'], 'measurements': workload['measurements'],
           'resourceFrameCount': len(frames), 'completeFrameCount': len(complete),
           'resourceBoundary': 'first to last complete 13-role sample, not full process lifetimes; PostgreSQL omitted',
           'sampledStart': first['timestamp'], 'sampledEnd': last['timestamp'],
           'sampledSpanSeconds': interval, 'roles': by_role,
           'totalSampledCpuSeconds': sum(v['sampledCpuDeltaSeconds'] for v in by_role.values()),
           'maximumSampledAggregateRssBytes': max(sum(p['rssBytes'] for p in f['processes']) for f in complete),
           'gc': gc,
           'latencyScope': 'per execution/task database metrics; percentile vectors p50/p95/p99, not throughput tails'}
    row['sampledAverageCores'] = row['totalSampledCpuSeconds'] / interval
    rows.append(row)
(OUT / 'resources-gc-comparison.json').write_text(json.dumps(rows, indent=2) + '\n')
for row in rows:
    print('RUN', row['run'], 'seconds', row['measurements']['elapsedSeconds'],
          'cpuSeconds', row['totalSampledCpuSeconds'], 'averageCores', row['sampledAverageCores'],
          'aggregateRss', row['maximumSampledAggregateRssBytes'])
    for role, data in row['roles'].items():
        print(role, round(data['sampledCpuDeltaSeconds'], 2), 'cpuSec', round(data['peakSampledRssBytes']/1024**2, 2), 'MiB')
    for role, data in row['gc'].items():
        print(role, data['workloadWindowPauseCount'], round(data['workloadWindowPauseMilliseconds'], 3), 'pauseMs', data['workloadWindowMaximumPauseMilliseconds'], 'maxMs')
