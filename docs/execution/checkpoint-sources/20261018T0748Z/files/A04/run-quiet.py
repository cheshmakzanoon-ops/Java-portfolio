#!/usr/bin/env python3
"""Coordinator-only quiet experiment; owns its children and two Compose services.

No Maven, pulls, source edits, volume deletion or automatic benchmark acceptance.
Run only with all three build locks held and PULSE_QUIET_RESERVATION set.
"""
from __future__ import annotations
import collections
import datetime as dt
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.work/A04'
PROJECT = ROOT / 'projects/04-pulsestream'
RUNTIME = WORK / 'runtime'
FROZEN = 'working-tree-sha256:2f7d000815cce016675463dcac431f93332bf2f4049a0b19a1330391fda940fc'
IMAGES = {
    'postgres': 'postgres:18.3-alpine@sha256:54451ecb8ab38c24c3ec123f2fd501303a3a1856a5c66e98cecf2460d5e1e9d7',
    'kafka': 'apache/kafka:4.2.1@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0',
}
COMPOSE = ['docker', 'compose', '-f', str(PROJECT / 'infra/compose.yaml')]
GIB = 1024 ** 3


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + '\n')
    temporary.replace(path)


def process_record(directory):
    """Optional proc symlinks can be denied for container UIDs; never hide their comm."""
    record = {'ppid': None, 'start': None, 'state': None,
              'comm': None, 'executable': None, 'namespace': None, 'readErrors': {}}
    try:
        stat = (directory / 'stat').read_text()
        tail = stat[stat.rindex(')') + 2:].split()
        record.update(ppid=int(tail[1]), start=tail[19], state=tail[0])
    except (OSError, ValueError, IndexError) as exc:
        record['readErrors']['stat'] = type(exc).__name__
    try:
        record['comm'] = (directory / 'comm').read_text().strip()
    except OSError as exc:
        record['readErrors']['comm'] = type(exc).__name__
    for key, relative in [('executable', 'exe'), ('namespace', 'ns/pid')]:
        try:
            target = os.readlink(directory / relative)
            record[key] = Path(target).name if key == 'executable' else target
        except OSError as exc:
            record['readErrors'][key] = type(exc).__name__
    return record if record['comm'] is not None or record['start'] is not None else None


def processes():
    result = {}
    for directory in Path('/proc').iterdir():
        if directory.name.isdigit():
            record = process_record(directory)
            if record is not None:
                result[int(directory.name)] = record
    return result


def descendants(pid, snapshot):
    found = {pid}
    while True:
        expanded = found | {p for p, data in snapshot.items() if data['ppid'] in found}
        if expanded == found:
            return found
        found = expanded


def terminate_owned(process, previously_seen=None):
    """Nested bounded workers start their own sessions: track descendants, not only PGID."""
    snapshot = processes()
    owned = dict(previously_seen or {})
    parent = snapshot.get(process.pid)
    parent_still_owned = parent and ((process.pid in owned and parent['start'] == owned[process.pid])
                                     or (process.pid not in owned and process.poll() is None))
    if parent_still_owned:
        owned.update({pid: snapshot[pid]['start'] for pid in descendants(process.pid, snapshot) if pid in snapshot})
    for sig in (signal.SIGTERM, signal.SIGKILL):
        current = processes()
        for pid, started in sorted(owned.items(), reverse=True):
            if started is not None and pid in current and current[pid]['start'] == started and current[pid]['state'] != 'Z':
                try:
                    os.kill(pid, sig)
                except ProcessLookupError:
                    pass
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                process.wait(timeout=0.1)
            except subprocess.TimeoutExpired:
                pass
            remaining = processes()
            if not any(pid in remaining and remaining[pid]['start'] == start and remaining[pid]['state'] != 'Z' for pid, start in owned.items()):
                return
            time.sleep(0.1)



