"""Finite Python-child tests. Defined offline; execute only in a granted functional window."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from reference_owned_process import Cancellation, OwnedProcess, identity


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load('prepare9', 'prepare-reference-9.py')
execute = load('execute9', 'execute-prepared-reference-9.py')


def wait_file(path, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.exists() and path.stat().st_size:
            return path.read_text()
        time.sleep(0.02)
    raise AssertionError('Child readiness was not observed: ' + str(path))


class OwnedProcessTest(unittest.TestCase):
    def test_success_has_no_owned_survivor(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = OwnedProcess([sys.executable, '-c', 'print(42)'], Path(directory) / 'log', 5, Cancellation())
            result = runner.run()
            self.assertEqual(result['exitCode'], 0)
            self.assertTrue(result['cleanupComplete'])
            self.assertEqual(result['remainingOwnedProcesses'], [])

    def test_timeout_reaps_nested_session_and_signal_ignoring_child(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            ready = directory / 'child'
            child = 'import os,time,signal;os.setsid();signal.signal(signal.SIGTERM,signal.SIG_IGN);open(' + repr(str(ready)) + ',"w").write(str(os.getpid()));time.sleep(60)'
            parent = 'import subprocess,sys,time;subprocess.Popen([sys.executable,"-c",' + repr(child) + ']);time.sleep(60)'
            runner = OwnedProcess([sys.executable, '-c', parent], directory / 'log', 0.5, Cancellation())
            result = runner.run()
            self.assertEqual(result['outcome'], 'TIMEOUT')
            self.assertTrue(result['cleanupComplete'])
            self.assertIsNone(identity(int(ready.read_text())))

    def test_successful_leader_cannot_abandon_live_orphan(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            ready = directory / 'child'
            child = 'import os,time;open(' + repr(str(ready)) + ',"w").write(str(os.getpid()));time.sleep(60)'
            parent = ('import subprocess,sys,time;from pathlib import Path;subprocess.Popen([sys.executable,"-c",'
                      + repr(child) + ']);p=Path(' + repr(str(ready)) + ');'
                      '\nwhile not p.exists():time.sleep(.01)')
            result = OwnedProcess([sys.executable, '-c', parent], directory / 'log', 5, Cancellation()).run()
            self.assertEqual(result['exitCode'], 0)
            self.assertTrue(result['cleanupComplete'])
            self.assertIsNone(identity(int(ready.read_text())))

    def test_actual_term_of_helper_cleans_nested_child(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            ready = directory / 'child'
            child = 'import os,time;open(' + repr(str(ready)) + ',"w").write(str(os.getpid()));time.sleep(60)'
            parent = 'import subprocess,sys,time;subprocess.Popen([sys.executable,"-c",' + repr(child) + ']);time.sleep(60)'
            script = ('import sys,json;from pathlib import Path;sys.path.insert(0,' + repr(str(HERE)) + ');'
                      'from reference_owned_process import Cancellation,OwnedProcess;'
                      'c=Cancellation();c.install();r=OwnedProcess(' + repr([sys.executable, '-c', parent])
                      + ',Path(' + repr(str(directory / 'log')) + '),20,c);x=r.run();'
                      'Path(' + repr(str(directory / 'result')) + ').write_text(json.dumps(x))')
            process = subprocess.Popen([sys.executable, '-c', script])
            try:
                child_pid = int(wait_file(ready))
                process.terminate()
                self.assertEqual(process.wait(timeout=10), 0)
                result = json.loads((directory / 'result').read_text())
                self.assertEqual(result['outcome'], 'CANCELLED')
                self.assertTrue(result['cleanupComplete'])
                self.assertIsNone(identity(child_pid))
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()

    def test_launch_exception_has_no_owned_survivor(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = OwnedProcess(['/path/that/does/not/exist'], Path(directory) / 'log', 1, Cancellation())
            with self.assertRaises(FileNotFoundError):
                runner.run()
            self.assertTrue(runner.result['cleanupComplete'])


class PreparationTest(unittest.TestCase):
    def fixture(self, root):
        scratch = root / '.work/A08'
        scratch.mkdir(parents=True)
        source, artifact, agent = root / 'Example.java', root / 'runtime.jar', root / 'agent.jar'
        source.write_bytes(b'certified-source')
        artifact.write_bytes(b'certified-runtime')
        agent.write_bytes(b'certified-agent')
        sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
        context = {'exitCode': 0, 'sourceFingerprint': 'a' * 64,
                   'agentSha256': sha(agent), 'collectionConfiguration': 'v3-allocation50-park-class-only'}
        for name, result_name, result in [('repair-cause-v3', 'summary.json', {'passed': True, 'freshJvmRuns': 42}),
                                          ('repair-observer-stress-v3', 'completed.json', {'stress': {'passed': True, 'elapsedSeconds': 1200}})]:
            directory = scratch / name
            directory.mkdir()
            (directory / 'context.json').write_text(json.dumps(context))
            (directory / result_name).write_text(json.dumps(result))
        for name in ('prepare-reference-9.py', 'execute-prepared-reference-9.py', 'reference_owned_process.py'):
            (scratch / name).write_text('frozen-helper')
        java_home = root / 'jdk'
        (java_home / 'bin').mkdir(parents=True)
        (java_home / 'bin/java').write_text('not-executed')
        (java_home / 'release').write_text('JAVA_VERSION="fixture"')
        checkpoint = scratch / 'repair-v3-checkpoint.json'
        checkpoint.write_text(json.dumps({'exitCode': 0, 'sourceFiles': {'Example.java': sha(source)},
                                         'runtimeArtifacts': [{'path': str(artifact), 'sha256': sha(artifact)}],
                                         'agentArtifact': {'path': str(agent), 'sha256': sha(agent)},
                                         'buildInputFingerprint': 'a' * 64,
                                         'collectionConfiguration': context['collectionConfiguration'],
                                         'eventSettings': {}}))
        return scratch, java_home, checkpoint, source, agent

    def test_snapshot_copy_identity_and_ready_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scratch, java_home, checkpoint, source, agent = self.fixture(root)
            with patch.multiple(prepare, ROOT=root, INPUTS=scratch / 'inputs', OUTPUT=scratch / 'out',
                                CHECKPOINT=checkpoint, EXPECTED_CHECKPOINT_SHA256=prepare.digest(checkpoint.read_bytes())), \
                    patch.dict(os.environ, {'JAVA_HOME': str(java_home)}), \
                    patch.object(prepare.subprocess, 'check_output', return_value='fixture-revision'):
                prepare.main()
                manifest = json.loads((scratch / 'inputs/manifest.json').read_text())
                self.assertEqual(manifest['agentSha256'], prepare.digest(agent.read_bytes()))
                self.assertEqual(manifest['functionalCertificateSha256'], prepare.digest(checkpoint.read_bytes()))
                self.assertEqual((scratch / 'inputs/threadlens-agent.jar').read_bytes(), b'certified-agent')

    def test_changed_original_never_becomes_certified_copy(self):
        for target_kind in ('agent', 'source', 'proof', 'checkpoint'):
            with self.subTest(target=target_kind), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                scratch, java_home, checkpoint, source, agent = self.fixture(root)
                target = {'agent': agent, 'source': source, 'proof': scratch / 'repair-cause-v3/summary.json',
                          'checkpoint': checkpoint}[target_kind]
                original_snapshot = prepare.snapshot
                changed = False

                def racing_snapshot(path, expected=None):
                    nonlocal changed
                    result = original_snapshot(path, expected)
                    if Path(path) == target and not changed:
                        changed = True
                        target.write_bytes(b'changed-after-snapshot')
                    return result

                with patch.multiple(prepare, ROOT=root, INPUTS=scratch / 'inputs', OUTPUT=scratch / 'out',
                                    CHECKPOINT=checkpoint, EXPECTED_CHECKPOINT_SHA256=prepare.digest(checkpoint.read_bytes())), \
                        patch.dict(os.environ, {'JAVA_HOME': str(java_home)}), \
                        patch.object(prepare, 'snapshot', side_effect=racing_snapshot), \
                        patch.object(prepare.subprocess, 'check_output', return_value='fixture-revision'):
                    with self.assertRaises(RuntimeError):
                        prepare.main()
                    self.assertFalse((scratch / 'inputs/manifest.json').exists())
                    copied_agent = scratch / 'inputs/threadlens-agent.jar'
                    if copied_agent.exists():
                        self.assertEqual(copied_agent.read_bytes(), b'certified-agent')


class InstrumentationEvidenceTest(unittest.TestCase):
    def trial(self, directory, operations=10, accepted_per_subject=15, drops=0):
        run = Path(directory)
        (run / 'agent').mkdir()
        (run / 'threadlens.yaml').write_text(
            'allowPackages: [io.github.cheshmakzanoon.portfolio.threadlens.workloads]\n'
            'methodNames: [plantedCpuHotspot, plantedAllocationPressure, plantedMonitorContention]\n'
            'maxMethods: 3\nqueueCapacity: 65536\noutputDirectory: ' + str((run / 'agent').resolve()) + '\n')
        methods = sorted(execute.METHODS)
        (run / 'agent/methods.tsv').write_text('methodId\tidentity\n' + ''.join(f'{i}\t{name}\n' for i, name in enumerate(methods)))
        (run / 'agent/timing-summary.tsv').write_text(
            'methodId\texits\texceptionalExits\ttotalNanos\tp50Nanos\tp95Nanos\tp99Nanos\n'
            + ''.join(f'{i}\t{accepted_per_subject}\t0\t100\t1\t2\t3\n'
                      for i, name in enumerate(methods) if 'Monitor' not in name))
        status = {'statusVersion': '2', 'collectionHealthy': 'true', 'queueDrops': str(drops),
                  'shutdownDiscarded': '0', 'incomplete': str(drops > 0).lower(), 'probeFailures': '0',
                  'methodOverflow': '0', 'timingHistogramOverflow': '0', 'durationClipped': '0',
                  'observedTimingEvents': str(2 * accepted_per_subject),
                  'acceptedTimingReservations': str(2 * accepted_per_subject),
                  'producerQuiesced': 'true', 'unfinishedProbeExits': '0', 'rawTimingRowsOmitted': '0',
                  'activeMethodIdLimit': '3', 'activeQueueCapacity': '65536', 'skips': '{}', 'statusComplete': 'true'}
        (run / 'agent/observer-status.txt').write_text(''.join(f'{k}={v}\n' for k, v in status.items()))
        return run, {'kind': 'allocation', 'operations': operations}

    def test_warmup_inclusive_counts_are_lower_bound_not_exact_operation_equality(self):
        with tempfile.TemporaryDirectory() as directory:
            run, measurement = self.trial(directory)
            result = execute.validate_instrumented(run, measurement)
            self.assertEqual(result['warmupInclusiveAcceptedPlusDrops'], 30)
            self.assertEqual(result['minimumMeasuredSelectedCalls'], 20)

    def test_positive_but_tiny_count_is_insufficient(self):
        with tempfile.TemporaryDirectory() as directory:
            run, measurement = self.trial(directory, accepted_per_subject=1)
            with self.assertRaisesRegex(RuntimeError, 'below measured'):
                execute.validate_instrumented(run, measurement)

    def test_true_drops_are_accepted_only_with_explicit_incompleteness(self):
        with tempfile.TemporaryDirectory() as directory:
            run, measurement = self.trial(directory, accepted_per_subject=5, drops=10)
            result = execute.validate_instrumented(run, measurement)
            self.assertEqual(result['warmupInclusiveAcceptedPlusDrops'], 20)
            path = run / 'agent/observer-status.txt'
            path.write_text(path.read_text().replace('incomplete=true', 'incomplete=false'))
            with self.assertRaisesRegex(RuntimeError, 'Drops/incomplete'):
                execute.validate_instrumented(run, measurement)

    def test_wrong_registered_selection_and_missing_complete_status_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            run, measurement = self.trial(directory)
            registry = run / 'agent/methods.tsv'
            original = registry.read_text()
            registry.write_text(original.replace('plantedCpuHotspot(I)J', 'someOtherMethod(I)J'))
            with self.assertRaisesRegex(RuntimeError, 'identity set'):
                execute.validate_instrumented(run, measurement)
            registry.write_text(original)
            path = run / 'agent/observer-status.txt'
            path.write_text(path.read_text().replace('rawTimingRowsOmitted=0\n', ''))
            with self.assertRaisesRegex(RuntimeError, 'Required final status'):
                execute.validate_instrumented(run, measurement)


class DurableOutcomeTest(unittest.TestCase):
    def test_failed_child_leaves_durable_nonzero_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            java_home = directory / 'jdk'
            (java_home / 'bin').mkdir(parents=True)
            java = java_home / 'bin/java'
            java.symlink_to(sys.executable)
            release = java_home / 'release'
            release.write_text('fixture')
            inputs = directory / 'inputs'
            inputs.mkdir()
            for name in ('threadlens-agent.jar', 'source.tar.gz', 'functional-certificate.json'):
                (inputs / name).write_bytes(name.encode())
            manifest = {'command': [str(java), '-c', 'raise SystemExit(7)', str(directory / 'out'), 'ignored'],
                        'runtimeArtifacts': [], 'validationProofs': [], 'executionHelpers': {},
                        'agentSha256': execute.sha(inputs / 'threadlens-agent.jar'),
                        'sourceArchiveSha256': execute.sha(inputs / 'source.tar.gz'),
                        'functionalCertificateSha256': execute.sha(inputs / 'functional-certificate.json'),
                        'jdkReleaseSha256': execute.sha(release)}
            (inputs / 'manifest.json').write_text(json.dumps(manifest))
            with patch.object(execute, 'INPUTS', inputs), patch.dict(os.environ, {'THREADLENS_REFERENCE_RESERVATION': 'finite-helper-test'}):
                self.assertEqual(execute.main(), 7)
            observed = json.loads((inputs / 'manifest.json').read_text())
            self.assertEqual(observed['executorExitCode'], 7)
            self.assertEqual(observed['state'], 'FAILED')
            self.assertIn('completedUtc', observed)
            self.assertTrue(observed['ownedProcess']['cleanupComplete'])


if __name__ == '__main__':
    unittest.main()
