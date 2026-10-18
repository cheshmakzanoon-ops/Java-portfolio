#!/usr/bin/env python3
"""Coordinator-only quiet reservation orchestration. No work occurs without --execute-quiet."""
import argparse
import collections
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / 'projects/10-routeweaver'
WORK = ROOT / '.work/A10'
RUNTIME = PROJECT / '.runtime'
EXPECTED_SOURCE = 'd5964723baa5e51619d4d85316f02ae3ccc370726ad7eb3b41617a200ced88d0'


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_json(path, value):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')
    temporary.replace(path)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def proc_session(pid):
    try:
        raw = Path('/proc', str(pid), 'stat').read_text()
        return int(raw[raw.rfind(')') + 2:].split()[3])
    except (FileNotFoundError, ProcessLookupError, PermissionError, ValueError):
        return None


def session_members(sid):
    return [int(p.name) for p in Path('/proc').iterdir()
            if p.name.isdigit() and proc_session(int(p.name)) == sid]


def terminate_session(sid):
    # timeout(1) creates another process group, but retains this owned session.
    # Address every member so detached process groups cannot outlive a failed driver.
    for sig, delay in ((signal.SIGTERM, 3), (signal.SIGKILL, 1)):
        members = session_members(sid)
        if not members:
            return
        for pid in members:
            try:
                os.kill(pid, sig)
            except ProcessLookupError:
                pass
        until = time.monotonic() + delay
        while time.monotonic() < until and session_members(sid):
            time.sleep(0.1)


def java_processes():
    result = []
    for p in Path('/proc').iterdir():
        if not p.name.isdigit():
            continue
        try:
            if Path(os.readlink(p / 'exe')).name == 'java':
                result.append({'pid': int(p.name), 'command': (p / 'cmdline').read_bytes().replace(b'\0', b' ').decode(errors='replace')})
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            pass
    return result


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(0.2)
        return sock.connect_ex(('127.0.0.1', port)) == 0


def validate_results(directory, expected_source):
    jmh = json.loads((directory / 'validation-jmh.json').read_text())
    actual = {(r['benchmark'].rsplit('.', 1)[-1], int(r['params']['jobs'])) for r in jmh}
    required = {(method, jobs) for method in ('fullReferenceValidation', 'immutableProblemImportValidation') for jobs in (100, 250, 500)}
    assert len(jmh) == 6 and actual == required, 'Incomplete JMH method/size matrix'
    for row in jmh:
        assert row['forks'] == 3 and row['warmupIterations'] == 3 and row['measurementIterations'] == 3, row
        assert row['mode'] == 'thrpt' and row['primaryMetric']['scoreUnit'] == 'ops/s', row
        assert row['warmupTime'] == '1 s' and row['measurementTime'] == '1 s', row
        assert len(row['primaryMetric']['rawData']) == 3
        assert all(len(fork) == 3 for fork in row['primaryMetric']['rawData'])
        assert math.isfinite(row['primaryMetric']['score']) and row['primaryMetric']['score'] > 0
        assert 'gc.alloc.rate.norm' in row['secondaryMetrics'], 'Missing GC profiler evidence'
    corpus = json.loads((directory / 'corpus-raw.json').read_text())
    assert len(corpus) == 30 and {(r['instance'], r['run']) for r in corpus} == {(i, r) for i in range(10) for r in range(3)}, 'Incomplete 10 x 3 system corpus'
    sizes = [100] * 3 + [250] * 3 + [500] * 3 + [100]
    vehicles = [5] * 3 + [10] * 3 + [25] * 3 + [5]
    durations = []
    for row in corpus:
        index = row['instance']
        assert (row['jobs'], row['vehicles']) == (sizes[index], vehicles[index])
        assert row['seed'] == 4200 + index and row['solverSeed'] == 42
        assert row['solverBudgetMillis'] == 30000 and row['solverStepLimit'] == 100000
        assert row['source'] == row['serviceSource'] == expected_source, 'Source identity mismatch'
        assert row['feasibility'] in ('FULLY_FEASIBLE', 'PARTIAL_FEASIBLE')
        assert len(row['inputHash']) == 64 and row['scoreTrace']
        assert row['optimized']['hard'] == 0 and row['baseline']['hard'] == 0
        key = lambda score: (score['hard'], score['unassignedPriority'], score['operatingCost'] + score['disruption'])
        assert key(row['optimized']) <= key(row['baseline']), 'Degraded same-input incumbent'
        assert all(point['score']['hard'] == 0 for point in row['scoreTrace'])
        if 'operatingCostImprovementFraction' in row:
            assert row['sameAssignedDemand'], 'Scalar cost comparison changed assigned demand'
        assert row['queueLatencyNanos'] >= 0 and row['baselineNanos'] >= 0 and row['durableSolveNanos'] > 0
        assert row['algorithmVersion'] and row['objectiveVersion'] and row['stopCondition']
        durations.append(row['durableSolveNanos'] / 1e9)
    samples = [json.loads(line) for line in (directory / 'server-resources.jsonl').read_text().splitlines() if line.strip()]
    assert samples and all(row['rssBytes'] > 0 and row['threads'] > 0 for row in samples)
    for filename in ('environment.txt', 'validation-jmh.log', 'gc.log', 'server-gc.log', 'server-process-status.txt'):
        assert (directory / filename).stat().st_size > 0, filename
    return {'jmhRows': len(jmh), 'jmhForksPerRow': 3, 'systemRows': len(corpus), 'resourceSamples': len(samples), 'durableSolveSecondsMin': min(durations), 'durableSolveSecondsMax': max(durations), 'stopConditions': dict(collections.Counter(r['stopCondition'] for r in corpus)), 'improvableRuns': [{'run': r['run'], 'baseline': r['baseline'], 'optimized': r['optimized'], 'sameAssignedDemand': r['sameAssignedDemand'], 'operatingCostImprovementFraction': r.get('operatingCostImprovementFraction')} for r in corpus if r['instance'] == 9], 'interpretation': 'Configured30-second budgets; actual durations and early stops retained. Three system samples are not a p95/p99 estimate. Full driver independently validates every persisted baseline/result.'}


