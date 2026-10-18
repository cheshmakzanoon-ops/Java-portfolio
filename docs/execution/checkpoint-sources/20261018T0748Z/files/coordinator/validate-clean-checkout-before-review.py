#!/usr/bin/env python3
"""Finite, recorded release checks from a real fresh clone; inert without --execute.

Run only after all owners pause and reference measurements stop. No benchmark
results are produced by this helper. Each phase keeps its own actual logs/status.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

SOURCE = Path('/workspace/Java-portfolio')
SLUGS = ('clearledger durableflow quorumkv pulsestream tenantshield matchcore '
         'indexforge threadlens byteguard routeweaver').split()
parser = argparse.ArgumentParser()
parser.add_argument('--execute', action='store_true')
parser.add_argument('--sha', required=True)
parser.add_argument('--phase', required=True,
                    choices=['prepare', 'verify', 'integration', 'mutation', 'demos', 'coexist'])
args = parser.parse_args()
if not re.fullmatch(r'[0-9a-f]{40}', args.sha):
    raise SystemExit('Use the full, frozen actual commit SHA')
clone = Path('/tmp') / ('portfolio-cleancheck-' + args.sha[:12])
evidence = SOURCE / '.work/coordinator/cleancheck' / args.sha / args.phase
plan = {'phase': args.phase, 'sourceCommit': args.sha, 'clone': str(clone),
        'evidence': str(evidence), 'executed': False,
        'limits': 'serial builds/stacks; two stacks only for coexistence; no measurements'}
if not args.execute:
    print(json.dumps(plan, indent=2))
    raise SystemExit(0)
if evidence.exists():
    raise SystemExit('Refusing to overwrite a prior validation attempt')
if args.phase == 'prepare':
    if clone.exists():
        raise SystemExit('Fresh clone path already exists')
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE, text=True).strip()
    branch = subprocess.check_output(['git', 'branch', '--show-current'], cwd=SOURCE, text=True).strip()
    dirty = subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=normal'], cwd=SOURCE, text=True)
    if head != args.sha or branch != 'main' or dirty:
        raise SystemExit('Prepare requires the exact clean intended commit on main')
else:
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=clone, text=True).strip()
    if head != args.sha:
        raise SystemExit('Clone commit differs from the frozen release input')
java = subprocess.run(['ps', '-C', 'java', '-o', 'stat='], capture_output=True, text=True)
if java.returncode not in (0, 1) or any(not r.strip().startswith('Z') for r in java.stdout.splitlines()):
    raise SystemExit('Stop reference studies, builds and Java services before a release phase')
running = subprocess.check_output(['docker', 'ps', '--format', '{{.Image}}'], text=True)
if any('testcontainers/ryuk' not in r for r in running.splitlines()):
    raise SystemExit('Application containers must be stopped before a release phase')
evidence.mkdir(parents=True, exist_ok=False)
record = {**plan, 'executed': True, 'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(),
          'status': 'RUNNING', 'commands': []}
active = None


def save():
    target = evidence / 'execution.json'
    temporary = target.with_suffix('.tmp')
    temporary.write_text(json.dumps(record, indent=2) + '\n')
    temporary.replace(target)


def stop_owned(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def command(label, argv, timeout=1800, namespace=None, check=True, cwd=None, cloud=True):
    global active
    env = os.environ.copy()
    env['PORTFOLIO_TEST_RUN_STARTED'] = str(int(time.time()))
    if namespace:
        env['COMPOSE_PROJECT_NAME'] = namespace
    actual = list(argv)
    if cloud:
        actual = ['bash', '-c', 'source "$1"; shift; exec "$@"', 'clean-check',
                  str(clone / 'scripts/cloud-env.sh'), *actual]
    row = {'label': label, 'argv': list(argv), 'timeoutSeconds': timeout,
           'composeNamespace': namespace, 'testRunStarted': env['PORTFOLIO_TEST_RUN_STARTED'],
           'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
    record['commands'].append(row)
    save()
    started = time.monotonic()
    log = evidence / ('%03d-%s.log' % (len(record['commands']), label))
    row['logPath'] = log.name
    try:
        with log.open('wb') as output:
            active = subprocess.Popen(actual, cwd=cwd or clone, env=env,
                                      stdout=output, stderr=subprocess.STDOUT,
                                      start_new_session=True)
            try:
                row['exitCode'] = active.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                row['timedOut'] = True
                stop_owned(active)
                row['exitCode'] = active.returncode
            finally:
                if active.poll() is None:
                    stop_owned(active)
                active = None
    finally:
        row['elapsedSeconds'] = time.monotonic() - started
        row['logSha256'] = hashlib.sha256(log.read_bytes()).hexdigest()
        save()
    print(json.dumps({'phase': args.phase, 'command': label,
                      'exitCode': row.get('exitCode'), 'elapsedSeconds': row['elapsedSeconds']}), flush=True)
    if check and (row.get('exitCode') != 0 or row.get('timedOut')):
        raise RuntimeError('Actual validation command failed: ' + label)
    return row.get('exitCode', 1)


def project_command(action, slug, namespace, check=True):
    return command(action + '-' + slug, ['./scripts/portfolio.sh', action, slug],
                   timeout=900, namespace=namespace, check=check)


def require_fresh_namespace(namespace):
    for kind, flags in [('ps', ['-aq']), ('volume', ['ls', '-q'])]:
        existing = subprocess.check_output(['docker', kind, *flags, '--filter',
                                           'label=com.docker.compose.project=' + namespace], text=True)
        if existing.strip():
            raise RuntimeError('Fresh namespace already has ' + kind + ' resources: ' + namespace)


def interrupted(signum, frame):
    if active:
        stop_owned(active)
    raise RuntimeError('Interrupted by signal ' + str(signum))


for number in (signal.SIGINT, signal.SIGTERM):
    signal.signal(number, interrupted)
save()
try:
    if args.phase == 'prepare':
        command('clone', ['git', 'clone', '--no-hardlinks', '--single-branch', '--branch', 'main',
                          str(SOURCE), str(clone)], cwd=SOURCE, cloud=False, timeout=120)
        cloned = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=clone, text=True).strip()
        if cloned != args.sha:
            raise RuntimeError('Source moved during clone')
        # Only dependency/tool caches are reused: never target/, runtime or receipts.
        for name in ['coordinator', *('A%02d' % i for i in range(1, 11))]:
            cache = SOURCE / '.work' / name / 'm2'
            if cache.is_dir():
                destination = clone / '.work' / name / 'm2'
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.symlink_to(cache, target_is_directory=True)
        wrapper = clone / '.work/wrapper'
        wrapper.parent.mkdir(parents=True, exist_ok=True)
        wrapper.symlink_to(SOURCE / '.work/wrapper', target_is_directory=True)
        command('toolchain', ['./mvnw', '-version'], timeout=120)
        command('tracked-inputs', ['git', 'status', '--porcelain'], cloud=False)
    elif args.phase == 'verify':
        command('clean-reactor', ['./mvnw', '-Dmaven.repo.local=' + str(clone / '.work/coordinator/m2'),
                                  '-B', '-ntp', 'clean', 'verify'], timeout=3600)
        command('root-verify-all', ['./scripts/portfolio.sh', 'verify', 'all'], timeout=3600)
        for slug in SLUGS:
            command('standalone-' + slug, ['./scripts/portfolio.sh', 'verify', slug], timeout=1800)
    elif args.phase in ('integration', 'mutation'):
        for slug in SLUGS:
            command(args.phase + '-' + slug,
                    ['./scripts/portfolio.sh', args.phase, slug], timeout=2700)
    elif args.phase == 'demos':
        for slug in SLUGS:
            namespace = 'portfolio-clean-' + args.sha[:8] + '-' + slug
            require_fresh_namespace(namespace)
            try:
                project_command('up', slug, namespace)
                if slug == 'quorumkv':
                    project_command('down', slug, namespace)
                command('containers-' + slug, ['docker', 'ps', '--no-trunc', '--format',
                        '{{.ID}}|{{.Image}}|{{.Names}}|{{.Labels}}'], namespace=namespace, cloud=False)
                project_command('demo', slug, namespace)
                project_command('reset-demo', slug, namespace)
                project_command('up', slug, namespace)
                if slug == 'quorumkv':
                    project_command('down', slug, namespace)
                project_command('demo', slug, namespace)
            finally:
                if project_command('down', slug, namespace, check=False):
                    raise RuntimeError('Scoped teardown failed for ' + slug)
                command('stopped-' + slug, ['python3', 'scripts/check-stopped.py', slug],
                        namespace=namespace, timeout=30)
    elif args.phase == 'coexist':
        namespaces = {slug: 'portfolio-coexist-' + args.sha[:8] + '-' + slug
                      for slug in ('clearledger', 'pulsestream')}
        for namespace in namespaces.values():
            require_fresh_namespace(namespace)
        try:
            for slug, namespace in namespaces.items():
                project_command('up', slug, namespace)
            command('simultaneous-containers', ['docker', 'ps', '--no-trunc', '--format',
                    '{{.ID}}|{{.Image}}|{{.Names}}|{{.Labels}}'], cloud=False)
            for slug, namespace in namespaces.items():
                project_command('demo', slug, namespace)
        finally:
            failed = []
            for slug, namespace in namespaces.items():
                if project_command('down', slug, namespace, check=False):
                    failed.append(slug)
                elif command('stopped-' + slug, ['python3', 'scripts/check-stopped.py', slug],
                             namespace=namespace, timeout=30, check=False):
                    failed.append(slug)
            if failed:
                raise RuntimeError('Scoped coexistence teardown failed: ' + ','.join(failed))
    record['status'] = 'PASS'
except BaseException as error:
    record['status'] = 'FAIL'
    record['error'] = type(error).__name__ + ': ' + str(error)
    raise
finally:
    record['completedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
    save()
