#!/usr/bin/env python3
"""Finite, recorded release checks from a real fresh clone; inert without --execute.

Run only after all owners pause and reference measurements stop. No benchmark
results are produced by this helper. Each phase keeps its own actual logs/status.
"""
from __future__ import annotations

import argparse
import ctypes
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import urllib.request

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
catalog = json.loads((SOURCE / 'catalog/projects.yaml').read_text())['projects']
projects = {p['slug']: p for p in catalog}
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
if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
    raise SystemExit('Cannot establish Linux child subreaping for bounded cleanup')
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
active_owned = {}
active_excluded = {}
cleanup_active = False


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def copy_snapshot(source, target, maximum_bytes=512 * 1024 * 1024):
    """Retain an exact, revalidated prefix when an active service appends a log."""
    with source.open('rb') as stream:
        initial = os.fstat(stream.fileno())
        if initial.st_size > maximum_bytes:
            raise RuntimeError('Raw evidence exceeds remaining snapshot byte budget')
        remaining = initial.st_size
        h = hashlib.sha256()
        with target.open('xb') as output:
            while remaining:
                chunk = stream.read(min(1024 * 1024, remaining))
                if not chunk:
                    raise RuntimeError('Raw evidence truncated during capture: ' + str(source))
                h.update(chunk)
                output.write(chunk)
                remaining -= len(chunk)
            output.flush()
            os.fsync(output.fileno())
        stream.seek(0)
        remaining = initial.st_size
        check = hashlib.sha256()
        while remaining:
            chunk = stream.read(min(1024 * 1024, remaining))
            if not chunk:
                raise RuntimeError('Raw evidence truncated during recheck: ' + str(source))
            check.update(chunk)
            remaining -= len(chunk)
        final = source.stat(follow_symlinks=False)
        if (initial.st_dev, initial.st_ino) != (final.st_dev, final.st_ino) or h.digest() != check.digest():
            raise RuntimeError('Raw evidence prefix/replacement changed during capture: ' + str(source))
        if digest(target) != h.hexdigest():
            raise RuntimeError('Raw evidence copy differs: ' + str(source))
        return {'bytes': initial.st_size, 'sha256': h.hexdigest(),
                'sourceSizeAtCompletion': final.st_size,
                'boundary': 'exact revalidated prefix at opening; subsequent appends are outside this snapshot'}


def process_snapshot():
    result = {}
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit():
            continue
        try:
            stat = (directory / 'stat').read_text()
            fields = stat[stat.rindex(')') + 2:].split()
            result[int(directory.name)] = {'state': fields[0], 'ppid': int(fields[1]),
                                           'start': fields[19]}
        except (OSError, ValueError, IndexError):
            continue
    return result


def java_processes():
    rows = []
    for pid, state in process_snapshot().items():
        if state['state'] == 'Z':
            continue
        try:
            name = (Path('/proc') / str(pid) / 'comm').read_text().strip()
        except FileNotFoundError:
            continue
        if name in {'java', 'javac', 'jfr', 'jshell', 'jcmd', 'jstat'}:
            rows.append({'pid': pid, 'comm': name, 'start': state['start']})
    return rows


def descendants(snapshot, parent):
    found = {parent}
    while True:
        expanded = found | {pid for pid, row in snapshot.items() if row['ppid'] in found}
        if expanded == found:
            return found - {parent}
        found = expanded


def remember_children(excluded):
    snapshot = process_snapshot()
    for pid in descendants(snapshot, os.getpid()):
        row = snapshot[pid]
        if excluded.get(pid) != row['start']:
            active_owned[pid] = row['start']


def reap_children():
    while True:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
            if pid == 0:
                return
        except ChildProcessError:
            return


