#!/usr/bin/env python3
"""Explicit coordinator-authorized dependency startup/ownership/teardown proof; no workload."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import sys
import time

spec = importlib.util.spec_from_file_location('pulse_quiet_runner', Path(__file__).with_name('run-quiet.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

def main():
    if os.environ.get('PULSE_FUNCTIONAL_CONTAINER_GRANT') != 'root-approved-startup-only':
        print('Requires explicit root startup-only grant; no benchmark or app launches.', file=sys.stderr)
        return 2
    archive = Path((m.WORK / 'latest-harness-repair-path').read_text().strip())
    output = archive / ('functional-' + str(os.getpid()))
    output.mkdir(exist_ok=False)
    r = m.Runner.__new__(m.Runner)
    r.output, r.env = output, os.environ.copy()
    r.summary = {'classification': 'functional-container-ownership-only', 'started': m.now(),
                 'measurementsExecuted': False, 'buildsExecuted': False, 'quietReservationClaimed': False}
    r.started_dependencies = False
    r.last_active = None
    r.container_ids = set()
    r.container_processes = {}
    result = 1
    def interrupted(sig, frame):
        raise m.Interrupted(signal.Signals(sig).name)
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        # This is a functional grant, not a reservation/all-locks quiet measurement.
        if r.command('before-running', ['docker', 'ps', '-q']).stdout.strip():
            raise RuntimeError('Expected no existing running containers before functional startup')
        before = m.processes()
        foreign = m.foreign_java(before, m.descendants(os.getpid(), before), {})
        if foreign:
            raise RuntimeError('Foreign Java before functional startup: ' + str(foreign))
        r.started_dependencies = True  # Includes partial startup cleanup.
        r.command('functional-up', m.COMPOSE + ['up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '180'], timeout=220)
        observations = []
        for _ in range(3):
            inventory = r.container_inventory()
            snapshot = m.processes()
            foreign = m.foreign_java(snapshot, m.descendants(os.getpid(), snapshot), inventory)
            if foreign:
                # Allow only an observed startup/top sampling race; retry fresh, fail closed.
                inventory = r.container_inventory()
                snapshot = m.processes()
                foreign = m.foreign_java(snapshot, m.descendants(os.getpid(), snapshot), inventory)
            if foreign:
                raise RuntimeError('Foreign Java during functional ownership check: ' + str(foreign))
            if {p['service'] for p in inventory.values()} != set(m.IMAGES):
                raise RuntimeError('Missing exact owned Kafka/Postgres Docker PID inventories')
            java = {str(pid): {'identity': identity, 'proc': snapshot[pid]} for pid, identity in inventory.items()
                    if pid in snapshot and snapshot[pid]['comm'] == 'java'}
            if not java:
                raise RuntimeError('Expected actual Kafka JVM in exact owned Docker top inventory')
            observations.append({'time': m.now(), 'containers': r.summary['latestContainerInventory'],
                                 'ownedJava': java, 'foreignJava': foreign})
            time.sleep(2)
        r.summary.update(observations=observations, status='FUNCTIONAL_OWNERSHIP_PASSED')
        result = 0
    except BaseException as exc:
        r.summary.update(status='FAILED', error=type(exc).__name__ + ': ' + str(exc))
    finally:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        r.summary['teardown'] = r.teardown()
        if r.summary['teardown'].get('attempted') and not r.summary['teardown'].get('success'):
            result = 1
            r.summary['status'] = 'FAILED_TEARDOWN'
        r.summary.update(exitCode=result, ended=m.now(), runnerSha256=m.sha(Path(__file__).with_name('run-quiet.py')))
        r.save()
        print(json.dumps({'summary': str(output / 'summary.json'), 'exitCode': result, 'status': r.summary['status'], 'teardown': r.summary['teardown']}))
    return result

if __name__ == '__main__':
    sys.exit(main())
