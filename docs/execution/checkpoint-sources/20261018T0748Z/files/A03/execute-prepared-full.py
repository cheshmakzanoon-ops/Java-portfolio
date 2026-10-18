#!/usr/bin/env python3
"""Run the frozen 18-scenario QuorumKV study inside the coordinator reservation.

No compilation, retries, source edits, deletion, or reuse of an old output directory.
Linux /proc is required for lock ownership, process attribution and bounded cleanup.
"""
import csv
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
import tempfile
import time

ROOT = Path('/workspace/Java-portfolio')
WORK = ROOT / '.work/A03'
PROJECT = ROOT / 'projects/03-quorumkv'
INPUTS = WORK / 'full-run-inputs'
SOURCE = 'ab5534dd111bf63b606b5da53c2cd870d6d0bf5c3549e8f6db2cf4e509a89ca9'
PORT = 18300
MAX_SECONDS = 18 * 46 * 60 + 600
NODE_MAIN = 'io.github.cheshmakzanoon.portfolio.quorumkv.NodeMain'


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def proc(pid):
    try:
        directory = Path('/proc') / str(pid)
        text = (directory / 'stat').read_text()
        fields = text[text.rfind(')') + 2:].split()
        argv = (directory / 'cmdline').read_bytes().split(b'\0')
        argv = [part.decode(errors='replace') for part in argv if part]
        return {'pid': int(pid), 'state': fields[0], 'ppid': int(fields[1]),
                'pgrp': int(fields[2]), 'startTicks': int(fields[19]),
                'userTicks': int(fields[11]), 'systemTicks': int(fields[12]),
                'rssBytes': int(fields[21]) * os.sysconf('SC_PAGE_SIZE'),
                'argv': argv, 'comm': (directory / 'comm').read_text().strip()}
    except (OSError, ValueError, IndexError):
        return None


def processes():
    result = []
    for path in Path('/proc').iterdir():
        if path.name.isdigit():
            item = proc(path.name)
            if item and item['state'] not in ('Z', 'X'):
                result.append(item)
    return result


def reservation():
    ancestor = proc(os.getppid())
    while ancestor:
        for index, arg in enumerate(ancestor['argv']):
            if arg.endswith('/run-quiet.py') or arg == '.work/coordinator/run-quiet.py':
                output = Path(ancestor['argv'][index + 1])
                if not output.is_absolute():
                    output = ROOT / output
                record = json.loads((output / 'reservation.json').read_text())
                if record.get('otherJavaProcessesAtReservation') != [] or record.get('otherApplicationContainersAtReservation') != []:
                    raise RuntimeError('Coordinator reservation did not observe a quiet host')
                if 'completedAtUtc' in record:
                    raise RuntimeError('Reservation already completed')
                locks = Path('/proc/locks').read_text().splitlines()
                for slot in range(3):
                    lock = ROOT / '.work/build-slots' / f'{slot}.lock'
                    st = lock.stat()
                    matches = False
                    for line in locks:
                        parts = line.split()
                        if len(parts) < 6 or parts[1:4] != ['FLOCK', 'ADVISORY', 'WRITE']:
                            continue
                        device = parts[5].split(':')
                        if len(device) == 3 and int(parts[4]) == ancestor['pid'] and (
                                int(device[0], 16), int(device[1], 16), int(device[2])) == (
                                    os.major(st.st_dev), os.minor(st.st_dev), st.st_ino):
                            matches = True
                    if not matches:
                        raise RuntimeError(f'Coordinator does not hold build slot {slot}')
                return output, ancestor['pid'], record
        ancestor = proc(ancestor['ppid']) if ancestor['ppid'] else None
    raise RuntimeError('Must be launched through .work/coordinator/run-quiet.py')


