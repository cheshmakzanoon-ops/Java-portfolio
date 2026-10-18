#!/usr/bin/env python3
"""Scheduled validation only; no Maven, implicit builds, reused outputs or reference timings."""
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path('/workspace/Java-portfolio')
CHECKPOINT = ROOT / '.work/A08/repair-v3-checkpoint.json'


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ('cause', 'stress'):
        raise SystemExit('cause|stress NEW_OUTPUT_DIRECTORY; requires coordinator scheduling grant')
    grant = os.environ.get('THREADLENS_VALIDATION_GRANT', '').strip()
    if not grant:
        raise SystemExit('THREADLENS_VALIDATION_GRANT must identify the actual coordinator reservation')
    checkpoint = json.loads(CHECKPOINT.read_text())
    for name, expected in checkpoint['sourceFiles'].items():
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected:
            raise RuntimeError('source changed since certification: ' + name)
    for artifact in checkpoint['runtimeArtifacts'] + [checkpoint['agentArtifact']]:
        if hashlib.sha256(Path(artifact['path']).read_bytes()).hexdigest() != artifact['sha256']:
            raise RuntimeError('artifact changed since certification: ' + artifact['path'])
    out = Path(sys.argv[2]).resolve()
    if not out.is_relative_to(ROOT / '.work/A08'):
        raise RuntimeError('validation output must remain in owned scratch')
    out.mkdir(parents=True, exist_ok=False)
    cp = ':'.join(a['path'] for a in checkpoint['runtimeArtifacts'])
    java = str(Path(os.environ.get('JAVA_HOME', '/workspace/.tools/jdk-25')) / 'bin/java')
    context = {'schemaVersion': 1, 'startedAtUtc': datetime.now(timezone.utc).isoformat(),
               'sourceFingerprint': checkpoint['buildInputFingerprint'],
               'collectionConfiguration': checkpoint['collectionConfiguration'],
               'agentSha256': checkpoint['agentArtifact']['sha256'], 'reservation': grant,
               'scope': 'functional settings/cause/boundedness validation; not overhead acceptance'}
    write(out / 'context.json', context)

    def run(command, log, timeout):
        with log.open('wb') as output:
            result = subprocess.run(command, cwd=ROOT, stdout=output,
                                    stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f'child exit {result.returncode}; inspect {log}')

    def analyze(recording, report, timings=None):
        command = [java, '-Xmx512m', '-XX:ActiveProcessorCount=2', '-cp', cp,
                   'io.github.cheshmakzanoon.portfolio.threadlens.cli.ThreadLensCli',
                   'analyze', str(recording), str(report)]
        if timings is not None:
            command.extend(['--timings', str(timings)])
        run(command, report.parent / (report.name + '.log'), 120)
        return json.loads((report / 'report.json').read_text())

    if sys.argv[1] == 'cause':
        rng = random.Random(80842)
        trials = []
        for kind in ('cpu', 'allocation', 'contention'):
            for pair in range(7):
                order = ['baseline', 'regressed']
                rng.shuffle(order)
                reports, measurements = {}, {}
                for variant in order:
                    directory = out / f'{kind}-{pair}-{variant}'
                    directory.mkdir()
                    recording = directory / 'recording.jfr'
                    command = [java, '-Xms256m', '-Xmx256m', '-XX:ActiveProcessorCount=4',
                               '-cp', cp,
                               'io.github.cheshmakzanoon.portfolio.threadlens.workloads.WorkloadMain',
                               kind, str(variant == 'regressed').lower(), '1', '4', 'jfr',
                               str(directory / 'measurement.json'), str(recording)]
                    write(directory / 'command.json', command)
                    run(command, directory / 'process.log', 40)
                    settings = json.loads(Path(str(recording) + '.settings.json').read_text())
                    assert settings['jdk.ThreadPark#stackTrace'] == 'false'
                    assert settings['jdk.ObjectAllocationSample#throttle'] == '50/s'
                    measurements[variant] = json.loads((directory / 'measurement.json').read_text())
                    assert measurements[variant]['collectionConfiguration'] == checkpoint['collectionConfiguration']
                    reports[variant] = analyze(recording, directory / 'report')['profile']
                baseline, candidate = reports['baseline'], reports['regressed']
                if kind == 'cpu':
                    top = sorted(candidate['methods'], key=lambda m: (-candidate['methods'][m]['self'], m))[:3]
                    found = any('plantedCpuHotspot' in m for m in top)
                    detail = {'topThreeObservedSelfMethods': top}
                elif kind == 'allocation':
                    weighted = lambda p: sum(v['observedWeightedBytes'] for k, v in p['allocations'].items() if k in ('[B', 'byte[]'))
                    b, c = weighted(baseline), weighted(candidate)
                    found, detail = c > b and c > 0, {'baselineByteArrayWeightedBytes': b, 'regressedByteArrayWeightedBytes': c}
                else:
                    monitor = lambda p: sum(v['totalNanos'] for k, v in p['monitors'].items() if 'PlantedLock' in k)
                    b, c = monitor(baseline), monitor(candidate)
                    found, detail = c > b and c > 0, {'baselinePlantedMonitorNanos': b, 'regressedPlantedMonitorNanos': c}
                trials.append({'workload': kind, 'pair': pair, 'randomizedOrder': order,
                               'identified': found, 'details': detail, 'measurements': measurements})
                write(out / 'trials.json', trials)
                if sum(p.stat().st_size for p in out.rglob('*') if p.is_file()) > 256 * 1024 * 1024:
                    raise RuntimeError('cause output exceeds explicit256MiB budget')
        summary = {kind: {'identified': sum(t['identified'] for t in trials if t['workload'] == kind), 'trials': 7}
                   for kind in ('cpu', 'allocation', 'contention')}
        summary.update({'freshJvmRuns': 42, 'warmupSeconds': 1, 'measurementSeconds': 4,
                        'minimumPerCause': 6, 'passed': all(v['identified'] >= 6 for v in summary.values()),
                        'allSevenByCause': all(v['identified'] == 7 for v in summary.values())})
        write(out / 'summary.json', summary)
        assert summary['passed'], 'preserve failed cause trials; do not substitute a favorable subset'
    else:
        config = out / 'agent.yaml'
        agent_out = out / 'agent-output'
        config.write_text('allowPackages: [io.github.cheshmakzanoon.portfolio.threadlens.workloads]\n'
                          'methodNames: [plantedMonitorContention]\nmaxMethods: 1\nqueueCapacity: 65536\n'
                          'outputDirectory: ' + str(agent_out) + '\n')
        command = [java, '-Xms256m', '-Xmx256m', '-XX:ActiveProcessorCount=2',
                   '-javaagent:' + checkpoint['agentArtifact']['path'] + '=config=' + str(config),
                   '-cp', cp, 'io.github.cheshmakzanoon.portfolio.threadlens.cli.ObserverStress',
                   '1200', str(out / 'observer')]
        write(out / 'command.json', command)
        run(command, out / 'process.log', 1320)
        stress = json.loads((out / 'observer/stress-summary.json').read_text())
        assert stress['passed'] and stress['elapsedSeconds'] >= 1200
        status = dict(line.split('=', 1) for line in (agent_out / 'observer-status.txt').read_text().splitlines())
        assert status['statusVersion'] == '2' and status['statusComplete'] == 'true'
        assert status['collectionHealthy'] == 'true' and status['producerQuiesced'] == 'true'
        assert all(status[k] == '0' for k in ('probeFailures', 'shutdownDiscarded', 'unfinishedProbeExits', 'methodOverflow', 'timingHistogramOverflow'))
        events, drops = int(status['observedTimingEvents']), int(status['queueDrops'])
        assert events > 0 and events == int(status['acceptedTimingReservations'])
        assert events + drops == sum(stress['hostOperations'][2:])
        assert (status['incomplete'] == 'true') == (drops > 0)
        replay = analyze(out / 'observer/observer.jfr', out / 'replayed-report', agent_out)
        assert replay['profile']['counters']['observedTimingExits'] == events
        assert replay['profile']['counters']['timingUnaccountedEvents'] == 0
        for path in (out / 'observer/report.json', out / 'observer/report.html',
                     out / 'replayed-report/report.json', out / 'replayed-report/report.html'):
            assert 'fixture-secret-never-export' not in path.read_text()
        write(out / 'completed.json', {'stress': stress, 'agentStatus': status,
                                      'monitorHostOperationsEqualAcceptedPlusDrops': True,
                                      'scope': 'shared-host functional boundedness only; not overhead performance'})
    context.update({'completedAtUtc': datetime.now(timezone.utc).isoformat(), 'exitCode': 0})
    write(out / 'context.json', context)


if __name__ == '__main__':
    main()
