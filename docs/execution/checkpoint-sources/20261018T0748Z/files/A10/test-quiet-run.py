#!/usr/bin/env python3
"""Control-flow tests with synthetic JSON and stub phases. Never starts Java/Docker."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('quiet_runner', Path(__file__).with_name('quiet-run.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class QuietRunnerTest(unittest.TestCase):
    def test_measurement_failure_still_executes_scoped_down_and_verification(self):
        self.check_failure(RuntimeError('synthetic benchmark failure'))

    def test_interruption_still_executes_scoped_down_and_verification(self):
        self.check_failure(InterruptedError('synthetic coordinator signal'))

    def test_log_copy_failure_cannot_prevent_scoped_down(self):
        self.check_failure(RuntimeError('synthetic benchmark failure'), fail_preserve=True)

    def check_failure(self, failure, fail_preserve=False):
        with tempfile.TemporaryDirectory(prefix='runner-test-', dir=m.WORK) as directory:
            base = Path(directory)
            output = base / 'output'
            output.mkdir()
            runtime = base / 'synthetic-runtime'
            runtime.mkdir()
            (runtime / 'build-stamp').write_text(m.EXPECTED_SOURCE)
            (runtime / 'running-source').write_text(m.EXPECTED_SOURCE)
            (runtime / 'service.pid').write_text('999999999')
            project = base / 'synthetic-project'
            (project / 'evidence').mkdir(parents=True)
            (project / 'evidence/artifacts-current.json').write_text('{}')
            phases = []
            def phase(name, command, timeout_seconds, measurement=False, cleanup=False):
                phases.append(name)
                (output / (name + '.log')).write_text('')
                if name == '01-artifact-preflight':
                    for filename in ('final-artifact-fixture.json', 'final-jmh-discovery.log'):
                        (base / filename).write_text('SYNTHETIC TEST ONLY')
                if name == '04-full-jmh-and-corpus':
                    raise failure
            censuses = []
            def java_census():
                owned = [{'pid': 999999999, 'command': 'SYNTHETIC OWNED JAVA PID; NO PROCESS STARTED'}] if '02-up' in phases and '05-down' not in phases else []
                censuses.append([r['pid'] for r in owned])
                return owned
            with patch.object(m, 'RUNTIME', runtime), patch.object(m, 'PROJECT', project), patch.object(m, 'WORK', base), patch.object(m, 'java_processes', side_effect=java_census), patch.object(m, 'port_open', return_value=False):
                runner = m.Runner(output, 1024)
                runner.phase = phase
                if fail_preserve:
                    original_preserve = runner.preserve_logs
                    def preserve(name):
                        if name == 'before-down':
                            raise OSError('synthetic full disk while copying logs')
                        original_preserve(name)
                    runner.preserve_logs = preserve
                self.assertEqual(1, runner.run())
                self.assertEqual('FAIL', runner.state['status'])
                self.assertEqual([[], [999999999]], censuses[:2])
                self.assertIn('04-full-jmh-and-corpus', phases)
                self.assertEqual(repr(failure), runner.state['error'])
                if fail_preserve:
                    self.assertEqual(repr(OSError('synthetic full disk while copying logs')), runner.state['beforeDownPreservationError'])
                self.assertIn('05-down', phases)
                self.assertIn('06-confirm-containers', phases)
                self.assertLess(phases.index('04-full-jmh-and-corpus'), phases.index('05-down'))
                self.assertLess(phases.index('05-down'), phases.index('06-confirm-containers'))
                self.assertTrue((output / 'raw-manifest.json').is_file())

    def test_service_exit_before_forced_kill_still_finalizes_evidence(self):
        self.check_kill_race(ProcessLookupError('synthetic service already stopped'), expected_exit=0)

    def test_other_kill_error_is_retained_and_cleanup_checks_continue(self):
        self.check_kill_race(PermissionError('synthetic denied kill'), expected_exit=1)

    def check_kill_race(self, kill_error, expected_exit):
        with tempfile.TemporaryDirectory(prefix='runner-test-', dir=m.WORK) as directory:
            base = Path(directory)
            output = base / 'output'
            output.mkdir()
            runtime = base / 'synthetic-runtime'
            runtime.mkdir()
            for name, value in (('build-stamp', m.EXPECTED_SOURCE), ('running-source', m.EXPECTED_SOURCE), ('service.pid', '999999999')):
                (runtime / name).write_text(value)
            project = base / 'synthetic-project'
            (project / 'evidence').mkdir(parents=True)
            (project / 'evidence/artifacts-current.json').write_text('{}')
            phases = []
            def phase(name, command, timeout_seconds, measurement=False, cleanup=False):
                phases.append(name)
                (output / (name + '.log')).write_text('')
                if name == '01-artifact-preflight':
                    for filename in ('final-artifact-fixture.json', 'final-jmh-discovery.log'):
                        (base / filename).write_text('SYNTHETIC TEST ONLY')
            owned = [{'pid': 999999999, 'command': str(project / 'routeweaver-service/target/routeweaver-service-1.0.0-SNAPSHOT-exec.jar')}]
            def java_census():
                return owned if '02-up' in phases else []
            with patch.object(m, 'RUNTIME', runtime), patch.object(m, 'PROJECT', project), patch.object(m, 'WORK', base), patch.object(m, 'java_processes', side_effect=java_census), patch.object(m, 'port_open', return_value=False) as ports, patch.object(m, 'validate_results', return_value={'SYNTHETIC_TEST_ONLY': True}), patch.object(m.time, 'sleep'), patch.object(m.os, 'kill', side_effect=kill_error) as kill:
                runner = m.Runner(output, 1024)
                runner.phase = phase
                self.assertEqual(expected_exit, runner.run())
                kill.assert_called_once_with(999999999, m.signal.SIGKILL)
                self.assertIn('04-full-jmh-and-corpus', phases)
                self.assertIn('05-down', phases)
                self.assertIn('06-confirm-containers', phases)
                self.assertGreaterEqual(ports.call_count, 4)
                if expected_exit == 0:
                    self.assertEqual('PASS', runner.state['status'])
                    self.assertTrue(runner.state['serviceStoppedBeforeForcedKill'])
                    self.assertNotIn('serviceKillError', runner.state)
                else:
                    self.assertEqual('FAIL', runner.state['status'])
                    self.assertEqual(repr(kill_error), runner.state['serviceKillError'])
                self.assertEqual(runner.state['status'], json.loads((output / 'operations.json').read_text())['status'])
                self.assertIn('operations.json', json.loads((output / 'raw-manifest.json').read_text()))

    def test_complete_synthetic_results_pass_but_missing_run_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='runner-test-', dir=m.WORK) as directory:
            target = Path(directory)
            jmh = []
            for method in ('fullReferenceValidation', 'immutableProblemImportValidation'):
                for jobs in (100, 250, 500):
                    jmh.append({'benchmark': 'synthetic.' + method, 'params': {'jobs': str(jobs)}, 'forks': 3, 'warmupIterations': 3, 'measurementIterations': 3, 'warmupTime': '1 s', 'measurementTime': '1 s', 'mode': 'thrpt', 'primaryMetric': {'scoreUnit': 'ops/s', 'score': 1.0, 'rawData': [[1.0] * 3] * 3}, 'secondaryMetrics': {'gc.alloc.rate.norm': {}}})
            (target / 'validation-jmh.json').write_text(json.dumps(jmh))
            corpus = []
            for index in range(10):
                for run in range(3):
                    score = {'hard': 0, 'unassignedPriority': 0, 'operatingCost': 100, 'disruption': 0}
                    corpus.append({'instance': index, 'run': run, 'jobs': [100] * 3 + [250] * 3 + [500] * 3 + [100], 'vehicles': [5] * 3 + [10] * 3 + [25] * 3 + [5], 'seed': 4200 + index, 'solverSeed': 42, 'solverBudgetMillis': 30000, 'solverStepLimit': 100000, 'source': m.EXPECTED_SOURCE, 'serviceSource': m.EXPECTED_SOURCE, 'feasibility': 'FULLY_FEASIBLE', 'inputHash': 'a' * 64, 'scoreTrace': [{'score': score}], 'baseline': score, 'optimized': score, 'sameAssignedDemand': True, 'queueLatencyNanos': 0, 'baselineNanos': 1, 'durableSolveNanos': 1, 'algorithmVersion': 'SYNTHETIC', 'objectiveVersion': 'SYNTHETIC', 'stopCondition': 'SYNTHETIC'})
                    corpus[-1]['jobs'] = corpus[-1]['jobs'][index]
                    corpus[-1]['vehicles'] = corpus[-1]['vehicles'][index]
            (target / 'corpus-raw.json').write_text(json.dumps(corpus))
            (target / 'server-resources.jsonl').write_text(json.dumps({'rssBytes': 1, 'threads': 1}) + '\n')
            for name in ('environment.txt', 'validation-jmh.log', 'gc.log', 'server-gc.log', 'server-process-status.txt'):
                (target / name).write_text('SYNTHETIC TEST ONLY')
            self.assertEqual(30, m.validate_results(target, m.EXPECTED_SOURCE)['systemRows'])
            (target / 'corpus-raw.json').write_text(json.dumps(corpus[:-1]))
            with self.assertRaisesRegex(AssertionError, 'Incomplete 10 x 3'):
                m.validate_results(target, m.EXPECTED_SOURCE)

    def test_low_disk_aborts_before_any_runtime_phase(self):
        with tempfile.TemporaryDirectory(prefix='runner-test-', dir=m.WORK) as directory:
            runner = m.Runner(Path(directory), 4096)
            called = []
            runner.phase = lambda *args, **kwargs: called.append(args)
            with patch.object(m.shutil, 'disk_usage', return_value=type('Disk', (), {'free': 1})()):
                self.assertEqual(1, runner.run())
            self.assertFalse(called)
            self.assertIn('Insufficient free disk', runner.state['error'])


if __name__ == '__main__':
    unittest.main()