def foreign_java(snapshot, own, inventory):
    """Only fresh exact Docker host PID/start identities exempt a container JVM."""
    return [{'pid': pid, 'comm': p['comm']} for pid, p in snapshot.items()
                   if (p['comm'] in {'java', 'javac', 'jfr', 'jshell', 'jcmd', 'jstat', 'mvn', 'mvnw'}
                       or p.get('executable') in {'java', 'javac', 'jfr', 'jshell', 'jcmd', 'jstat'}) and p['state'] != 'Z'
                   and pid not in own
                   and not (pid in inventory and inventory[pid].get('start') is not None
                            and p.get('start') is not None and inventory[pid]['start'] == p['start'])]


class Interrupted(Exception):
    pass


class Runner:
    def __init__(self, reservation):
        self.reservation = reservation
        self.output = WORK / 'quiet-runs' / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + str(os.getpid()))
        self.output.mkdir(parents=True, exist_ok=False)
        self.summary = {
            'schemaVersion': 1, 'project': 'pulsestream', 'reservation': reservation,
            'sourceRevision': FROZEN, 'coordinatorReportedCorrectnessCommit': 'e16b8a4',
            'coordinatorReportedSharedInputCommit': 'a152090',
            'started': now(), 'status': 'RUNNING', 'phases': [],
            'boundaries': [
                'No Maven/build/pull during quiet execution; original/current engine each three forks.',
                'Engine benchmarks run before dependency startup; durable runs share only own Kafka/PostgreSQL.',
                'Durable driver uses fresh SQL database/topic each run,1m measurements/10k keys/8partitions.',
                'Source/schema preparation excluded and reported; SQL results/checkpoints/outbox included; result publication excluded.',
                'Closed-loop checkpoint latency excludes queue waiting; CPU is Java-process CPU, excludes containers.',
                'Two-second disk/process checks refresh Docker inspect/top host PID inventories while dependencies run; periodic container checks also run outside Java. Their host overhead is included, not independently quantified.',
                'SIGINT/TERM/error cleanup stops only owned processes and own two Compose services; SIGKILL/host loss cannot be handled.',
            ],
        }
        self.started_dependencies = False
        self.active = None
        self.phase_processes = {}
        self.all_owned_processes = {}
        self.last_active = None
        self.container_ids = set()
        self.container_processes = {}
        self.inputs = {}
        self.env = os.environ.copy()
        self.env['JAVA_HOME'] = '/workspace/.tools/jdk-25'
        self.env['PATH'] = self.env['JAVA_HOME'] + '/bin:' + self.env.get('PATH', '')
        self.save()

    def save(self):
        write_json(self.output / 'summary.json', self.summary)

    def emit(self, value):
        print(f'{now()} {value}', flush=True)

    def command(self, name, args, timeout=30, check=True):
        """Capture only known non-secret commands, preserving their real status."""
        result = subprocess.run(args, cwd=ROOT, env=self.env, text=True, capture_output=True, timeout=timeout)
        (self.output / (name + '.stdout')).write_text(result.stdout)
        (self.output / (name + '.stderr')).write_text(result.stderr)
        if check and result.returncode:
            raise RuntimeError(f'{name} exited {result.returncode}; see captured output')
        return result

    def fingerprint(self):
        return self.command('source-fingerprint', ['python3', str(PROJECT / 'scripts/fingerprint.py')]).stdout.strip()

    def locks(self):
        # Probe separate read-only file descriptions; root retains its own three locks.
        for number in range(3):
            with (ROOT / f'.work/build-slots/{number}.lock').open('rb') as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    continue
                fcntl.flock(lock, fcntl.LOCK_UN)
                raise RuntimeError(f'Build slot {number} is not reserved; coordinator must hold all three')

    def disk(self, minimum):
        free = shutil.disk_usage(ROOT).free
        if free < minimum:
            raise RuntimeError(f'Free disk {free} is below required reserve {minimum}; no files deleted')
        return free

    def container_inventory(self):
        """Fresh Docker authority, exact IDs/labels/pinned images; proc namespaces are advisory."""
        formatted = '{{.ID}}|{{.Label "com.docker.compose.project"}}|{{.Label "com.docker.compose.service"}}'
        running = self.command('running-container-scope', ['docker', 'ps', '--no-trunc', '--format', formatted]).stdout.splitlines()
        inventory = {}
        identities = []
        for line in running:
            identity, project, service = line.split('|', 2)
            if (not self.started_dependencies or project != 'java-portfolio-pulsestream'
                    or service not in IMAGES or not re.fullmatch('[0-9a-f]{64}', identity)):
                raise RuntimeError(f'Unexpected running container: {identity} project={project} service={service}')
            inspect_format = ('{{.Id}}|{{index .Config.Labels "com.docker.compose.project"}}|'
                              '{{index .Config.Labels "com.docker.compose.service"}}|{{.Config.Image}}|'
                              '{{.State.Pid}}|{{.State.Running}}')
            fields = self.command('owned-container-identity-' + service,
                                  ['docker', 'inspect', '--format', inspect_format, identity]).stdout.strip().split('|')
            if len(fields) != 6 or fields[:3] != [identity, project, service] or fields[3] != IMAGES[service] or fields[5] != 'true':
                raise RuntimeError('Owned container exact identity/image/state changed: ' + identity)
            init_pid = int(fields[4])
            top = self.command('owned-container-top-' + service, ['docker', 'top', identity, '-eo', 'pid']).stdout.splitlines()
            if not top or top[0].strip() != 'PID' or any(not row.strip().isdigit() for row in top[1:]):
                raise RuntimeError('Malformed Docker host PID inventory: ' + identity)
            pids = {int(row.strip()) for row in top[1:]}
            if init_pid <= 0 or init_pid not in pids:
                raise RuntimeError('Container init absent from Docker host PID inventory: ' + identity)
            snapshot = processes()
            for pid in pids:
                if pid in inventory:
                    raise RuntimeError('Docker host PID belongs to multiple containers: ' + str(pid))
                inventory[pid] = {'containerId': identity, 'service': service,
                                  'start': snapshot.get(pid, {}).get('start')}
            identities.append({'id': identity, 'service': service, 'configuredImage': fields[3],
                               'initPid': init_pid, 'processes': {str(pid): inventory[pid] for pid in sorted(pids)}})
            self.container_ids.add(identity)
        self.container_processes = inventory  # Replace, never accumulate stale PID exemptions.
        self.summary['latestContainerInventory'] = {'observed': now(), 'containers': identities}
        return inventory

    def quiet(self, containers=True, _retry=True):
        self.locks()
        # Refresh Docker top on every process check once dependencies can be starting.
        # A readable namespace/exe is never required and never grants ownership.
        inventory = self.container_inventory() if containers or self.started_dependencies else {}
        snapshot = processes()
        own = descendants(os.getpid(), snapshot)
        if self.active:
            for pid in descendants(self.active.pid, snapshot):
                if pid in snapshot:
                    self.phase_processes[pid] = snapshot[pid]['start']
                    self.all_owned_processes[pid] = snapshot[pid]['start']
        foreign = foreign_java(snapshot, own, inventory)
        if foreign:
            if self.started_dependencies and _retry:
                # A process can start between Docker top and the proc snapshot.
                self.quiet(containers=True, _retry=False)
                return
            raise RuntimeError(f'Unexpected JVM/build processes: {foreign}')

    def verify_inputs(self):
        if self.fingerprint() != FROZEN:
            raise RuntimeError('Frozen executable source fingerprint changed')
        for path, digest in self.inputs.items():
            if not Path(path).is_file() or sha(path) != digest:
                raise RuntimeError(f'Prepared artifact changed: {path}')

    def preflight(self):
        self.emit('PREFLIGHT')
        for tool in ('bash', 'python3', 'docker', 'java'):
            if not shutil.which(tool, path=self.env['PATH']):
                raise RuntimeError(f'Missing {tool}; prepare outside quiet window')
        self.quiet()
        self.disk(8 * GIB)
        if self.fingerprint() != FROZEN:
            raise RuntimeError('Expected recertified source2f7d0008…; do not rebuild inside quiet runner')
        for name in ('build.fingerprint', 'classpath.fingerprint'):
            if (RUNTIME / name).read_text().strip() != FROZEN:
                raise RuntimeError(f'Stale {name}; prepare outside quiet window')
        prepared = json.loads((WORK / 'QUIET_READY.json').read_text())
        if prepared['sourceRevision'] != FROZEN:
            raise RuntimeError('QUIET_READY source is stale')
        cached = json.loads((RUNTIME / 'artifacts.sha256.json').read_text())
        if cached != prepared['preparedArtifactHashes']:
            raise RuntimeError('Prepared JAR hash manifests disagree')
        self.inputs.update(cached)
        cpfile = PROJECT / 'verification/target/runtime-classpath.txt'
        classpath = cpfile.read_text().strip().split(os.pathsep)
        for path in classpath:
            if not Path(path).is_file():
                raise RuntimeError(f'Classpath entry absent: {path}')
            self.inputs[path] = sha(path)
        self.inputs[str(cpfile)] = sha(cpfile)
        metadata = PROJECT / 'verification/target/classes/META-INF/BenchmarkList'
        if 'EngineBenchmark' not in metadata.read_text():
            raise RuntimeError('Actual generated JMH metadata is missing')
        for directory in (PROJECT / 'verification/target/classes', PROJECT / 'engine/target/classes'):
            for path in directory.rglob('*'):
                if path.is_file():
                    self.inputs[str(path)] = sha(path)
        expected_runtime_inputs = prepared.get('preparedRuntimeInputHashes')
        if not expected_runtime_inputs or self.inputs != expected_runtime_inputs:
            raise RuntimeError('Runtime classpath/classes differ from actual recertified preparation receipt')
        baseline = WORK / 'pre-optimization-engine'
        old = json.loads((baseline / 'manifest.json').read_text())
        for name, digest in old['files'].items():
            self.inputs[str(baseline / name)] = digest
        self.inputs[str(baseline / 'manifest.json')] = sha(baseline / 'manifest.json')
        if sha(baseline / 'Engine.java') == sha(PROJECT / 'engine/src/main/java/io/github/cheshmakzanoon/portfolio/pulsestream/Engine.java'):
            raise RuntimeError('Original/current engine sources unexpectedly identical')
        # Record and verify every external orchestration input as well as frozen product code.
        for path in (Path(__file__).resolve(), WORK / 'run-engine-baseline.sh', WORK / 'QUIET_READY.json'):
            self.inputs[str(path)] = sha(path)
        self.verify_inputs()
        config = json.loads(self.command('compose-config', COMPOSE + ['config', '--format', 'json']).stdout)
        if config['name'] != 'java-portfolio-pulsestream' or set(config['services']) != set(IMAGES):
            raise RuntimeError('Unexpected Compose scope')
        for service, image in IMAGES.items():
            if config['services'][service]['image'] != image:
                raise RuntimeError(f'Unexpected immutable image for {service}')
            for binding in config['services'][service].get('ports', []):
                if binding.get('host_ip') != '127.0.0.1':
                    raise RuntimeError('Dependency port is not loopback-bound')
            inspected = json.loads(self.command('image-' + service, ['docker', 'image', 'inspect', image]).stdout)[0]
            expected = image.split(':', 1)[0] + '@' + image.split('@', 1)[1]
            if expected not in inspected.get('RepoDigests', []):
                raise RuntimeError(f'Image digest provenance missing for {service}')
        fixture = json.loads((PROJECT / 'fixtures/workload-v1.json').read_text())
        if tuple(fixture[x] for x in ('seed', 'partitions', 'keys', 'measurements')) != (4404, 8, 10000, 1000000):
            raise RuntimeError('Prescribed workload changed')
        self.summary['preflight'] = {'passed': True, 'freeDiskBytes': self.disk(8 * GIB), 'inputHashes': self.inputs,
                                     'baselineManifest': old, 'workloadManifest': fixture, 'time': now()}
        self.save()

    def phase(self, name, args, timeout, pattern=None):
        self.verify_inputs()
        self.quiet()
        self.disk(3 * GIB)
        before = set(RUNTIME.glob(pattern)) if pattern else set()
        self.phase_processes = {}
        record = {'name': name, 'command': args, 'started': now(), 'freeDiskBeforeBytes': shutil.disk_usage(ROOT).free}
        self.summary['phases'].append(record)
        self.save()
        self.emit('START ' + name)
        start = time.monotonic()
        logfile = self.output / (name + '.log')
        try:
            with logfile.open('wb') as output:
                self.active = subprocess.Popen(args, cwd=ROOT, env=self.env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
                self.last_active = self.active
                next_process, next_container = 0.0, 0.0
                while self.active.poll() is None:
                    elapsed = time.monotonic() - start
                    if elapsed > timeout:
                        raise RuntimeError(f'{name} exceeded {timeout}s')
                    if elapsed >= next_process:
                        self.disk(int(1.5 * GIB))
                        self.quiet(containers=name == 'dependencies-up' or elapsed >= next_container)
                        next_process = elapsed + 2
                        if elapsed >= next_container:
                            next_container = elapsed + 10
                    time.sleep(0.5)
                record['exitCode'] = self.active.returncode
            if self.active.returncode:
                raise RuntimeError(f'{name} exited {self.active.returncode}; see {logfile}')
            self.verify_inputs()
            if pattern:
                created = set(RUNTIME.glob(pattern)) - before
                record['artifactDirectories'] = [str(p) for p in sorted(created)]
                if len(created) != 1:
                    raise RuntimeError(f'{name} created {len(created)} output directories; refuse stale/ambiguous evidence')
                artifact = created.pop()
                record['result'] = self.validate_jmh(artifact) if name.startswith('engine-') else self.validate_full(artifact)
                record['artifacts'] = {str(p): {'bytes': p.stat().st_size, 'sha256': sha(p)}
                                       for p in sorted(artifact.rglob('*')) if p.is_file()}
                return artifact
        except BaseException as exc:
            if self.active:
                terminate_owned(self.active, self.phase_processes)
            record.setdefault('exitCode', self.active.returncode if self.active else None)
            record['error'] = f'{type(exc).__name__}: {exc}'
            if pattern:
                record['artifactDirectories'] = [str(p) for p in sorted(set(RUNTIME.glob(pattern)) - before)]
            raise
        finally:
            record['ended'] = now()
            record['wallSeconds'] = time.monotonic() - start
            record['freeDiskAfterBytes'] = shutil.disk_usage(ROOT).free
            record['log'] = str(logfile)
            self.active = None
            self.save()
            self.emit('END ' + name + ' exit=' + str(record.get('exitCode')))

    @staticmethod
    def recordings(artifact):
        files = [p for p in artifact.rglob('*.jfr') if p.is_file()]
        if not files:
            raise RuntimeError('Missing JFR recording; retain failed artifacts')
        for p in files:
            with p.open('rb') as recording:
                if p.stat().st_size < 1024 or recording.read(4) != b'FLR\x00':
                    raise RuntimeError('Non-JFR/empty recording; retain failed artifacts')
        return [str(p) for p in files]

    def validate_jmh(self, artifact):
        results = json.loads((artifact / 'jmh.json').read_text())
        if len(results) != 1:
            raise RuntimeError('Expected exactly one EngineBenchmark result')
        r = results[0]
        if not r['benchmark'].endswith('EngineBenchmark.freshBatch') or r['forks'] != 3 or r['warmupIterations'] != 2 or r['measurementIterations'] != 3:
            raise RuntimeError('JMH fork/warmup/measurement contract mismatch')
        metric = r['primaryMetric']
        raw = metric.get('rawData', [])
        if r['mode'] != 'thrpt' or metric['scoreUnit'] != 'ops/s' or len(raw) != 3 or any(len(fork) != 3 for fork in raw):
            raise RuntimeError('JMH lacks actual3fork×3measurement throughput data')
        if not math.isfinite(metric['score']) or metric['score'] <= 0:
            raise RuntimeError('Invalid JMH throughput')
        if 'gc.alloc.rate.norm' not in r.get('secondaryMetrics', {}):
            raise RuntimeError('Missing actual GC allocation profiler')
        return {'primaryMetric': metric, 'recordings': self.recordings(artifact), 'assertionsPassed': 6}

    def validate_full(self, artifact):
        r = json.loads((artifact / 'result.json').read_text())
        if r['sourceRevision'] != FROZEN or r['workload'] != 'durable-v1-full' or r['seed'] != 4404:
            raise RuntimeError('Stale/wrong-mode durable result')
        m = r['measurements']
        if m['acceptedMeasurements'] != 1000000 or m['duplicateCount'] != 1000 or r['assertions'] != {'passed': 6, 'failed': 0}:
            raise RuntimeError('Prescribed counts or independent assertions failed')
        for left, right in (('committedDigest', 'recoveryDigest'), ('resultDigest', 'oracleDigest')):
            if r[left] != r[right] or not re.fullmatch('[a-f0-9]{64}', r[left]):
                raise RuntimeError('Independent/recovery digest mismatch')
        if not (m['checkpointSamples'] > 0 and 0 <= m['maximumQueueBatches'] <= 8 and m['publishedWindows'] > 0):
            raise RuntimeError('Empty/unbounded measured processing')
        for field in ('elapsedSeconds', 'acceptedEventsPerSecond', 'databaseBytes', 'recoveryMillis'):
            if not math.isfinite(m[field]) or m[field] <= 0:
                raise RuntimeError('Missing/invalid durable measurement: ' + field)
        if not 0 <= m['checkpointP50Millis'] <= m['checkpointP95Millis'] <= m['checkpointP99Millis']:
            raise RuntimeError('Invalid checkpoint percentile ordering')
        if not math.isclose(m['acceptedEventsPerSecond'], 1000000 / m['elapsedSeconds'], rel_tol=1e-10):
            raise RuntimeError('Throughput/drain boundary mismatch')
        env = json.loads((artifact / 'environment.json').read_text())
        if env['sourceRevision'] != FROZEN or env['limits']['partitions'] != 8 or env['limits']['fullKeys'] != 10000:
            raise RuntimeError('Wrong/missing environment provenance')
        if not (artifact / 'gc.log').is_file() or (artifact / 'gc.log').stat().st_size == 0:
            raise RuntimeError('Missing full-run GC log')
        self.recordings(artifact)
        return r

    def start_dependencies(self):
        self.verify_inputs()
        self.quiet()
        self.started_dependencies = True  # Also clean partially successful Compose up.
        self.phase('dependencies-up', COMPOSE + ['up', '-d', '--pull', 'never', '--wait', '--wait-timeout', '180'], 220)
        self.command('owned-containers', COMPOSE + ['ps', '-a', '--format', 'json'])
        ids = self.command('owned-container-ids', COMPOSE + ['ps', '-a', '-q', 'postgres', 'kafka']).stdout.split()
        details = json.loads(self.command('owned-container-inspect', ['docker', 'inspect', *ids]).stdout)
        if len(details) != 2:
            raise RuntimeError('Expected exactly two own dependency containers')
        inventory = self.container_inventory()
        if {entry['service'] for entry in inventory.values()} != set(IMAGES):
            raise RuntimeError('Both dependency host PID inventories are required')
        self.summary['dependencies'] = [{'id': c['Id'], 'service': c['Config']['Labels']['com.docker.compose.service'],
                                         'imageId': c['Image'], 'configuredImage': c['Config']['Image'], 'mounts': c['Mounts']} for c in details]
        self.save()
        self.quiet()

    def teardown(self):
        if self.last_active:
            terminate_owned(self.last_active, self.all_owned_processes)
        if not self.started_dependencies:
            return {'attempted': False, 'reason': 'No dependencies started/adopted'}
        errors = []
        try:
            self.command('dependency-logs', COMPOSE + ['logs', '--no-color', '--tail', '500'], timeout=20, check=False)
        except Exception as exc:
            errors.append(str(exc))
        stop_code = None
        try:
            stop_code = self.command('dependencies-stop', COMPOSE + ['stop', '--timeout', '30', 'postgres', 'kafka'], timeout=100, check=False).returncode
            if stop_code:
                errors.append(f'Compose stop exit{stop_code}')
        except Exception as exc:
            errors.append(str(exc))
        ids = []
        try:
            ids = self.command('remaining-owned-running', COMPOSE + ['ps', '-q', '--status', 'running', 'postgres', 'kafka'], timeout=20).stdout.split()
            if ids:
                # Exact scoped container IDs, no prune/down/volumes/deletion.
                self.command('fallback-owned-stop', ['docker', 'stop', '--time', '10', *ids], timeout=45)
                errors.append('Fallback own-container stop was needed')
            still = self.command('after-teardown-running', COMPOSE + ['ps', '-q', '--status', 'running', 'postgres', 'kafka'], timeout=20).stdout.split()
            if still:
                errors.append('Owned containers still running: ' + ','.join(still))
            self.command('after-teardown-state', COMPOSE + ['ps', '-a', '--format', 'json'], timeout=20, check=False)
        except Exception as exc:
            errors.append(str(exc))
        return {'attempted': True, 'composeStopExitCode': stop_code, 'fallbackContainerIds': ids, 'errors': errors, 'success': not errors}

    def run(self):
        outcome = 1
        try:
            self.preflight()
            old = self.phase('engine-original', ['bash', str(WORK / 'run-engine-baseline.sh')], 660, 'engine-baseline-*')
            current = self.phase('engine-current', ['bash', str(PROJECT / 'scripts/benchmark-engine.sh')], 660, 'engine-[0-9]*')
            self.start_dependencies()
            completed = []
            for number in range(1, 4):
                completed.append(self.phase(f'durable-full-{number}', ['bash', str(PROJECT / 'scripts/benchmark.sh'), 'full'], 2160, 'durable-full-*'))
            results = [json.loads((p / 'result.json').read_text()) for p in completed]
            if len({r['run'] for r in results}) != 3 or len({r['database'] for r in results}) != 3:
                raise RuntimeError('Full runs did not use independent fresh database/run identities')
            if len({r['resultDigest'] for r in results}) != 1:
                raise RuntimeError('Independent aggregate results differ across identical seeded full workloads')
            self.summary['completedMeasurementDirectories'] = [str(old), str(current), *map(str, completed)]
            self.summary['status'] = 'MEASUREMENTS_PASSED'
            outcome = 0
        except BaseException as exc:
            self.summary['status'] = 'FAILED'
            self.summary['error'] = f'{type(exc).__name__}: {exc}'
            self.emit(self.summary['error'])
            outcome = 130 if isinstance(exc, (Interrupted, KeyboardInterrupt)) else 1
        finally:
            # A second TERM must not interrupt the cleanup of the first signal.
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            cleanup = self.teardown()
            self.summary['teardown'] = cleanup
            if cleanup.get('attempted') and not cleanup.get('success'):
                outcome = 1
                self.summary['status'] = 'FAILED_TEARDOWN'
            self.summary['ended'] = now()
            self.summary['exitCode'] = outcome
            self.save()
            self.emit(f'FINAL exit={outcome} {self.output / "summary.json"}')
        return outcome


def main():
    reservation = os.environ.get('PULSE_QUIET_RESERVATION', '')
    if not re.fullmatch('[A-Za-z0-9_-]{1,80}', reservation):
        print('Set PULSE_QUIET_RESERVATION only after coordinator grants quiet and holds all three build locks.', file=sys.stderr)
        return 2
    os.umask(0o077)
    WORK.mkdir(exist_ok=True)
    with (WORK / 'quiet-runner.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('Another A04 quiet runner owns this project.', file=sys.stderr)
            return 2
        def interrupted(sig, frame):
            raise Interrupted(signal.Signals(sig).name)
        signal.signal(signal.SIGINT, interrupted)
        signal.signal(signal.SIGTERM, interrupted)
        return Runner(reservation).run()


if __name__ == '__main__':
    sys.exit(main())
