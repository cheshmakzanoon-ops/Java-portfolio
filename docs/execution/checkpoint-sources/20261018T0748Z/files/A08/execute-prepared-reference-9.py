#!/usr/bin/env python3
"""Full mandatory protocol, bounded owned-tree lifetime and durable failure evidence."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import os
import sys
from reference_owned_process import Cancellation, OwnedProcess

INPUTS = Path('/workspace/Java-portfolio/.work/A08/reference-study-9-inputs')
TOTAL_TIMEOUT_SECONDS = 10_800
CONFIGURATION = 'v3-allocation50-park-class-only'
OWNER = 'io.github.cheshmakzanoon.portfolio.threadlens.workloads.ControlledWorkload.'
METHODS = {OWNER + 'plantedCpuHotspot(I)J', OWNER + 'plantedAllocationPressure(I)V',
           OWNER + 'plantedMonitorContention(J)V'}


def utc():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + '.tmp')
    with temporary.open('w') as output:
        output.write(json.dumps(value, indent=2) + '\n')
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def validate_instrumented(run, measurement):
    expected_config = ('allowPackages: [io.github.cheshmakzanoon.portfolio.threadlens.workloads]\n'
                       'methodNames: [plantedCpuHotspot, plantedAllocationPressure, plantedMonitorContention]\n'
                       'maxMethods: 3\nqueueCapacity: 65536\noutputDirectory: '
                       + str((run / 'agent').resolve()) + '\n')
    require((run / 'threadlens.yaml').read_text() == expected_config, 'Selected-method configuration differs: ' + str(run))
    status = {}
    rows = (run / 'agent/observer-status.txt').read_text().splitlines()
    for row in rows:
        key, value = row.split('=', 1)
        require(key not in status, 'Duplicate status field: ' + str(run))
        status[key] = value
    required_fields = {'statusVersion', 'statusComplete', 'collectionHealthy', 'queueDrops',
                       'shutdownDiscarded', 'incomplete', 'probeFailures', 'methodOverflow',
                       'timingHistogramOverflow', 'durationClipped', 'observedTimingEvents',
                       'acceptedTimingReservations', 'producerQuiesced', 'unfinishedProbeExits',
                       'rawTimingRowsOmitted', 'activeMethodIdLimit', 'activeQueueCapacity', 'skips'}
    require(required_fields <= set(status), 'Required final status fields missing')
    require(rows[-1] == 'statusComplete=true' and status['statusVersion'] == '2'
            and status['collectionHealthy'] == 'true' and status['producerQuiesced'] == 'true',
            'Incomplete/unhealthy producer status: ' + str(run))
    require(all(status[k] == '0' for k in ('probeFailures', 'shutdownDiscarded', 'unfinishedProbeExits',
                                         'methodOverflow', 'timingHistogramOverflow', 'durationClipped')),
            'Producer failure or lost selected coverage: ' + str(run))
    require(status['activeMethodIdLimit'] == '3' and status['activeQueueCapacity'] == '65536',
            'Active collection limits differ: ' + str(run))
    method_rows = (run / 'agent/methods.tsv').read_text().splitlines()
    require(method_rows[0] == 'methodId\tidentity', 'Method registry header differs')
    registry = {}
    for row in method_rows[1:]:
        key, name = row.split('\t', 1)
        require(key not in registry, 'Duplicate method ID')
        registry[key] = name
    require(len(registry) == 3 and set(registry.values()) == METHODS, 'Selected method identity set differs')
    summary_rows = (run / 'agent/timing-summary.tsv').read_text().splitlines()
    require(summary_rows[0] == 'methodId\texits\texceptionalExits\ttotalNanos\tp50Nanos\tp95Nanos\tp99Nanos',
            'Timing summary header differs')
    counts = {}
    for row in summary_rows[1:]:
        columns = row.split('\t')
        require(len(columns) == 7 and columns[0] in registry, 'Unknown timing summary method')
        name = registry[columns[0]]
        require(name not in counts, 'Duplicate timing summary method')
        counts[name] = int(columns[1])
        require(counts[name] > 0 and int(columns[2]) == 0, 'Missing/exceptional workload timing')
    active = ({OWNER + 'plantedCpuHotspot(I)J'} if measurement['kind'] == 'cpu' else
              {OWNER + 'plantedCpuHotspot(I)J', OWNER + 'plantedAllocationPressure(I)V'}
              if measurement['kind'] == 'allocation' else {OWNER + 'plantedMonitorContention(J)V'})
    require(set(counts) == active, 'Expected workload timing subjects are absent or unexpected')
    accepted, observed, drops = (int(status[k]) for k in
                                 ('acceptedTimingReservations', 'observedTimingEvents', 'queueDrops'))
    require(accepted == observed == sum(counts.values()) and observed > 0 and drops >= 0,
            'Accepted/observed/summary conservation failed')
    require(0 <= int(status['rawTimingRowsOmitted']) <= observed, 'Invalid bounded-prefix omission count')
    require((status['incomplete'] == 'true') == (drops > 0), 'Drops/incomplete correspondence failed')
    minimum = measurement['operations'] * (2 if measurement['kind'] == 'allocation' else 1)
    require(accepted + drops >= minimum, 'Warmup-inclusive timings+drop count below measured operation calls')
    require(all(count + drops >= measurement['operations'] for count in counts.values()),
            'Per-subject timings plus all drops below measured operation calls')
    return {'status': status, 'timingsBySelectedMethod': counts,
            'minimumMeasuredSelectedCalls': minimum,
            'warmupInclusiveAcceptedPlusDrops': accepted + drops,
            'conservationScope': 'Counts include warmup; operations exclude warmup. Lower bound only, not exact equality.'}


def validate_outputs(output, manifest):
    environment = json.loads((output / 'environment.json').read_text())
    order = json.loads((output / 'run-order.json').read_text())
    summary = json.loads((output / 'summary.json').read_text())
    expected = {(w, p, m) for w in ('cpu', 'allocation', 'contention', 'virtual')
                for p in range(7) for m in ('baseline', 'jfr', 'instrumented')}
    require(len(order) == 84 and len({x['id'] for x in order}) == 84
            and {(x['workload'], x['pair'], x['mode']) for x in order} == expected,
            'Full84/7paired/4workload/3mode protocol differs')
    require(environment['pairs'] == 7 and environment['warmupSeconds'] == 30
            and environment['measurementSeconds'] == 60 and environment['acceptanceEligible']
            and environment['seed'] == 42908
            and environment['sourceFingerprint'] == manifest['buildInputFingerprint']
            and environment['agentJarSha256'] == manifest['agentSha256']
            and environment['collectionConfiguration'] == CONFIGURATION,
            'Benchmark environment differs from immutable reference protocol')
    measurements, observer_status = [], []
    for item in order:
        run = output / item['id']
        measurement = json.loads((run / 'measurement.json').read_text())
        require(item['exitCode'] == 0 and measurement['warmupSeconds'] == 30
                and measurement['requestedMeasurementSeconds'] == 60
                and measurement['actualMeasurementSeconds'] >= 60
                and measurement['collectionConfiguration'] == CONFIGURATION
                and measurement['kind'] == item['workload'] and measurement['mode'] == item['mode']
                and not measurement['regressed'] and measurement['operations'] > 0,
                'Trial protocol differs: ' + item['id'])
        if item['mode'] != 'baseline':
            settings = json.loads((run / 'recording.jfr.settings.json').read_text())
            expected_settings = {'jdk.ExecutionSample#period': '20 ms', 'jdk.ExecutionSample#stackTrace': 'true',
                                 'jdk.ObjectAllocationSample#throttle': '50/s', 'jdk.ObjectAllocationSample#stackTrace': 'true',
                                 'jdk.JavaMonitorEnter#threshold': '2 ms', 'jdk.JavaMonitorEnter#stackTrace': 'true',
                                 'jdk.ThreadPark#threshold': '2 ms', 'jdk.ThreadPark#stackTrace': 'false'}
            require(all(settings.get(k) == v for k, v in expected_settings.items())
                    and all(settings.get(k + '#enabled') == 'true' for k in
                            ('jdk.ExecutionSample', 'jdk.ObjectAllocationSample', 'jdk.JavaMonitorEnter',
                             'jdk.ThreadPark', 'jdk.GarbageCollection', 'jdk.DataLoss')),
                    'Actual producer settings differ: ' + item['id'])
        if item['mode'] == 'instrumented':
            observer_status.append({'id': item['id'], **validate_instrumented(run, measurement)})
        measurements.append({**{k: item[k] for k in ('id', 'pair', 'mode', 'workload')}, 'measurement': measurement})
    atomic_json(INPUTS / 'raw-measurements.json', measurements)
    atomic_json(INPUTS / 'observer-status-summary.json', observer_status)
    require(set(summary) == {'cpu', 'allocation', 'contention', 'virtual'}
            and all(set(modes) == {'jfr', 'instrumented'} for modes in summary.values()),
            'Overhead comparison set differs')
    checks = [{'workload': w, 'mode': mode, 'throughputPass': v['throughputGatePass'], 'p99Pass': v['p99GatePass']}
              for w, modes in summary.items() for mode, v in modes.items()]
    return {'fullProtocolValidated': True, 'overheadGateChecks': checks,
            'allOverheadGatesPass': len(checks) == 8 and all(c['throughputPass'] and c['p99Pass'] for c in checks)}


def main():
    manifest_path = INPUTS / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    reservation = os.environ.get('THREADLENS_REFERENCE_RESERVATION', '').strip()
    if not reservation or reservation.startswith('PENDING'):
        raise SystemExit('Explicit coordinator quiet-host reservation required')
    if 'executionStartedUtc' in manifest:
        raise SystemExit('Refusing to overwrite an earlier attempted reference study')
    output = Path(manifest['command'][-2])
    if output.exists() and any(output.iterdir()):
        raise SystemExit('Reference output must be fresh; preserve previous trials')
    cancellation = Cancellation()
    cancellation.install()
    runner = None
    exit_code = 2
    manifest.update({'exclusiveHostReservation': reservation, 'executionStartedUtc': utc(),
                     'state': 'PREFLIGHT', 'allOverheadGatesPass': False,
                     'totalTimeoutSeconds': TOTAL_TIMEOUT_SECONDS})
    try:
        atomic_json(manifest_path, manifest)
        for artifact in manifest['runtimeArtifacts']:
            require(sha(artifact['runtimeCopy']) == artifact['sha256'], 'Runtime artifact changed')
        require(sha(INPUTS / 'threadlens-agent.jar') == manifest['agentSha256'], 'Agent changed')
        require(sha(INPUTS / 'source.tar.gz') == manifest['sourceArchiveSha256'], 'Source archive changed')
        require(sha(INPUTS / 'functional-certificate.json') == manifest['functionalCertificateSha256'], 'Certificate changed')
        for proof in manifest['validationProofs']:
            require(sha(proof['copy']) == proof['sha256'], 'Validation proof changed')
        for name, expected_hash in manifest['executionHelpers'].items():
            require(sha(Path(__file__).parent / name) == expected_hash, 'Execution helper changed: ' + name)
        java = Path(manifest['command'][0])
        require(sha(java.parent.parent / 'release') == manifest['jdkReleaseSha256'], 'Prepared JDK metadata changed')
        manifest['jdkReleaseObserved'] = (java.parent.parent / 'release').read_text()
        for process in Path('/proc').iterdir():
            if not process.name.isdigit():
                continue
            try:
                args = (process / 'cmdline').read_bytes().split(b'\0')
            except OSError:
                continue
            require(not any(c in args for c in (b'org.codehaus.plexus.classworlds.launcher.Launcher',
                                               b'org.apache.maven.wrapper.MavenWrapperMain')), 'Active Maven violates quiet reservation')
        require(not cancellation.signal_number, 'Cancelled before benchmark launch')
        def started(identity_record):
            manifest['benchmarkProcessPid'] = identity_record['pid']
            manifest['ownedProcessLaunch'] = identity_record
            atomic_json(manifest_path, manifest)

        runner = OwnedProcess(manifest['command'], INPUTS / 'launcher.log', TOTAL_TIMEOUT_SECONDS, cancellation, started)
        manifest['state'] = 'RUNNING'
        atomic_json(manifest_path, manifest)
        result = runner.run()
        manifest['launcherExitCode'] = result.get('exitCode')
        if result['outcome'] != 'EXITED' or result['exitCode'] != 0:
            exit_code = result.get('exitCode', 2)
            raise RuntimeError('Benchmark did not finish successfully: ' + repr(result))
        manifest.update(validate_outputs(output, manifest))
        require(not cancellation.signal_number, 'Cancelled during final validation')
        exit_code = 0 if manifest['allOverheadGatesPass'] else 3
        manifest['state'] = 'PASS' if exit_code == 0 else 'OVERHEAD_FAIL'
    except BaseException as error:
        manifest.update({'state': 'FAILED', 'failure': type(error).__name__ + ': ' + str(error)})
        if cancellation.signal_number:
            exit_code = 128 + cancellation.signal_number
        elif exit_code == 0:
            exit_code = 2
        print(manifest['failure'], file=sys.stderr)
    finally:
        if runner is not None:
            manifest['ownedProcess'] = runner.result
            if not runner.result.get('cleanupComplete', False):
                manifest['state'], exit_code = 'FAILED_CLEANUP', 2
        if cancellation.signal_number:
            manifest['cancellationSignal'] = cancellation.signal_number
            manifest['state'], exit_code = 'CANCELLED', 128 + cancellation.signal_number
        if exit_code < 0:
            exit_code = 128 - exit_code
        manifest.update({'completedUtc': utc(), 'executorExitCode': exit_code})
        try:
            atomic_json(manifest_path, manifest)
        finally:
            cancellation.restore()
    return exit_code


if __name__ == '__main__':
    raise SystemExit(main())
