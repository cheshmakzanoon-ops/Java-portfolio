#!/usr/bin/env python3
"""Inert, finite Linux/Python-only controls for the actual release validator.

Root must explicitly grant execution after the quiet window. This file never
starts Java, Docker, Git, Maven, network clients, or application services.
"""
from __future__ import annotations

import argparse
import ctypes
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import types

HERE = Path(__file__).resolve().parent
HELPER = HERE.parent / 'coordinator/validate-clean-checkout.py'
EXTRACTION = HERE / 'test-cleancheck-review.py'

# Synthetic fixtures have an independent finite lifetime as a last resort.
# The leaf deliberately escapes its parent process group and ignores TERM.
FIXTURE = r'''
import json, os, pathlib, signal, subprocess, sys, time
mode, directory = sys.argv[1], pathlib.Path(sys.argv[2])
signal.signal(signal.SIGTERM, signal.SIG_IGN if mode in ('leaf','foreign') else signal.SIG_DFL)
stat = pathlib.Path('/proc/self/stat').read_text()
fields = stat[stat.rindex(')') + 2:].split()
identity = {'pid': os.getpid(), 'start': fields[19], 'ppid': os.getppid(),
            'pgid': os.getpgrp(), 'sid': os.getsid(0), 'mode': mode}
temporary = directory / (mode + '.tmp')
temporary.write_text(json.dumps(identity))
temporary.replace(directory / (mode + '.json'))
if mode in ('leaf', 'foreign'):
    time.sleep(90)
elif mode == 'middle':
    subprocess.Popen([sys.executable, __file__, 'leaf', str(directory)], start_new_session=True)
    time.sleep(90)
elif mode in ('timeout-parent', 'exit-parent'):
    child = 'middle' if mode == 'timeout-parent' else 'leaf'
    subprocess.Popen([sys.executable, __file__, child, str(directory)], start_new_session=True)
    deadline = time.monotonic() + 4
    while not (directory / 'leaf.json').is_file():
        if time.monotonic() > deadline:
            raise RuntimeError('Synthetic leaf readiness timed out')
        time.sleep(.01)
    if mode == 'timeout-parent':
        time.sleep(90)
else:
    raise RuntimeError('Unknown synthetic fixture mode')
'''


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute-after-quiet-release', action='store_true')
    parser.add_argument('--output', type=Path)
    options = parser.parse_args()
    if not options.execute_after_quiet_release:
        print(json.dumps({'executed': False, 'requires': '--execute-after-quiet-release',
                          'cases': ['nested setsid TERM-resistant grandchild timeout',
                                    'exited parent with adopted escaped TERM-resistant child'],
                          'negativeControl': 'independent child predates both commands',
                          'wallDeadlineSeconds': 32, 'emergencyReapSeconds': 5}, indent=2))
        return 0
    if sys.platform != 'linux' or not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        raise SystemExit('Linux pidfd support is required; no children started')
    output = options.output or HERE / ('process-controls-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    output = output.resolve()
    if not output.is_relative_to(HERE) or output.exists():
        raise SystemExit('Choose a fresh output directory within .work/A02')
    output.mkdir(parents=True)
    fixture = output / 'synthetic-fixture.py'
    fixture.write_text(FIXTURE)
    source_sha, extraction_sha = sha(HELPER), sha(EXTRACTION)
    spec = importlib.util.spec_from_file_location('cleancheck_offline_extraction', EXTRACTION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    ns = module.functions()  # Exactly the AST extraction used by the accepted pure controls.
    if sha(HELPER) != source_sha:
        raise RuntimeError('Validator changed during AST extraction; no children started')
    ns.update(evidence=output, clone=output, args=types.SimpleNamespace(phase='process-controls'),
              record={'commands': []}, active=None, active_owned={}, active_excluded={}, cleanup_active=False)
    library = ctypes.CDLL(None, use_errno=True)
    if library.prctl(36, 1, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), 'Cannot enable Linux child subreaper')
    baseline = ns['process_snapshot']()
    baseline_children = {pid: baseline[pid]['start']
                         for pid in ns['descendants'](baseline, os.getpid())}
    retained = {}
    report = {'status': 'RUNNING', 'executed': True,
              'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
              'runnerSha256': sha(__file__), 'validatorSha256': source_sha,
              'extractionSha256': extraction_sha, 'fixtureSha256': sha(fixture),
              'python': sys.version, 'cases': [], 'emergencySignals': [], 'cleanupErrors': [],
              'cleanupBoundary': 'pidfd signals after PID/start recheck; bounded five-second reap',
              'overallAlarmSeconds': 32}
    started = time.monotonic()
    foreign = None

    def save():
        target = output / 'control-results.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps(report, indent=2) + '\n')
        temporary.replace(target)

    def collect():
        snapshot = ns['process_snapshot']()
        for pid in ns['descendants'](snapshot, os.getpid()):
            row = snapshot[pid]
            if baseline_children.get(pid) != row['start']:
                retained[pid] = row['start']
        for path in output.glob('*/**/*.json'):
            try:
                row = json.loads(path.read_text())
                if {'pid', 'start', 'mode'} <= row.keys():
                    retained[int(row['pid'])] = row['start']
            except (OSError, ValueError, TypeError):
                continue
        return snapshot

    def kill_once():
        snapshot = collect()
        for pid, expected_start in list(retained.items()):
            current = snapshot.get(pid)
            if not current or current['start'] != expected_start or current['state'] == 'Z':
                continue
            descriptor = None
            try:
                descriptor = os.pidfd_open(pid, 0)
                checked = ns['process_snapshot']().get(pid)
                if checked and checked['start'] == expected_start:
                    signal.pidfd_send_signal(descriptor, signal.SIGKILL)
                    report['emergencySignals'].append({'pid': pid, 'start': expected_start, 'signal': 'SIGKILL'})
            except ProcessLookupError:
                pass
            except OSError as error:
                report['cleanupErrors'].append({'pid': pid, 'error': repr(error)})
            finally:
                if descriptor is not None:
                    os.close(descriptor)

    def interrupted(signum, frame):
        # Kill immediately so the validator's own finally cannot add another
        # ten-second TERM grace after our finite overall deadline expires.
        kill_once()
        raise RuntimeError('Control interrupted/deadline signal ' + str(signum))

    def identity(directory, mode, timeout=2):
        deadline = time.monotonic() + timeout
        path = directory / (mode + '.json')
        while time.monotonic() < deadline:
            if path.is_file():
                row = json.loads(path.read_text())
                retained[row['pid']] = row['start']
                return row
            time.sleep(.01)
        raise RuntimeError('Missing synthetic identity: ' + str(path))

    def live(row):
        current = ns['process_snapshot']().get(row['pid'])
        return bool(current and current['start'] == row['start'] and current['state'] != 'Z')

    def assert_foreign():
        row = identity(output / 'foreign', 'foreign')
        if not live(row):
            raise RuntimeError('Negative control was wrongly terminated by validator cleanup')
        return row

    previous_handlers = {number: signal.signal(number, interrupted)
                         for number in (signal.SIGINT, signal.SIGTERM, signal.SIGALRM)}
    save()
    signal.setitimer(signal.ITIMER_REAL, 32)
    try:
        foreign_directory = output / 'foreign'
        foreign_directory.mkdir()
        with (foreign_directory / 'process.log').open('wb') as log:
            foreign = subprocess.Popen([sys.executable, str(fixture), 'foreign', str(foreign_directory)],
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        foreign_identity = identity(foreign_directory, 'foreign')
        for label, mode in (('nested-timeout', 'timeout-parent'), ('exited-parent', 'exit-parent')):
            case_started = time.monotonic()
            directory = output / label
            directory.mkdir()
            case = {'name': label, 'status': 'RUNNING', 'foreignBefore': assert_foreign()}
            report['cases'].append(case)
            save()
            if mode == 'timeout-parent':
                try:
                    ns['command'](label, [sys.executable, str(fixture), mode, str(directory)],
                                  timeout=3, cwd=directory, cloud=False)
                except RuntimeError as error:
                    if not str(error).startswith('Actual validation command failed: '):
                        raise
                    case['expectedTimeoutError'] = str(error)
                else:
                    raise RuntimeError('Timeout control unexpectedly returned success')
                command_row = ns['record']['commands'][-1]
                if not command_row.get('timedOut'):
                    raise RuntimeError('Control did not reach the actual command timeout branch')
                owned = [identity(directory, role) for role in ('timeout-parent', 'middle', 'leaf')]
                if owned[1]['sid'] == owned[0]['sid'] or owned[2]['sid'] == owned[1]['sid']:
                    raise RuntimeError('Fixture did not actually escape nested sessions')
            else:
                ns['command'](label, [sys.executable, str(fixture), mode, str(directory)],
                              timeout=3, cwd=directory, cloud=False)
                parent, leaf = identity(directory, mode), identity(directory, 'leaf')
                current = ns['process_snapshot']()
                if live(parent) or not live(leaf) or current[leaf['pid']]['ppid'] != os.getpid():
                    raise RuntimeError('Exited-parent control did not reach live adopted-child state')
                case['adoptedBeforeStop'] = {'identity': leaf, 'observed': current[leaf['pid']]}
                proxy = types.SimpleNamespace(pid=parent['pid'], poll=lambda: 0)
                ns['stop_owned'](proxy)
                owned = [parent, leaf]
            case['ownedIdentities'] = owned
            if any(live(row) for row in owned):
                raise RuntimeError('Actual validator cleanup left a live fixture descendant')
            case['foreignAfter'] = assert_foreign()
            case['elapsedSeconds'] = time.monotonic() - case_started
            case['status'] = 'PASS'
            save()
        if sha(HELPER) != source_sha or sha(EXTRACTION) != extraction_sha:
            raise RuntimeError('Reviewed source changed during controls')
        report['status'] = 'PASS'
    except BaseException as error:
        report['status'] = 'FAIL'
        report['error'] = type(error).__name__ + ': ' + str(error)
        if report['cases'] and report['cases'][-1]['status'] == 'RUNNING':
            report['cases'][-1].update(status='FAIL', error=report['error'],
                                       elapsedSeconds=time.monotonic() - case_started)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        for number in previous_handlers:
            signal.signal(number, signal.SIG_IGN)
        cleanup_started = time.monotonic()
        deadline = cleanup_started + 5
        while True:
            kill_once()  # Includes the intentionally spared foreign fixture now.
            if foreign is not None:
                foreign.poll()
            ns['reap_children']()
            snapshot = collect()
            remaining = [{'pid': pid, **snapshot[pid]} for pid, start in retained.items()
                         if pid in snapshot and snapshot[pid]['start'] == start]
            if not remaining or time.monotonic() >= deadline:
                break
            time.sleep(.05)
        report['remainingOwnedProcessesIncludingZombies'] = remaining
        if remaining or report['cleanupErrors']:
            report['status'] = 'FAIL'
        report['cleanupElapsedSeconds'] = time.monotonic() - cleanup_started
        report['elapsedSeconds'] = time.monotonic() - started
        report['completedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
        report['validatorCommandEvidence'] = 'execution.json'
        report['artifactHashes'] = {str(path.relative_to(output)): sha(path)
                                    for path in output.rglob('*')
                                    if path.is_file() and path.name != 'control-results.json'}
        save()
        for number, handler in previous_handlers.items():
            signal.signal(number, handler)
    print(json.dumps({'status': report['status'], 'elapsedSeconds': report['elapsedSeconds'],
                      'evidence': str(output)}, indent=2))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
