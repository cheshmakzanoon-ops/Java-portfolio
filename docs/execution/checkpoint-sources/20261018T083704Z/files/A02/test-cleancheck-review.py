#!/usr/bin/env python3
"""Offline controls for extracted clean-checkout helper functions.

Never imports the helper's command-line/top-level body. All process, Git, HTTP,
Docker, and copy operations in the exercised functions are replaced with mocks.
Tiny fixture files live only beneath this owner's ignored work directory.
"""
import ast
import hashlib
import json
import os
from pathlib import Path
import signal
import tempfile
import types
import unittest
from unittest.mock import Mock


HERE = Path(__file__).resolve().parent
HELPER = HERE.parent / 'coordinator/validate-clean-checkout.py'


def functions():
    tree = ast.parse(HELPER.read_text())
    safe = ast.Module(body=[node for node in tree.body if isinstance(
        node, (ast.Import, ast.ImportFrom, ast.FunctionDef))], type_ignores=[])
    namespace = {'__name__': 'offline_cleancheck_functions'}
    exec(compile(safe, str(HELPER), 'exec'), namespace)
    return namespace


class CleanCheckControls(unittest.TestCase):
    def setUp(self):
        self.ns = functions()
        self.ns.update(record={}, cleanup_active=False, save=Mock())

    def test_cleanup_attempts_both_stacks_and_stopped_after_first_error(self):
        calls = []

        def command(label, argv, **kwargs):
            self.assertTrue(self.ns['cleanup_active'])
            calls.append(label)
            if label == 'cleanup-clearledger':
                raise PermissionError('injected first-down failure')
            if label == 'stopped-clearledger':
                return 7
            return 0

        self.ns['command'] = command
        with self.assertRaisesRegex(RuntimeError, 'first-down failure'):
            self.ns['cleanup_projects']({'clearledger': 'one', 'pulsestream': 'two'})
        self.assertEqual(calls, ['cleanup-clearledger', 'stopped-clearledger',
                                 'cleanup-pulsestream', 'stopped-pulsestream'])
        self.assertFalse(self.ns['cleanup_active'])
        errors = self.ns['record']['cleanupAttempts'][0]['errors']
        self.assertEqual(len(errors), 2)

    def test_signal_during_cleanup_is_deferred_without_skipping_other_stack(self):
        calls = []

        def command(label, argv, **kwargs):
            calls.append(label)
            if len(calls) == 1:
                self.ns['interrupted'](signal.SIGTERM, None)
            return 0

        self.ns['command'] = command
        self.ns['cleanup_projects']({'clearledger': 'one', 'pulsestream': 'two'})
        self.assertEqual(len(calls), 4)
        self.assertEqual(self.ns['record']['signalsDeferredDuringCleanup'], [signal.SIGTERM])

    def test_adopted_nested_timeout_children_killed_but_reused_and_prior_pids_spared(self):
        parent = os.getpid()
        snapshot = {
            500: {'ppid': parent, 'start': 'owned-timeout', 'state': 'S'},
            501: {'ppid': 500, 'start': 'owned-driver', 'state': 'S'},
            700: {'ppid': parent, 'start': 'prior-service', 'state': 'S'},
            800: {'ppid': 1, 'start': 'reused-external', 'state': 'S'},
        }
        self.ns.update(active_owned={100: 'vanished-leader', 800: 'old-start'},
                       active_excluded={700: 'prior-service'},
                       process_snapshot=lambda: snapshot, reap_children=Mock())
        killed = []

        def kill(pid, signum):
            killed.append((pid, signum))
            snapshot[pid]['state'] = 'Z'

        self.ns['os'] = types.SimpleNamespace(getpid=lambda: parent, kill=kill)
        self.ns['stop_owned'](types.SimpleNamespace(pid=100, poll=lambda: 0))
        self.assertEqual(set(killed), {(500, signal.SIGTERM), (501, signal.SIGTERM)})
        self.ns['reap_children'].assert_called_once()

    def test_term_resistant_owned_child_reaches_bounded_kill(self):
        parent = os.getpid()
        snapshot = {500: {'ppid': parent, 'start': 'owned', 'state': 'S'}}
        self.ns.update(active_owned={}, active_excluded={},
                       process_snapshot=lambda: snapshot, reap_children=Mock())
        killed = []

        def kill(pid, signum):
            killed.append((pid, signum))
            if signum == signal.SIGKILL:
                snapshot[pid]['state'] = 'Z'

        ticks = iter(range(1000))
        self.ns['time'] = types.SimpleNamespace(monotonic=lambda: next(ticks), sleep=Mock())
        self.ns['os'] = types.SimpleNamespace(getpid=lambda: parent, kill=kill)
        # The KILL phase must complete without real waiting or real signals.
        self.ns['stop_owned'](types.SimpleNamespace(pid=100, poll=lambda: 0))
        self.assertEqual(killed, [(500, signal.SIGTERM), (500, signal.SIGKILL)])

    def test_untracked_java_source_is_rejected_before_build(self):
        with tempfile.TemporaryDirectory(dir=HERE, prefix='cleancheck-fixture-') as temporary:
            fixture = Path(temporary)
            (fixture / 'pom.xml').write_text('<project/>')
            injected = fixture / 'src/main/java/Injected.java'
            injected.parent.mkdir(parents=True)
            injected.write_text('class Injected {}')
            sha = 'a' * 40
            self.ns.update(SOURCE=fixture, clone=fixture, args=types.SimpleNamespace(sha=sha))

            def git(argv, **kwargs):
                if argv[1:3] == ['rev-parse', 'HEAD']:
                    return sha + '\n'
                if argv[1] == 'diff':
                    return ''
                if argv[1] == 'ls-files':
                    if '--others' in argv:
                        return b'src/main/java/Injected.java\0'
                    return b'pom.xml\0'
                if argv[1] == 'status':
                    return '?? src/main/java/Injected.java\n'
                raise AssertionError('Unexpected mock Git request: ' + repr(argv))

            self.ns['subprocess'] = types.SimpleNamespace(check_output=git)
            with self.assertRaisesRegex(RuntimeError, '(?i)untracked|unexpected|input'):
                self.ns['protected_inputs']()

    def test_ignored_java_source_is_rejected_even_when_git_omits_it(self):
        with tempfile.TemporaryDirectory(dir=HERE, prefix='cleancheck-ignored-fixture-') as temporary:
            fixture = Path(temporary)
            (fixture / 'pom.xml').write_text('<project/>')
            source = fixture / 'src/main/java/Injected.java'
            source.parent.mkdir(parents=True)
            source.write_text('class Injected {}')
            sha = 'a' * 40
            self.ns.update(SOURCE=fixture, clone=fixture, args=types.SimpleNamespace(sha=sha))

            def git(argv, **kwargs):
                if argv[1] == 'rev-parse':
                    return sha + '\n'
                if argv[1] == 'diff':
                    return ''
                if argv[1] == 'ls-files':
                    return b'' if '--others' in argv else b'pom.xml\0'
                raise AssertionError('Unexpected mock Git request: ' + repr(argv))

            self.ns['subprocess'] = types.SimpleNamespace(check_output=git)
            with self.assertRaisesRegex(RuntimeError, '(?i)ignored|untracked|input'):
                self.ns['protected_inputs']()

    def test_evidence_model_mutation_is_not_exempt_as_generated_demo_output(self):
        sha = 'a' * 40
        self.ns.update(SOURCE=Path('/fixture'), clone=Path('/fixture'), args=types.SimpleNamespace(sha=sha))

        def git(argv, **kwargs):
            if argv[1] == 'rev-parse':
                return sha + '\n'
            if argv[1] == 'diff':
                return 'projects/07-indexforge/evidence/frozen-model.json\n'
            raise AssertionError('Input mutation must stop before further Git operations')

        self.ns['subprocess'] = types.SimpleNamespace(check_output=git)
        with self.assertRaisesRegex(RuntimeError, 'modified tracked'):
            self.ns['protected_inputs']()

    def test_existing_network_prevents_claim_of_fresh_namespace(self):
        requests = []

        def docker(argv, **kwargs):
            requests.append(argv)
            return 'old-network\n' if argv[1] == 'network' else ''

        self.ns['subprocess'] = types.SimpleNamespace(check_output=docker)
        with self.assertRaisesRegex(RuntimeError, 'network'):
            self.ns['require_fresh_namespace']('fresh-only')
        self.assertEqual([request[1] for request in requests], ['ps', 'volume', 'network'])

    def test_coexistence_rejects_replacement_jvm_with_same_pid(self):
        class FakePath:
            def __init__(self, value):
                self.value = str(value)

            def __truediv__(self, child):
                return FakePath(self.value.rstrip('/') + '/' + str(child))

            def __str__(self):
                return self.value

            def read_text(self):
                return '101' if self.value.endswith('api.pid') else '102'

            def read_bytes(self):
                slug = '01-clearledger' if '/101/' in self.value else '04-pulsestream'
                return ('java\0-jar\0/fixture/projects/' + slug + '/api.jar\0').encode()

        class Health:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self, limit):
                return b'OK'

        snapshots = {101: {'state': 'S', 'start': 'first'},
                     102: {'state': 'S', 'start': 'second'}}

        def docker(argv, **kwargs):
            if argv[1] == 'ps':
                return 'c1' if argv[-1].endswith('clearledger') else 'p1 p2'
            identity = argv[-1]
            return identity + '|true|201|image@sha256:abc|database'

        self.ns.update(Path=FakePath, clone=FakePath('/fixture'),
                       projects={'clearledger': {'path': 'projects/01-clearledger'},
                                 'pulsestream': {'path': 'projects/04-pulsestream'}},
                       args=types.SimpleNamespace(sha='a' * 40),
                       process_snapshot=lambda: snapshots,
                       subprocess=types.SimpleNamespace(check_output=docker),
                       urllib=types.SimpleNamespace(request=types.SimpleNamespace(urlopen=lambda *a, **k: Health())))
        original = self.ns['coexist_identity']('initial')
        snapshots[102]['start'] = 'replacement-start-time'
        with self.assertRaisesRegex(RuntimeError, 'identities changed'):
            self.ns['coexist_identity']('after operations', original)

    def test_third_party_seed_excludes_own_artifacts_and_snapshots_without_shared_writes(self):
        with tempfile.TemporaryDirectory(dir=HERE, prefix='cleancheck-cache-fixture-') as temporary:
            fixture = Path(temporary)
            origin = fixture / 'source-fixture'
            destination = fixture / 'destination-fixture'
            source_cache = origin / '.work/coordinator/m2'
            samples = ['org/example/release/1/release-1.jar',
                       'org/example/release/1/release-1.pom',
                       'org/example/development/1-SNAPSHOT/development.jar',
                       'io/github/cheshmakzanoon/portfolio/tool/1/tool.jar']
            for relative in samples:
                path = source_cache / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'tiny-mock-input')
            copies, links = [], []
            self.ns.update(SOURCE=origin, clone=destination, digest=lambda path: 'fixed-mock-hash',
                           shutil=types.SimpleNamespace(
                               disk_usage=lambda path: types.SimpleNamespace(free=10 * 1024 ** 3),
                               copy2=lambda source, target: copies.append((source, target))),
                           os=types.SimpleNamespace(link=lambda source, target: links.append((source, target))))
            self.ns['seed_dependency_caches']()
            self.assertEqual(len(copies), 2)
            self.assertEqual(len(links), 2)
            self.assertTrue(all('SNAPSHOT' not in str(source) and 'cheshmakzanoon' not in str(source)
                                for source, _ in copies))
            self.assertTrue(all(source.is_relative_to(destination) and target.is_relative_to(destination)
                                for source, target in links))
            self.assertTrue(all(not target.is_relative_to(origin) for _, target in copies))

    def snapshot_fixture(self, alteration):
        """Inject a deterministic filesystem change after the first source read."""
        temporary = tempfile.TemporaryDirectory(dir=HERE, prefix='snapshot-fixture-')
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        original = b'original-provider-line\n'
        source, target = directory / 'source.log', directory / 'retained.log'
        source.write_bytes(original)

        class ReadHook:
            def __init__(self, stream):
                self.stream = stream
                self.changed = False

            def __enter__(self):
                self.stream.__enter__()
                return self

            def __exit__(self, *args):
                return self.stream.__exit__(*args)

            def fileno(self):
                return self.stream.fileno()

            def seek(self, *args):
                return self.stream.seek(*args)

            def read(self, size):
                chunk = self.stream.read(size)
                if not self.changed:
                    self.changed = True
                    alteration(source)
                return chunk

        class HookSource:
            def open(self, *args, **kwargs):
                return ReadHook(source.open(*args, **kwargs))

            def stat(self, *args, **kwargs):
                return source.stat(*args, **kwargs)

            def __str__(self):
                return str(source)

        return HookSource(), source, target, original

    def test_snapshot_append_retains_exact_original_prefix_and_discloses_larger_source(self):
        def append(path):
            with path.open('ab') as output:
                output.write(b'later-provider-line\n')

        hook, source, target, original = self.snapshot_fixture(append)
        receipt = self.ns['copy_snapshot'](hook, target)
        self.assertEqual(target.read_bytes(), original)
        self.assertEqual(receipt['bytes'], len(original))
        self.assertEqual(receipt['sha256'], hashlib.sha256(original).hexdigest())
        self.assertEqual(receipt['sourceSizeAtCompletion'], source.stat().st_size)
        self.assertGreater(receipt['sourceSizeAtCompletion'], receipt['bytes'])

    def test_snapshot_rejects_truncation_after_first_pass(self):
        hook, source, target, original = self.snapshot_fixture(lambda path: path.write_bytes(b''))
        with self.assertRaisesRegex(RuntimeError, '(?i)truncat|prefix|changed'):
            self.ns['copy_snapshot'](hook, target)

    def test_snapshot_rejects_same_size_in_place_prefix_mutation(self):
        def mutate(path):
            with path.open('r+b') as output:
                output.write(b'X')

        hook, source, target, original = self.snapshot_fixture(mutate)
        with self.assertRaisesRegex(RuntimeError, '(?i)prefix|changed'):
            self.ns['copy_snapshot'](hook, target)

    def test_snapshot_rejects_replaced_source_inode(self):
        def replace(path):
            replacement = path.with_name('replacement.log')
            replacement.write_bytes(b'replacement-provider-line\n')
            replacement.replace(path)

        hook, source, target, original = self.snapshot_fixture(replace)
        with self.assertRaisesRegex(RuntimeError, '(?i)replacement|changed'):
            self.ns['copy_snapshot'](hook, target)

    def test_snapshot_growth_after_selection_rejects_budget_without_copying_a_prefix(self):
        with tempfile.TemporaryDirectory(dir=HERE, prefix='snapshot-budget-fixture-') as temporary:
            directory = Path(temporary)
            source, target = directory / 'source.log', directory / 'retained.log'
            source.write_bytes(b'selected\n')
            remaining_budget = source.stat().st_size
            with source.open('ab') as output:
                output.write(b'appended-before-opening\n')
            grown = source.read_bytes()
            with self.assertRaisesRegex(RuntimeError, '(?i)budget'):
                self.ns['copy_snapshot'](source, target, maximum_bytes=remaining_budget)
            self.assertFalse(target.exists(), 'Oversized evidence must not be silently clipped')
            self.assertEqual(source.read_bytes(), grown)
            receipt = self.ns['copy_snapshot'](source, target, maximum_bytes=len(grown))
            self.assertEqual(target.read_bytes(), grown)
            self.assertEqual(receipt['bytes'], len(grown))


if __name__ == '__main__':
    unittest.main(verbosity=2)