def validate_inputs():
    frozen = json.loads((INPUTS / 'prepared.json').read_text())
    if frozen['sourceFingerprint'] != SOURCE or frozen['runnerSha256'] != digest(Path(__file__)):
        raise RuntimeError('Prepared runner receipt changed')
    for name, expected in frozen['inputSha256'].items():
        if digest(INPUTS / name) != expected:
            raise RuntimeError(f'Frozen input changed: {name}')
    target = PROJECT / 'raft-verification/target'
    for name in ('verified-artifacts.json', 'verified-source.sha256', 'runtime-classpath.txt'):
        if (target / name).read_bytes() != (INPUTS / name).read_bytes():
            raise RuntimeError(f'Runtime receipt/classpath differs from frozen input: {name}')
    actual = subprocess.check_output(['python3', str(PROJECT / 'scripts/source-fingerprint.py')], text=True).strip()
    if actual != SOURCE:
        raise RuntimeError(f'Executable inputs changed: {actual}')
    subprocess.run(['python3', str(PROJECT / 'scripts/runtime-artifacts.py'), 'check'], check=True)
    return frozen


def assert_ports_free():
    sockets = []
    try:
        for port in range(PORT, PORT + 3):
            handle = socket.socket()
            sockets.append(handle)
            handle.bind(('127.0.0.1', port))
    finally:
        for handle in sockets:
            handle.close()


def owned_nodes(raw):
    result = []
    for item in processes():
        args = item['argv']
        if NODE_MAIN not in args:
            continue
        at = args.index(NODE_MAIN)
        if len(args) < at + 5:
            continue
        data, tls = Path(args[at + 2]), Path(args[at + 3])
        if data.is_relative_to(raw) and tls == data.parent / 'tls' and args[at + 4] == str(PORT):
            result.append(item)
    return result


def cleanup(child, raw):
    """Always stop the isolated driver group and exact-path member fallbacks."""
    actions = []
    for sig, wait in ((signal.SIGTERM, 8), (signal.SIGKILL, 5)):
        group = [p for p in processes() if child is not None and p['pgrp'] == child.pid]
        nodes = owned_nodes(raw)
        if not group and not nodes:
            break
        if group:
            try:
                os.killpg(child.pid, sig)
                actions.append({'group': child.pid, 'signal': sig.name})
            except ProcessLookupError:
                pass
        for item in nodes:
            current = proc(item['pid'])
            if current and current['startTicks'] == item['startTicks'] and current['state'] not in ('Z', 'X'):
                try:
                    os.kill(item['pid'], sig)
                    actions.append({'pid': item['pid'], 'signal': sig.name})
                except ProcessLookupError:
                    pass
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline:
            if child is not None:
                child.poll()
            if not any(p['pgrp'] == child.pid for p in processes()) and not owned_nodes(raw):
                break
            time.sleep(.1)
    remaining = owned_nodes(raw)
    if child is not None:
        remaining += [p for p in processes() if p['pgrp'] == child.pid and p['pid'] not in {n['pid'] for n in remaining}]
        try:
            child.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
    return {'actions': actions, 'remainingOwnedProcesses': remaining}


