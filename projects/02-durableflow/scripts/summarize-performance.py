#!/usr/bin/env python3
"""Summarize three independently captured full runs; never synthesize missing evidence."""
import datetime
import json
import math
import pathlib
import re
import statistics
import sys

if len(sys.argv) != 4:
    raise SystemExit('Usage: summarize-performance.py RUN1_DIRECTORY RUN2_DIRECTORY RUN3_DIRECTORY')
runs = []
identity = None
for argument in sys.argv[1:]:
    directory = pathlib.Path(argument)
    if (directory / 'exit-code.txt').read_text().strip() != '0':
        raise SystemExit(f'Failed run: {directory}')
    result = json.loads((directory / 'workload-10000.json').read_text())
    environment = json.loads((directory / 'environment.json').read_text())
    measurements = result['measurements']
    required = dict(executions=10000, completedActivities=80000, independentProviderEffects=80000,
                    firedBusinessTimers=5000, acceptedUniqueSignals=10000, transientRejectedAttempts=16000,
                    queuedAtEnd=0)
    for name, expected in required.items():
        if int(measurements[name]) != expected:
            raise SystemExit(f'Wrong full-workload dimension: {name} in {directory}')
    if result['exitCode'] != 0 or not result['profilingCaptured']:
        raise SystemExit(f'Incomplete profiling: {directory}')
    current = tuple(environment[name] if not isinstance(environment[name], dict)
                    else json.dumps(environment[name], sort_keys=True)
                    for name in ('sourceFingerprint', 'rootPomSha256', 'cpu', 'cgroupLimits', 'jdk', 'jvmFlags'))
    if identity is not None and current != identity:
        raise SystemExit('Run source/reference-environment identities differ')
    identity = current
    frames = [json.loads(line) for line in (directory / 'resource-samples.jsonl').read_text().splitlines()]
    if not frames:
        raise SystemExit(f'Missing resource samples: {directory}')
    required_roles = {'api','provider','scheduler-1','scheduler-2','driver'} | {f'worker-{n}' for n in range(1,9)}
    working_frames = [frame for frame in frames if any(process['role']=='driver' for process in frame['processes'])]
    if not working_frames or any(not required_roles.issubset({process['role'] for process in frame['processes']}) for frame in working_frames):
        raise SystemExit(f'Incomplete live eight-worker/two-scheduler process evidence: {directory}')
    maximum_rss = max(sum(process['rssBytes'] for process in frame['processes']) for frame in frames)
    processes = {}
    for frame in frames:
        for process in frame['processes']:
            key = (process['role'], process['pid'])
            if key not in processes:
                processes[key] = [process['cpuTicks'], process['cpuTicks']]
            processes[key][1] = process['cpuTicks']
    cpu_seconds = sum(last-first for first,last in processes.values()) / frames[0]['clockTicksPerSecond']
    gc = {}
    for file in (directory / 'gc').glob('*-gc.log*'):
        pauses = [float(value) for value in re.findall(r'GC\(\d+\) Pause[^\n]*? ([\d.]+)ms\s*$', file.read_text(), re.M)]
        if pauses:
            gc[file.name] = dict(pauses=len(pauses), totalPauseMs=sum(pauses), maxPauseMs=max(pauses))
    events = json.loads((directory / 'allocation-samples.json').read_text())['recording']['events']
    allocation = [event['values']['weight'] for event in events if event['type'] == 'jdk.ObjectAllocationSample']
    def p95(name):
        values = [float(value) for value in measurements[name].strip('{}').split(',')]
        if len(values) != 3 or not all(math.isfinite(value) for value in values):
            raise SystemExit(f'Invalid percentile vector: {name}')
        return values[1]
    runs.append(dict(directory=str(directory), startedAt=result['startedAt'],
                     elapsedSeconds=measurements['elapsedSeconds'],
                     activitiesPerSecond=int(measurements['completedActivities'])/measurements['elapsedSeconds'],
                     completionP95Ms=p95('completionLatencyMs'), scheduleToStartP95Ms=p95('scheduleToStartMs'),
                     timerLatenessP95Ms=p95('timerLatenessMs'), maximumSampledClusterRssBytes=maximum_rss,
                     sampledProcessCpuSeconds=cpu_seconds, worker1AllocationSamples=len(allocation),
                     worker1EstimatedAllocatedBytes=sum(allocation), gc=gc))
if len({run['startedAt'] for run in runs}) != 3:
    raise SystemExit('Three distinct run timestamps required')
summary = {}
for name in ('elapsedSeconds', 'activitiesPerSecond', 'completionP95Ms', 'scheduleToStartP95Ms',
             'timerLatenessP95Ms', 'maximumSampledClusterRssBytes'):
    values = [run[name] for run in runs]
    mean = statistics.mean(values)
    summary[name] = dict(minimum=min(values), median=statistics.median(values), maximum=max(values),
                         sampleStddev=statistics.stdev(values), coefficientOfVariation=statistics.stdev(values)/mean if mean else 0)
stable = all(summary[name]['coefficientOfVariation'] <= 0.10
             for name in ('elapsedSeconds', 'completionP95Ms', 'scheduleToStartP95Ms'))
output = dict(schemaVersion=1, project='durableflow', workload='seeded-eight-activities-v1',
              timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
              sourceRevision=environment['sourceFingerprint'], rootPomSha256=environment['rootPomSha256'],
              command=' '.join(sys.argv), exitCode=0, assertions=dict(fullRuns=3, failed=0),
              measurements=summary, runs=runs,
              regressionBudget=dict(status='established' if stable else 'unestablished-noisy-reference',
                  sameEnvironmentOnly=True, policy='25% above the slowest of three full reference runs; establish only if duration/completion/scheduling CV <=10%',
                  limits={name:summary[name]['maximum']*1.25 for name in ('elapsedSeconds', 'completionP95Ms', 'scheduleToStartP95Ms')} if stable else {}),
              limitations=['Independent database resets and quiet-host scheduling must also be evidenced in command logs.',
                           'Closed-loop offered load may hide coordinated omission.',
                           'JFR allocation samples cover worker 1 and are statistical estimates.',
                           'GC logs include service startup; process RSS and CPU are sampled.'])
print(json.dumps(output, indent=2))