class Runner:
    def __init__(self, output, min_free_mib):
        self.output = output
        self.floor = min_free_mib * 1024 * 1024
        self.active = None
        self.stack_attempted = False
        self.service_pid = None
        self.state = {'schemaVersion': 1, 'project': 'routeweaver', 'sourceRevision': 'working-tree-sha256:' + EXPECTED_SOURCE, 'startedAtUtc': utc(), 'status': 'RUNNING', 'operations': [], 'diskSamples': [], 'output': str(output), 'protocol': 'Existing source-tested no-Maven preflight/up/reset/benchmark.sh/down; both JMH methods with3forks and all10instances x3 actual30000ms-budget system solves; no excluded system warmup'}
        self.env = dict(os.environ, ROUTEWEAVER_PORT='19000', ROUTEWEAVER_DB_PORT='19001')

    def save(self):
        write_json(self.output / 'operations.json', self.state)

    def disk(self, initial=False):
        usage = shutil.disk_usage(ROOT)
        temp = shutil.disk_usage('/tmp')
        self.state['diskSamples'].append({'atUtc': utc(), 'workspaceFreeBytes': usage.free, 'tmpFreeBytes': temp.free})
        required = self.floor if initial else 512 * 1024 * 1024
        if usage.free < required or temp.free < 256 * 1024 * 1024:
            raise RuntimeError('Insufficient free disk; nothing is deleted automatically. Workspace need/free=' + str(required) + '/' + str(usage.free) + ', tmp free=' + str(temp.free))

    def phase(self, name, command, timeout_seconds, measurement=False, cleanup=False):
        log = self.output / (name + '.log')
        record = {'phase': name, 'command': command, 'startedAtUtc': utc(), 'timeoutSeconds': timeout_seconds, 'rawLog': str(log)}
        self.state['operations'].append(record)
        self.save()
        print(utc(), name, 'START', flush=True)
        start = time.monotonic()
        process = None
        try:
            with log.open('xb') as stream:
                process = subprocess.Popen(command, cwd=ROOT, env=self.env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
                self.active = process
                next_disk = time.monotonic()
                while process.poll() is None:
                    if time.monotonic() - start > timeout_seconds:
                        raise TimeoutError(name + ' timed out')
                    if not cleanup and time.monotonic() >= next_disk:
                        self.disk()
                        self.save()
                        next_disk = time.monotonic() + 5
                    time.sleep(0.5)
                record['exitCode'] = process.returncode
                if process.returncode:
                    raise RuntimeError(name + ' failed with exit ' + str(process.returncode))
        except BaseException as error:
            if process is not None:
                terminate_session(process.pid)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
                record['exitCode'] = process.returncode
            record['error'] = repr(error)
            raise
        finally:
            if measurement and process is not None:
                terminate_session(process.pid)
            self.active = None
            record['elapsedSeconds'] = time.monotonic() - start
            record['finishedAtUtc'] = utc()
            self.save()
            print(utc(), name, 'END', record.get('exitCode'), flush=True)

    def preserve_logs(self, subdirectory):
        target = self.output / subdirectory
        target.mkdir(exist_ok=True)
        for pattern in ('service.log*', 'service-gc.log*', 'service.pid', 'running-source', 'build-stamp'):
            for path in RUNTIME.glob(pattern):
                if path.is_file():
                    shutil.copyfile(path, target / path.name)

    def run(self):
        failure = None
        try:
            self.disk(initial=True)
            assert (RUNTIME / 'build-stamp').read_text().strip() == EXPECTED_SOURCE, 'Source stamp changed'
            observed_java = java_processes()
            self.state['initialJavaProcesses'] = observed_java
            assert not observed_java, 'Other Java processes exist; coordinator must drain the quiet host first'
            assert not port_open(19000) and not port_open(19001), 'RouteWeaver ports must be closed for a fresh worker'
            self.phase('00-docker-idle', ['docker', 'ps', '--format', '{{.ID}} {{.Names}}'], 15)
            assert not (self.output / '00-docker-idle.log').read_text().strip(), 'Other containers exist; do not call this a quiet reference run'
            self.preserve_logs('before-start')
            shutil.copyfile(PROJECT / 'evidence/artifacts-current.json', self.output / 'artifacts-used.json')
            self.phase('01-artifact-preflight', ['bash', str(WORK / 'preflight-no-maven.sh')], 90)
            for name in ('final-artifact-fixture.json', 'final-jmh-discovery.log'):
                shutil.copyfile(WORK / name, self.output / name)
            self.stack_attempted = True
            self.phase('02-up', ['bash', str(PROJECT / 'scripts/up.sh')], 180)
            self.service_pid = int((RUNTIME / 'service.pid').read_text().strip())
            self.state['servicePid'] = self.service_pid
            assert (RUNTIME / 'running-source').read_text().strip() == EXPECTED_SOURCE
            assert [r['pid'] for r in java_processes()] == [self.service_pid], 'Unrelated Java process appeared before measurement'
            self.phase('03-reset', ['bash', str(PROJECT / 'scripts/reset-demo.sh')], 45)
            measurement = self.output / 'measurement'
            self.phase('04-full-jmh-and-corpus', ['bash', str(PROJECT / 'scripts/benchmark.sh'), str(measurement)], 2100, measurement=True)
            self.state['measurementValidation'] = validate_results(measurement, EXPECTED_SOURCE)
            write_json(self.output / 'validated-summary.json', self.state['measurementValidation'])
            self.state['status'] = 'PASS_PENDING_CLEANUP'
        except BaseException as error:
            failure = error
            self.state.update(status='FAIL', error=repr(error))
        finally:
            # A second TERM must not interrupt cleanup after a first interruption.
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            if self.active is not None:
                terminate_session(self.active.pid)
            try:
                self.preserve_logs('before-down')
            except BaseException as preserve_error:
                self.state['beforeDownPreservationError'] = repr(preserve_error)
                failure = failure or preserve_error
            if self.stack_attempted:
                if self.service_pid is None and (RUNTIME / 'service.pid').exists():
                    try:
                        self.service_pid = int((RUNTIME / 'service.pid').read_text().strip())
                    except (OSError, ValueError) as pid_error:
                        self.state['pidReadError'] = repr(pid_error)
                try:
                    self.phase('05-down', ['bash', str(PROJECT / 'scripts/down.sh')], 90, cleanup=True)
                except BaseException as cleanup_error:
                    self.state['cleanupError'] = repr(cleanup_error)
                    failure = failure or cleanup_error
                    # Even a full disk/logging failure must not bypass scoped Compose cleanup.
                    try:
                        emergency = subprocess.run(['docker', 'compose', '-f', str(PROJECT / 'infra/compose.yaml'), 'down', '--timeout', '20'], cwd=ROOT, env=self.env, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=90, start_new_session=True)
                        self.state['emergencyComposeDownExit'] = emergency.returncode
                    except BaseException as emergency_error:
                        self.state['emergencyComposeDownError'] = repr(emergency_error)
            # down.sh signals only this service; await it and fence a lingering owned JVM.
            if self.stack_attempted and self.service_pid is not None:
                for attempt in range(30):
                    if not any(r['pid'] == self.service_pid for r in java_processes()):
                        break
                    time.sleep(0.5)
                owned = [r for r in java_processes() if r['pid'] == self.service_pid and str(PROJECT / 'routeweaver-service/target/routeweaver-service-1.0.0-SNAPSHOT-exec.jar') in r['command']]
                if owned:
                    try:
                        os.kill(self.service_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        self.state['serviceStoppedBeforeForcedKill'] = True
                    except OSError as kill_error:
                        self.state['serviceKillError'] = repr(kill_error)
                        failure = failure or kill_error
                    else:
                        self.state['serviceRequiredKillAfterDown'] = True
                        time.sleep(0.5)
            if self.stack_attempted:
                try:
                    self.phase('06-confirm-containers', ['docker', 'ps', '--filter', 'label=com.docker.compose.project=java-portfolio-routeweaver', '--format', '{{.ID}} {{.Names}}'], 15, cleanup=True)
                    if (self.output / '06-confirm-containers.log').read_text().strip():
                        self.phase('07-fallback-scoped-down', ['docker', 'compose', '-f', str(PROJECT / 'infra/compose.yaml'), 'down', '--timeout', '20'], 90, cleanup=True)
                        self.phase('08-confirm-fallback', ['docker', 'ps', '--filter', 'label=com.docker.compose.project=java-portfolio-routeweaver', '--format', '{{.ID}} {{.Names}}'], 15, cleanup=True)
                        if (self.output / '08-confirm-fallback.log').read_text().strip():
                            raise RuntimeError('Owned Compose containers remain after fallback shutdown')
                except BaseException as verify_cleanup_error:
                    self.state['containerCleanupError'] = repr(verify_cleanup_error)
                    failure = failure or verify_cleanup_error
            if self.stack_attempted and (port_open(19000) or port_open(19001)):
                self.state['cleanupError'] = 'Owned ports remain open after scoped shutdown'
                failure = failure or RuntimeError(self.state['cleanupError'])
            try:
                self.preserve_logs('after-down')
            except BaseException as copy_error:
                self.state['logPreservationError'] = repr(copy_error)
                failure = failure or copy_error
            self.state['finishedAtUtc'] = utc()
            self.state['status'] = 'FAIL' if failure else 'PASS'
            self.save()
            manifest = {str(p.relative_to(self.output)): {'bytes': p.stat().st_size, 'sha256': sha(p)} for p in sorted(self.output.rglob('*')) if p.is_file() and p.name != 'raw-manifest.json'}
            write_json(self.output / 'raw-manifest.json', manifest)
        return 1 if failure else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute-quiet', action='store_true', help='Coordinator explicitly holds the full quiet host reservation')
    parser.add_argument('--output', type=Path, help='Fresh directory inside .work/A10; existing directories are refused')
    parser.add_argument('--min-free-mib', type=int, default=4096, help='Initial workspace free-space minimum; default4GiB. No files are deleted.')
    args = parser.parse_args()
    if not args.execute_quiet:
        parser.error('No runtime work without --execute-quiet and the coordinator quiet reservation')
    output = (args.output or WORK / 'quiet' / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6])).resolve()
    if not output.is_relative_to(WORK.resolve()) or args.min_free_mib < 1024:
        parser.error('Output must be within .work/A10 and initial minimum must be at least1024MiB')
    output.mkdir(parents=True, exist_ok=False)
    def interrupted(signum, frame):
        raise InterruptedError('Coordinator signal ' + str(signum))
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    runner = Runner(output, args.min_free_mib)
    runner.save()
    print('Fresh raw output:', output, flush=True)
    code = runner.run()
    print('Final status:', runner.state['status'], '; operations:', output / 'operations.json', flush=True)
    return code


if __name__ == '__main__':
    sys.exit(main())