def protected_inputs():
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=SOURCE, text=True).strip() != args.sha:
        raise RuntimeError('Intended source HEAD moved during release validation')
    if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=clone, text=True).strip() != args.sha:
        raise RuntimeError('Clone HEAD moved during release validation')
    changed = subprocess.check_output(['git', 'diff', '--name-only', 'HEAD'], cwd=clone, text=True).splitlines()
    # These four actual lifecycle outputs may change. Other evidence includes immutable models,
    # executable final operators, protocols and labels, so its entire directory is not exempt.
    generated_outputs = {
        'projects/04-pulsestream/evidence/demo.json',
        'projects/04-pulsestream/evidence/demo-output.txt',
        'projects/05-tenantshield/evidence/demo.json',
        'projects/05-tenantshield/evidence/demo-output.txt',
    }
    unexpected = [p for p in changed if p not in generated_outputs]
    if unexpected:
        raise RuntimeError('Unexpected modified tracked clone inputs: ' + ','.join(unexpected))
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=clone).split(b'\0')
    unexpected_untracked = [p.decode() for p in untracked if p and p.decode() not in generated_outputs]
    if unexpected_untracked:
        raise RuntimeError('Unexpected untracked clone inputs: ' + ','.join(unexpected_untracked))
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=clone).split(b'\0')
    tracked = {p.decode() for p in paths if p}
    # Inspect input directories regardless of Git ignores; generated output trees are separate.
    for base, directories, files in os.walk(clone, followlinks=False):
        directories[:] = [d for d in directories if d not in {'.git', '.work', 'target', '.runtime', '__pycache__', '.idea'}]
        for name in files:
            relative = str((Path(base) / name).relative_to(clone))
            if relative in tracked or relative in generated_outputs:
                continue
            generated = (name in {'.jqwik-database', 'dependency-reduced-pom.xml'}
                         or name.endswith(('.pyc', '.jfr', '.log', '.hprof'))
                         or name.startswith('javac.') and name.endswith('.args'))
            if not generated:
                raise RuntimeError('Unexpected untracked/ignored input: ' + relative)
    hashes = {}
    for raw in paths:
        if not raw:
            continue
        relative = raw.decode()
        if relative in generated_outputs:
            continue
        path = clone / relative
        if path.is_symlink() or not path.is_file():
            raise RuntimeError('Protected input missing or symbolic: ' + relative)
        hashes[relative] = digest(path)
    return hashes, changed


def save():
    target = evidence / 'execution.json'
    temporary = target.with_suffix('.tmp')
    temporary.write_text(json.dumps(record, indent=2) + '\n')
    temporary.replace(target)


def stop_owned(process):
    for signum, seconds in ((signal.SIGTERM, 10), (signal.SIGKILL, 5)):
        remember_children(active_excluded)
        current = process_snapshot()
        # A subreaper retains identities even after nested timeout/shell leaders exit.
        leader = current.get(process.pid)
        if leader and leader['start'] == active_owned.get(process.pid):
            for pid in descendants(current, process.pid):
                active_owned[pid] = current[pid]['start']
        for pid, started in list(active_owned.items()):
            if pid in current and current[pid]['start'] == started and current[pid]['state'] != 'Z':
                try:
                    os.kill(pid, signum)
                except ProcessLookupError:
                    pass
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            process.poll()
            remember_children(active_excluded)
            current = process_snapshot()
            if not any(pid in current and current[pid]['start'] == start and current[pid]['state'] != 'Z'
                       for pid, start in active_owned.items()):
                process.poll()
                reap_children()
                return
            time.sleep(.1)
    raise RuntimeError('Owned descendants survived bounded TERM/KILL cleanup')


