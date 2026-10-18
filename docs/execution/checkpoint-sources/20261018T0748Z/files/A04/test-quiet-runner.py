#!/usr/bin/env python3
"""Python-only control-flow tests. Synthetic fixtures are NOT benchmark evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('pulse_quiet_runner', Path(__file__).with_name('run-quiet.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class SupervisionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='pulsestream-runner-test-')
        self.root = Path(self.directory.name)
        self.runner = m.Runner.__new__(m.Runner)
        self.runner.output = self.root
        self.runner.summary = {'phases': []}
        self.runner.active = None
        self.runner.last_active = None
        self.runner.started_dependencies = False
        self.runner.container_ids = set()
        self.runner.container_processes = {}
        self.runner.phase_processes = {}
        self.runner.all_owned_processes = {}
        self.runner.locks = Mock()
        self.runner.save = Mock()
        self.runner.emit = Mock()

    def tearDown(self):
        self.directory.cleanup()

    def test_missing_explicit_reservation_does_not_create_runner_or_execute_commands(self):
        with patch.dict(m.os.environ, {}, clear=True), patch.object(m, 'Runner') as runner:
            self.assertEqual(2, m.main())
            runner.assert_not_called()

    def own_container_commands(self, image=None, top='PID\n44\n'):
        identity = 'a' * 64
        fields = '|'.join([identity, 'java-portfolio-pulsestream', 'kafka', image or m.IMAGES['kafka'], '44', 'true'])
        responses = [identity + '|java-portfolio-pulsestream|kafka\n', fields, top]
        return Mock(side_effect=[types.SimpleNamespace(stdout=value) for value in responses])

    def test_startup_monitor_recognizes_only_exact_owned_container_pids(self):
        self.runner.started_dependencies = True
        self.runner.command = self.own_container_commands()
        snapshot = {
            1337: {'ppid': 1, 'start': '10', 'comm': 'python3', 'state': 'R'},
            44: {'ppid': 1, 'start': '20', 'comm': 'java', 'namespace': None, 'executable': None, 'state': 'S'}}
        with patch.object(m.os, 'getpid', return_value=1337), patch.object(m, 'processes', return_value=snapshot):
            self.runner.quiet()
        self.assertEqual({'a' * 64}, self.runner.container_ids)
        self.assertEqual('20', self.runner.container_processes[44]['start'])

    def test_optional_proc_permission_denials_keep_java_stat_and_comm(self):
        proc = self.root / '44'
        proc.mkdir()
        (proc / 'stat').write_text('44 (java) S 1 ' + '0 ' * 17 + '1234 0')
        (proc / 'comm').write_text('java\n')
        with patch.object(m.os, 'readlink', side_effect=PermissionError('container UID')):
            record = m.process_record(proc)
        self.assertEqual('java', record['comm'])
        self.assertEqual('1234', record['start'])
        self.assertIsNone(record['executable'])
        self.assertEqual({'executable': 'PermissionError', 'namespace': 'PermissionError'}, record['readErrors'])

    def test_unreadable_optional_proc_links_do_not_exempt_foreign_java(self):
        snapshot = {99: {'ppid': 1, 'start': '20', 'comm': 'java', 'namespace': None, 'executable': None, 'state': 'S'}}
        with patch.object(m, 'processes', return_value=snapshot):
            with self.assertRaisesRegex(RuntimeError, 'Unexpected JVM'):
                self.runner.quiet(containers=False)

    def test_wrong_pinned_image_is_not_adopted(self):
        self.runner.started_dependencies = True
        self.runner.command = self.own_container_commands(image='apache/kafka:latest')
        with self.assertRaisesRegex(RuntimeError, 'identity/image/state'):
            self.runner.container_inventory()
        self.assertFalse(self.runner.container_ids)

    def test_docker_top_must_include_inspected_init_pid(self):
        self.runner.started_dependencies = True
        self.runner.command = self.own_container_commands(top='PID\n45\n')
        with self.assertRaisesRegex(RuntimeError, 'init absent'):
            self.runner.container_inventory()
        self.assertFalse(self.runner.container_ids)

    def test_reused_container_pid_is_not_exempted(self):
        self.runner.started_dependencies = True
        self.runner.container_inventory = Mock(return_value={44: {'start': 'original'}})
        snapshot = {44: {'ppid': 1, 'start': 'replacement', 'comm': 'java', 'state': 'S'}}
        with patch.object(m, 'processes', return_value=snapshot):
            with self.assertRaisesRegex(RuntimeError, 'Unexpected JVM'):
                self.runner.quiet(_retry=False)

    def test_unknown_start_ticks_never_verify_a_container_java_identity(self):
        self.runner.started_dependencies = True
        self.runner.container_inventory = Mock(return_value={44: {'start': None}})
        snapshot = {44: {'ppid': 1, 'start': None, 'comm': 'java', 'state': 'S'}}
        with patch.object(m, 'processes', return_value=snapshot):
            with self.assertRaisesRegex(RuntimeError, 'Unexpected JVM'):
                self.runner.quiet(_retry=False)

    def test_same_namespace_foreign_java_is_not_exempted(self):
        self.runner.started_dependencies = True
        self.runner.container_inventory = Mock(return_value={44: {'start': 'owned'}})
        snapshot = {99: {'ppid': 1, 'start': 'foreign', 'comm': 'java', 'namespace': 'shared', 'state': 'S'}}
        with patch.object(m, 'processes', return_value=snapshot):
            with self.assertRaisesRegex(RuntimeError, 'Unexpected JVM'):
                self.runner.quiet(_retry=False)

    def test_inventory_is_replaced_not_accumulated(self):
        self.runner.started_dependencies = True
        self.runner.container_processes = {44: {'start': 'old'}}
        self.runner.command = Mock(return_value=types.SimpleNamespace(stdout=''))
        self.assertEqual({}, self.runner.container_inventory())
        self.assertEqual({}, self.runner.container_processes)

    def test_foreign_container_rejected_before_adoption(self):
        self.runner.started_dependencies = True
        self.runner.command = Mock(return_value=types.SimpleNamespace(stdout='foreign|another-project|kafka\n'))
        with self.assertRaisesRegex(RuntimeError, 'Unexpected running container'):
            self.runner.quiet()
        self.assertFalse(self.runner.container_ids)

    def test_foreign_jvm_is_not_ignored(self):
        snapshot = {99: {'ppid': 1, 'start': '20', 'comm': 'java', 'namespace': 'foreign', 'state': 'S'}}
        with patch.object(m, 'processes', return_value=snapshot):
            with self.assertRaisesRegex(RuntimeError, 'Unexpected JVM'):
                self.runner.quiet(containers=False)

    def test_nonzero_phase_preserves_exit_and_cleans_owned_descendants(self):
        self.runner.verify_inputs = Mock()
        self.runner.quiet = Mock()
        self.runner.disk = Mock()
        self.runner.env = {}
        child = Mock(returncode=17, pid=991)
        child.poll.return_value = 17
        with patch.object(m.subprocess, 'Popen', return_value=child), patch.object(m, 'terminate_owned') as terminate:
            with self.assertRaisesRegex(RuntimeError, 'exited 17'):
                self.runner.phase('deliberate-unit-fixture-failure', ['never-executed'], 1)
            terminate.assert_called_once_with(child, {})
        self.assertEqual(17, self.runner.summary['phases'][0]['exitCode'])
        self.assertIn('error', self.runner.summary['phases'][0])
        self.assertIsNone(self.runner.active)

    def test_failed_preflight_still_finalizes_failure_without_starting_dependencies(self):
        self.runner.preflight = Mock(side_effect=RuntimeError('deliberate fixture refusal'))
        self.runner.teardown = Mock(return_value={'attempted': False})
        with patch.object(m.signal, 'signal'):
            self.assertEqual(1, self.runner.run())
        self.assertEqual('FAILED', self.runner.summary['status'])
        self.runner.teardown.assert_called_once()

    def test_teardown_failure_cannot_return_success(self):
        self.runner.preflight = Mock()
        self.runner.phase = Mock(side_effect=RuntimeError('deliberate fixture failure'))
        self.runner.teardown = Mock(return_value={'attempted': True, 'success': False, 'errors': ['fixture stop failure']})
        with patch.object(m.signal, 'signal'):
            self.assertEqual(1, self.runner.run())
        self.assertEqual('FAILED_TEARDOWN', self.runner.summary['status'])

    def test_three_fork_jmh_fixture_is_required(self):
        artifact = self.root / 'synthetic-jmh-schema-fixture'
        artifact.mkdir()
        result = {'benchmark': 'x.EngineBenchmark.freshBatch', 'forks': 2, 'warmupIterations': 2, 'measurementIterations': 3}
        (artifact / 'jmh.json').write_text(json.dumps([result]))
        with self.assertRaisesRegex(RuntimeError, 'fork/warmup/measurement'):
            self.runner.validate_jmh(artifact)

    def test_durable_count_downgrade_is_rejected_before_recording_acceptance(self):
        artifact = self.root / 'synthetic-durable-schema-fixture'
        artifact.mkdir()
        result = {'sourceRevision': m.FROZEN, 'workload': 'durable-v1-full', 'seed': 4404,
                  'measurements': {'acceptedMeasurements': 10000, 'duplicateCount': 10}, 'assertions': {'passed': 6, 'failed': 0}}
        (artifact / 'result.json').write_text(json.dumps(result))
        with self.assertRaisesRegex(RuntimeError, 'Prescribed counts'):
            self.runner.validate_full(artifact)

    def test_cleanup_never_kills_a_reused_foreign_pid(self):
        child = Mock(pid=55)
        child.poll.return_value = 0
        child.wait.return_value = 0
        snapshot = {55: {'ppid': 1, 'start': 'new-process', 'comm': 'java', 'namespace': 'host', 'state': 'R'}}
        with patch.object(m, 'processes', return_value=snapshot), patch.object(m.os, 'kill') as kill:
            m.terminate_owned(child, {55: 'old-owned-process'})
        kill.assert_not_called()

    def test_cleanup_tracks_nested_session_descendants(self):
        child = Mock(pid=100)
        child.poll.return_value = None
        child.wait.return_value = 0
        snapshot = {
            100: {'ppid': 1, 'start': 'a', 'comm': 'bash', 'namespace': 'host', 'state': 'S'},
            101: {'ppid': 100, 'start': 'b', 'comm': 'java', 'namespace': 'host', 'state': 'S'}}
        with patch.object(m, 'processes', side_effect=[snapshot, snapshot, {}]), patch.object(m.os, 'kill') as kill:
            m.terminate_owned(child, {100: 'a'})
        self.assertEqual({100, 101}, {call.args[0] for call in kill.call_args_list})
        self.assertTrue(all(call.args[1] == m.signal.SIGTERM for call in kill.call_args_list))

    def test_every_build_lock_must_be_reserved(self):
        slots = self.root / '.work/build-slots'
        slots.mkdir(parents=True)
        for i in range(3):
            (slots / f'{i}.lock').touch()
        with patch.object(m, 'ROOT', self.root), patch.object(m.fcntl, 'flock', side_effect=[BlockingIOError(), BlockingIOError(), BlockingIOError()]) as flock:
            m.Runner.locks(self.runner)
            self.assertEqual(3, flock.call_count)
        with patch.object(m, 'ROOT', self.root), patch.object(m.fcntl, 'flock', return_value=None):
            with self.assertRaisesRegex(RuntimeError, 'not reserved'):
                m.Runner.locks(self.runner)

    def test_low_disk_refuses_without_deleting_data(self):
        with patch.object(m.shutil, 'disk_usage', return_value=types.SimpleNamespace(free=1)):
            with self.assertRaisesRegex(RuntimeError, 'no files deleted'):
                self.runner.disk(3 * m.GIB)

    def test_failed_compose_stop_keeps_cleanup_failed_and_scoped(self):
        self.runner.started_dependencies = True
        commands = []
        def command(name, args, **options):
            commands.append(args)
            return types.SimpleNamespace(returncode=1 if name == 'dependencies-stop' else 0, stdout='')
        self.runner.command = command
        result = self.runner.teardown()
        self.assertFalse(result['success'])
        self.assertIn('Compose stop exit1', result['errors'])
        self.assertTrue(any(args[-2:] == ['postgres', 'kafka'] for args in commands))
        self.assertFalse(any('prune' in args or '--volumes' in args or 'down' in args for args in commands))


if __name__ == '__main__':
    unittest.main(verbosity=2)