def assert_results(raw, resources_path):
    with (raw / 'workload.csv').open() as stream:
        rows = list(csv.DictReader(stream))
    expected = {(run, profile, clients) for run in (1, 2, 3)
                for profile in ('read-heavy', 'mixed') for clients in (1, 8, 32)}
    keys = [(int(r['run']), r['profile'], int(r['clients'])) for r in rows]
    if len(rows) != 18 or set(keys) != expected:
        raise RuntimeError('Expected exactly 18 distinct full scenarios')
    environment = (raw / 'environment.txt').read_text()
    if f'sourceFingerprint={SOURCE}\n' not in environment:
        raise RuntimeError('Driver reported different executable inputs')
    simultaneous_members = set()
    with resources_path.open() as stream:
        for line in stream:
            sample = json.loads(line)
            groups = {}
            for item in sample['processes']:
                args = item['argv']
                if NODE_MAIN in args:
                    at = args.index(NODE_MAIN)
                    if len(args) >= at + 5:
                        data = Path(args[at + 2])
                        if data.is_relative_to(raw):
                            groups.setdefault(data.parent.name, set()).add(args[at + 1])
            simultaneous_members.update(name for name, ids in groups.items() if ids == {'0', '1', '2'})
    for row, (run, profile, clients) in zip(rows, keys):
        if int(row['operations']) != 100000 or int(row['payloadBytes']) != 1024 or int(row['errors']) != 0:
            raise RuntimeError(f'Workload/error assertion failed: {row}')
        for key, value in row.items():
            if key == 'profile':
                continue
            if not math.isfinite(float(value)) or float(value) < 0:
                raise RuntimeError(f'Invalid metric {key}={value}')
        if not (0 < float(row['p50Micros']) <= float(row['p95Micros']) <= float(row['p99Micros'])):
            raise RuntimeError('Invalid latency ordering')
        if int(row['lagSamples']) == 0 or float(row['elapsedSeconds']) <= 0:
            raise RuntimeError('No successful replication samples or elapsed time')
        if not math.isclose(float(row['opsPerSecond']) * float(row['elapsedSeconds']), 100000, rel_tol=0.0001):
            raise RuntimeError('Throughput does not match completed operations')
        scenario = raw / f'run-{run}-{profile}-{clients}'
        if scenario.name not in simultaneous_members:
            raise RuntimeError(f'No resource sample observed all3 real members: {scenario}')
        for node in range(3):
            for suffix in ('.log', '-gc.log', '.jfr'):
                artifact = scenario / f'node-{node}{suffix}'
                if not artifact.is_file() or artifact.stat().st_size == 0:
                    raise RuntimeError(f'Missing member diagnostic: {artifact}')
            if not (scenario / f'status-{node}.txt').is_file():
                raise RuntimeError('Missing member status')
        for name in ('server.crt', 'server.key', 'auth.properties'):
            if not (scenario / 'tls' / name).is_file():
                raise RuntimeError(f'Missing independently generated TLS input: {scenario}/{name}')
    if not (raw / 'client.jfr').is_file() or (raw / 'client.jfr').stat().st_size == 0:
        raise RuntimeError('Missing client JFR')
    return rows