def command(label, argv, timeout=1800, namespace=None, check=True, cwd=None, cloud=True,
            run_cutoff=None):
    global active, active_owned, active_excluded
    env = os.environ.copy()
    env['PORTFOLIO_TEST_RUN_STARTED'] = str(int(time.time()) if run_cutoff is None else run_cutoff)
    if namespace:
        env['COMPOSE_PROJECT_NAME'] = namespace
    actual = list(argv)
    if cloud:
        actual = ['bash', '-c', 'set -euo pipefail; source "$1"; shift; exec "$@"', 'clean-check',
                  str(clone / 'scripts/cloud-env.sh'), *actual]
    row = {'label': label, 'argv': list(argv), 'timeoutSeconds': timeout,
           'composeNamespace': namespace, 'testRunStarted': env['PORTFOLIO_TEST_RUN_STARTED'],
           'startedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat()}
    record['commands'].append(row)
    save()
    started = time.monotonic()
    log = evidence / ('%03d-%s.log' % (len(record['commands']), label))
    row['logPath'] = log.name
    before = process_snapshot()
    excluded = {pid: before[pid]['start'] for pid in descendants(before, os.getpid())}
    active_excluded = excluded
    active_owned = {}
    try:
        with log.open('wb') as output:
            active = subprocess.Popen(actual, cwd=cwd or clone, env=env,
                                      stdout=output, stderr=subprocess.STDOUT,
                                      start_new_session=True)
            current = process_snapshot()
            if active.pid in current:
                active_owned[active.pid] = current[active.pid]['start']
            try:
                deadline = time.monotonic() + timeout
                while True:
                    remember_children(excluded)
                    result = active.poll()
                    if result is not None:
                        row['exitCode'] = result
                        break
                    if time.monotonic() >= deadline:
                        row['timedOut'] = True
                        stop_owned(active)
                        row['exitCode'] = active.returncode
                        break
                    time.sleep(.1)
            except BaseException as error:
                row['primaryError'] = type(error).__name__ + ': ' + str(error)
                raise
            finally:
                try:
                    if active.poll() is None:
                        stop_owned(active)
                    remember_children(excluded)
                    row.setdefault('exitCode', active.poll())
                    row['observedOwnedProcessIdentities'] = dict(active_owned)
                except BaseException as error:
                    row['processCleanupError'] = type(error).__name__ + ': ' + str(error)
                    raise
                finally:
                    active = None
    finally:
        row['elapsedSeconds'] = time.monotonic() - started
        row['logSha256'] = digest(log)
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
    for kind, flags in [('ps', ['-aq']), ('volume', ['ls', '-q']), ('network', ['ls', '-q'])]:
        existing = subprocess.check_output(['docker', kind, *flags, '--filter',
                                           'label=com.docker.compose.project=' + namespace], text=True)
        if existing.strip():
            raise RuntimeError('Fresh namespace already has ' + kind + ' resources: ' + namespace)


def interrupted(signum, frame):
    if cleanup_active:
        record.setdefault('signalsDeferredDuringCleanup', []).append(signum)
        return
    if active:
        stop_owned(active)
    raise RuntimeError('Interrupted by signal ' + str(signum))


def preserve_demo(slug, round_label):
    project = projects[slug]
    destination = evidence / (slug + '-' + round_label + '-raw')
    destination.mkdir(exist_ok=False)
    roots = [clone / project['path'] / 'evidence', clone / '.work' / project['id'] / 'runtime',
             clone / project['path'] / '.runtime', clone / project['path'] / 'target']
    selected = []
    total = 0
    for source in roots:
        if not source.is_dir():
            continue
        for path in source.rglob('*'):
            if path.is_symlink():
                raise RuntimeError('Symbolic demo proof: ' + str(path))
            if not path.is_file() or path.suffix in {'.jar', '.class'}:
                continue
            if 'target' in source.parts and path.suffix not in {'.log', '.json', '.txt', '.csv', '.tsv', '.jfr', '.pid', '.html', '.sarif', '.xml'}:
                continue
            total += path.stat().st_size
            if total > 512 * 1024 * 1024:
                raise RuntimeError('Demo raw preservation exceeds declared512MiB bound')
            selected.append(path)
    if shutil.disk_usage(evidence).free < total + 1024 ** 3:
        raise RuntimeError('Insufficient space to preserve demo proof with1GiB reserve')
    inventory = []
    captured_bytes = 0
    for path in selected:
        relative = path.relative_to(clone)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        copied = copy_snapshot(path, target, 512 * 1024 * 1024 - captured_bytes)
        captured_bytes += copied['bytes']
        inventory.append({'path': str(relative), **copied})
    (destination / 'inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')


def cleanup_projects(namespaces):
    global cleanup_active
    cleanup_active = True
    errors = []
    try:
        for slug, namespace in namespaces.items():
            for action in ('down', 'stopped'):
                try:
                    if action == 'down':
                        status = command('cleanup-' + slug, ['./scripts/portfolio.sh', 'down', slug],
                                         timeout=90, namespace=namespace, check=False)
                    else:
                        status = command('stopped-' + slug, ['python3', 'scripts/check-stopped.py', slug],
                                         timeout=30, namespace=namespace, check=False)
                    if status:
                        errors.append(slug + ':' + action + ':' + str(status))
                except BaseException as error:
                    errors.append(slug + ':' + action + ':' + repr(error))
    finally:
        cleanup_active = False
        record.setdefault('cleanupAttempts', []).append({'projects': namespaces, 'errors': errors})
        save()
    if errors:
        raise RuntimeError('Independent cleanup failures: ' + '; '.join(errors))


def coexist_identity(label, expected=None):
    observed = {}
    specs = {'clearledger': (clone / 'projects/01-clearledger/target/api.pid', 18100),
             'pulsestream': (clone / '.work/A04/runtime/service.pid', 18400)}
    snapshot = process_snapshot()
    for slug, (pid_path, port) in specs.items():
        pid = int(pid_path.read_text().strip())
        row = snapshot.get(pid)
        if not row or row['state'] == 'Z':
            raise RuntimeError('Coexist owned API absent: ' + slug)
        argv = (Path('/proc') / str(pid) / 'cmdline').read_bytes().decode(errors='replace')
        if str(clone / projects[slug]['path']) not in argv or '.jar' not in argv:
            raise RuntimeError('Coexist PID is not the selected clone API: ' + slug)
        with urllib.request.urlopen('http://127.0.0.1:%d/health' % port, timeout=3) as response:
            if response.status != 200:
                raise RuntimeError('Coexist health failed: ' + slug)
            body = response.read(65537)
            if len(body) > 65536:
                raise RuntimeError('Oversize coexist health response')
        namespace = 'portfolio-coexist-' + args.sha[:8] + '-' + slug
        ids = subprocess.check_output(['docker', 'ps', '-q', '--no-trunc', '--filter',
                                       'label=com.docker.compose.project=' + namespace], text=True, timeout=10).split()
        needed = 1 if slug == 'clearledger' else 2
        if len(ids) != needed:
            raise RuntimeError('Wrong coexist container count: ' + slug)
        container_rows = []
        for identity in sorted(ids):
            raw = subprocess.check_output(['docker', 'inspect', '--format',
                '{{.Id}}|{{.State.Running}}|{{.State.Pid}}|{{.Config.Image}}|{{index .Config.Labels "com.docker.compose.service"}}', identity],
                text=True, timeout=10).strip().split('|')
            if len(raw) != 5 or raw[1] != 'true' or '@sha256:' not in raw[3]:
                raise RuntimeError('Unverified coexist container: ' + slug)
            container_rows.append(raw)
        observed[slug] = {'pid': pid, 'start': row['start'], 'containers': container_rows}
    if expected is not None and observed != expected:
        raise RuntimeError('Coexisting service identities changed across operations')
    record.setdefault('coexistenceObservations', []).append({'label': label,
        'observedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'services': observed})
    save()
    return observed


def seed_dependency_caches():
    pool = clone / '.work/dependency-pool'
    pool.mkdir(parents=True)
    copied = 0
    for name in ['coordinator', *('A%02d' % i for i in range(1, 11))]:
        source = SOURCE / '.work' / name / 'm2'
        destination = clone / '.work' / name / 'm2'
        destination.mkdir(parents=True, exist_ok=False)
        if not source.is_dir():
            continue
        for path in source.rglob('*'):
            if path.is_symlink():
                raise RuntimeError('Symbolic dependency cache input: ' + str(path))
            if not path.is_file():
                continue
            relative = path.relative_to(source)
            if 'SNAPSHOT' in str(relative) or relative.parts[:3] == ('io', 'github', 'cheshmakzanoon'):
                continue
            if path.suffix not in {'.jar', '.pom', '.sha1', '.sha256', '.sha512', '.md5'} and path.name != '_remote.repositories':
                continue
            key = digest(path)
            immutable = pool / key
            if not immutable.exists():
                if shutil.disk_usage(clone).free < path.stat().st_size + 2 * 1024 ** 3:
                    raise RuntimeError('Dependency cache copy requires2GiB remaining reserve')
                shutil.copy2(path, immutable)
                if digest(immutable) != key:
                    raise RuntimeError('Dependency cache changed during copy')
                copied += path.stat().st_size
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            # Hardlinks stay wholly inside the disposable clone; original caches are never shared writes.
            os.link(immutable, target)
    record['dependencyCachePolicy'] = {'copiedBytes': copied,
        'ownPortfolioAndAllSnapshotsExcluded': True, 'cacheDirectoriesAreReal': True,
        'originalCacheContentsCopiedNotLinked': True}
    save()


for number in (signal.SIGINT, signal.SIGTERM):
    signal.signal(number, interrupted)
save()
try:
    record['validatorSha256'] = digest(Path(__file__))
    if args.phase != 'prepare':
        prepared = json.loads((SOURCE / '.work/coordinator/cleancheck' / args.sha / 'prepare/execution.json').read_text())
        if prepared.get('status') != 'PASS' or prepared.get('validatorSha256') != record['validatorSha256']:
            raise RuntimeError('Preparation used a different validator or did not pass')
    protected_before = None
    if args.phase != 'prepare':
        protected_before, prior_output_changes = protected_inputs()
        record['protectedInputHashes'] = protected_before
        record['priorGeneratedEvidenceChanges'] = prior_output_changes
        save()
    if args.phase == 'prepare':
        command('clone', ['git', 'clone', '--no-hardlinks', '--single-branch', '--branch', 'main',
                          str(SOURCE), str(clone)], cwd=SOURCE, cloud=False, timeout=120)
        cloned = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=clone, text=True).strip()
        if cloned != args.sha:
            raise RuntimeError('Source moved during clone')
        # Real, clone-local directories; own SNAPSHOTs/targets/runtime/receipts are excluded.
        seed_dependency_caches()
        wrapper = clone / '.work/wrapper'
        wrapper.parent.mkdir(parents=True, exist_ok=True)
        wrapper.symlink_to(SOURCE / '.work/wrapper', target_is_directory=True)
        command('toolchain', ['./mvnw', '-version'], timeout=120)
        command('tracked-inputs', ['git', 'status', '--porcelain'], cloud=False)
        inputs, changes = protected_inputs()
        if changes:
            raise RuntimeError('Fresh clone contains modified tracked inputs')
        record['protectedInputHashes'] = inputs
    elif args.phase == 'verify':
        command('clean-reactor', ['./mvnw', '-Dmaven.repo.local=' + str(clone / '.work/coordinator/m2'),
                                  '-B', '-ntp', 'clean', 'verify'], timeout=3600)
        reactor_cutoff = record['commands'][-1]['testRunStarted']
        for slug in SLUGS:
            command('reactor-discovery-' + slug,
                    ['python3', 'scripts/check-tests.py', projects[slug]['path']],
                    run_cutoff=reactor_cutoff)
        command('root-verify-all', ['./scripts/portfolio.sh', 'verify', 'all'], timeout=3600)
        for slug in SLUGS:
            project = projects[slug]
            own_cache = clone / '.work' / project['id'] / 'm2/io/github/cheshmakzanoon'
            if own_cache.exists():
                if own_cache.is_symlink() or not own_cache.resolve().is_relative_to(clone):
                    raise RuntimeError('Own artifact cache is outside disposable clone')
                shutil.rmtree(own_cache)
            command('standalone-clean-' + slug,
                    ['./mvnw', '-f', project['path'] + '/pom.xml',
                     '-Dmaven.repo.local=' + str(clone / '.work' / project['id'] / 'm2'),
                     '-B', '-ntp', 'clean', 'verify'], timeout=1800)
            standalone_cutoff = record['commands'][-1]['testRunStarted']
            command('standalone-discovery-' + slug,
                    ['python3', 'scripts/check-tests.py', project['path']],
                    run_cutoff=standalone_cutoff)
            # Direct Maven does not prepare lifecycle receipts. The supported invocation does so.
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
                preserve_demo(slug, 'round1')
                project_command('reset-demo', slug, namespace)
                project_command('up', slug, namespace)
                if slug == 'quorumkv':
                    project_command('down', slug, namespace)
                project_command('demo', slug, namespace)
                preserve_demo(slug, 'round2')
            finally:
                cleanup_projects({slug: namespace})
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
            identities = coexist_identity('both services before operations')
            for slug, namespace in namespaces.items():
                coexist_identity('before demo ' + slug, identities)
                project_command('demo', slug, namespace)
                coexist_identity('after demo ' + slug, identities)
                preserve_demo(slug, 'coexist')
        finally:
            cleanup_projects(namespaces)
    protected_after, generated_changes = protected_inputs()
    if protected_before is not None and protected_after != protected_before:
        raise RuntimeError('Protected executable/fixture/configuration inputs changed during phase')
    record['generatedEvidenceChanges'] = generated_changes
    remaining = java_processes()
    if remaining:
        raise RuntimeError('Java remains after completed release phase: ' + repr(remaining))
    remaining_apps = subprocess.check_output(['docker', 'ps', '--format', '{{.Image}}'], text=True)
    if any('testcontainers/ryuk' not in row for row in remaining_apps.splitlines()):
        raise RuntimeError('Application containers remain after release phase')
    record['status'] = 'PASS'
except BaseException as error:
    record['status'] = 'FAIL'
    record['error'] = type(error).__name__ + ': ' + str(error)
    raise
finally:
    record['completedAtUtc'] = dt.datetime.now(dt.timezone.utc).isoformat()
    save()