def main():
    if len(sys.argv) != 1:
        raise SystemExit('No options: exactly18 full100k scenarios; use coordinator reservation')
    reservation_dir, reservation_pid, reservation_record = reservation()
    reservation_start = proc(reservation_pid)['startTicks']
    # Exclusive, fresh, owner-local evidence directory; all prior full/smoke paths retained.
    output = Path(tempfile.mkdtemp(prefix='full-study-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ-'), dir=WORK))
    raw = output / 'raw'
    raw.mkdir()
    shutil.copytree(INPUTS, output / 'inputs')
    state = {'schemaVersion': 1, 'startedAtUtc': utc(), 'status': 'RUNNING',
             'sourceFingerprint': SOURCE, 'outputDirectory': str(output.relative_to(ROOT)),
             'reservationDirectory': str(reservation_dir.relative_to(ROOT)), 'reservationPid': reservation_pid,
             'expectedScenarios': 18, 'operationsPerScenario': 100000, 'payloadBytes': 1024,
             'seeds': '42+client identity', 'clientCounts': [1, 8, 32],
             'profiles': ['read-heavy:90%GET/10%PUT', 'mixed:50%GET/50%PUT'],
             'independentRuns': 3, 'transport': 'three actual member JVMs; authenticated TLSv1.3',
             'measurement': 'closed-loop; coordinated omission; sampler/JFR overhead included',
             'history': json.loads((INPUTS / 'prepared.json').read_text())['preservedHistory'],
             'exitCode': None, 'acceptanceEligible': False}
    write_json(output / 'result.json', state)
    print(f'QuorumKV full study output: {output}', flush=True)
    child = None
    interruption = []
    def interrupted(sig, frame):
        interruption.append(sig)
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, interrupted)
    failure = None
    driver_status = None
    rows = None
    cleanup_result = None
    try:
        validate_inputs()
        if any(os.environ.get(key) for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')):
            raise RuntimeError('Ambient JVM option injection changes the prepared measurement flags')
        if any(p['comm'] == 'java' for p in processes()):
            raise RuntimeError('Foreign live Java process exists before study')
        assert_ports_free()
        free = shutil.disk_usage(ROOT).free
        state['freeDiskBytesAtStart'] = free
        if free < 3 * 1024 ** 3:
            raise RuntimeError('Less than3GiB free; preserve data and provision space before measurement')
        env = dict(os.environ, QUORUMKV_PREPARED='1', QUORUMKV_RECORD='true', QUORUMKV_PORT=str(PORT))
        command = ['bash', '-c', 'set -euo pipefail; source "$1/scripts/common.sh"; run_tool benchmark "$2" "$3" 100000 3', '_', str(PROJECT), str(raw), str(PORT)]
        state['command'] = command
        write_json(output / 'result.json', state)
        start = time.monotonic()
        with (output / 'driver.log').open('wb') as log, (output / 'resources.jsonl').open('w') as resources:
            child = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            while child.poll() is None:
                if interruption:
                    raise RuntimeError(f'Interrupted by signal {interruption[0]}')
                if time.monotonic() - start > MAX_SECONDS:
                    raise RuntimeError('Full study exceeded aggregate50280-second bound')
                owner = proc(reservation_pid)
                if not owner or owner['startTicks'] != reservation_start or owner['state'] in ('Z', 'X'):
                    raise RuntimeError('Coordinator reservation owner disappeared')
                observed = processes()
                foreign = [p for p in observed if p['comm'] == 'java' and p['pgrp'] != child.pid]
                if foreign:
                    write_json(output / 'interference.json', foreign)
                    raise RuntimeError('Foreign Java appeared during study; do not accept timing')
                owned = [p for p in observed if p['pgrp'] == child.pid]
                resources.write(json.dumps({'utc': utc(), 'elapsedSeconds': time.monotonic() - start,
                    'ticksPerSecond': os.sysconf('SC_CLK_TCK'), 'processes': owned}) + '\n')
                resources.flush()
                if shutil.disk_usage(ROOT).free < 512 * 1024 ** 2:
                    raise RuntimeError('Free disk below512MiB: stopping without deleting evidence')
                time.sleep(1)
            driver_status = child.returncode
            state['driverElapsedSeconds'] = time.monotonic() - start
        if driver_status != 0:
            raise RuntimeError(f'Driver exited {driver_status}; partial full rows remain preserved')
        rows = assert_results(raw, output / 'resources.jsonl')
        validate_inputs()
    except Exception as error:
        failure = f'{type(error).__name__}: {error}'
    finally:
        cleanup_result = cleanup(child, raw)
    if cleanup_result['remainingOwnedProcesses']:
        failure = (failure + '; ' if failure else '') + 'Owned process cleanup failed'
    status = 128 + interruption[0] if interruption else (1 if failure else 0)
    state.update(completedAtUtc=utc(), exitCode=status, status='PASS' if status == 0 else 'FAIL',
                 failure=failure, driverExitCode=driver_status, cleanup=cleanup_result,
                 acceptanceEligible=status == 0, observedRows=rows,
                 freeDiskBytesAtEnd=shutil.disk_usage(ROOT).free,
                 manifest='sha256-manifest.json')
    write_json(output / 'result.json', state)
    # Hash only after measurement and process teardown. Never rewrite/remove old output.
    try:
        manifest = {str(path.relative_to(output)): {'bytes': path.stat().st_size, 'sha256': digest(path)}
                    for path in sorted(output.rglob('*')) if path.is_file() and path.name != 'sha256-manifest.json'}
        write_json(output / 'sha256-manifest.json', manifest)
    except Exception as error:
        failure = (failure + '; ' if failure else '') + f'Manifest publication failed: {error}'
        status = status or 1
        state.update(exitCode=status, status='FAIL', failure=failure, acceptanceEligible=False)
        write_json(output / 'result.json', state)
    print(json.dumps({'outputDirectory': str(output), 'status': state['status'], 'exitCode': status, 'failure': failure}), flush=True)
    return status


if __name__ == '__main__':
    raise SystemExit(main())
