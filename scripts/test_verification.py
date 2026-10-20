"""Regression tests for incomplete, stale, and internally inconsistent Maven runs."""
from pathlib import Path
import importlib.util
import os
import tempfile
import unittest

from maven_evidence import EvidenceError, reactor_modules
from test_build_tools import make_reactor, write_test_report


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


tests = load_script('check-tests')
mutations = load_script('check-mutations')


class ReactorEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        make_reactor(self.root, ('one', 'two'))

    def source(self, module, integration=False):
        path = self.root / module / 'src/test/java/example' / ('BehaviorIT.java' if integration else 'BehaviorTest.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('// Maven naming contract\n')

    def report(self, module, integration=False, timestamp=200):
        path = self.root / module / 'target' / ('failsafe-reports' if integration else 'surefire-reports') / 'TEST-example.Behavior.xml'
        write_test_report(path)
        os.utime(path, (timestamp, timestamp))
        return path

    def test_one_module_cannot_hide_a_missing_or_stale_other_module(self):
        for module in ('one', 'two'):
            self.source(module)
        self.report('one')
        self.assertTrue(tests.inspect(self.root, 150)['failures'])
        self.report('two', timestamp=100)
        self.assertTrue(tests.inspect(self.root, 150)['failures'])
        self.report('two')
        self.assertEqual([], tests.inspect(self.root, 150)['failures'])

    def test_every_integration_module_must_execute(self):
        for module in ('one', 'two'):
            self.source(module, integration=True)
        self.report('one', integration=True)
        self.assertTrue(tests.inspect(self.root, 150, integration=True)['failures'])
        self.report('two', integration=True)
        self.assertEqual([], tests.inspect(self.root, 150, integration=True)['failures'])

    def test_unlisted_cached_report_does_not_count_for_selected_reactor(self):
        self.source('one')
        self.report('dependency-cache')
        self.assertTrue(tests.inspect(self.root, 150)['failures'])

    def test_declared_counts_cannot_replace_actual_test_cases(self):
        path = self.report('one')
        path.write_text('<testsuite tests="100" failures="0" errors="0" skipped="0"/>')
        self.assertTrue(tests.inspect(self.root, 1)['failures'])
        path.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>')
        self.assertTrue(tests.inspect(self.root, 1)['failures'])

    def test_invalid_current_xml_is_a_reported_failure(self):
        self.report('one').write_text('<testsuite')
        self.assertTrue(tests.inspect(self.root, 1)['failures'])

    def test_fractional_cutoff_rejects_same_second_stale_report(self):
        self.source('one')
        self.report('one', timestamp=200.125)
        self.assertTrue(tests.inspect(self.root, 200.5)['failures'])
        self.assertEqual([], tests.inspect(self.root, 200.0)['failures'])

    def test_module_escape_and_cycles_fail_closed(self):
        pom = self.root / 'pom.xml'
        original = pom.read_text()
        pom.write_text(original.replace('<module>one</module>', '<module>..</module>'))
        with self.assertRaises(EvidenceError):
            reactor_modules(self.root)
        pom.write_text(original.replace('<module>two</module>', '<module>one</module>'))
        with self.assertRaises(EvidenceError):
            reactor_modules(self.root)

    def test_profile_module_only_required_when_selected(self):
        pom = self.root / 'pom.xml'
        pom.write_text(pom.read_text().replace('</project>', '<profiles><profile><id>integration</id><modules><module>database</module></modules></profile></profiles></project>'))
        self.assertEqual(3, len(reactor_modules(self.root)))
        with self.assertRaises(EvidenceError):
            reactor_modules(self.root, ('integration',))

    def mutation_report(self, module, killed, survived=0):
        path = self.root / module / 'target/pit-reports/mutations.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<mutations>' + '<mutation status="KILLED"/>' * killed + '<mutation status="SURVIVED"/>' * survived + '</mutations>')
        return path

    def test_aggregate_mutations_cannot_hide_a_weak_module(self):
        make_reactor(self.root, ('one', 'two'), mutation=True)
        self.mutation_report('one', 100)
        self.mutation_report('two', 6, 4)
        self.assertTrue(mutations.inspect(self.root, 1)['failures'])
        self.mutation_report('two', 7, 3)
        self.assertEqual([], mutations.inspect(self.root, 1)['failures'])

    def test_each_selected_mutation_module_requires_fresh_evidence(self):
        make_reactor(self.root, ('one', 'two'), mutation=True)
        self.mutation_report('one', 100)
        self.assertTrue(mutations.inspect(self.root, 1)['failures'])
        second = self.mutation_report('two', 10)
        os.utime(second, (1, 1))
        self.assertTrue(mutations.inspect(self.root, 2)['failures'])

    def test_module_specific_mutation_threshold_cannot_be_diluted(self):
        make_reactor(self.root, ('one',), mutation=True)
        pom = self.root / 'one/pom.xml'
        pom.write_text(pom.read_text().replace('<targetClasses>', '<mutationThreshold>90</mutationThreshold><targetClasses>'))
        self.mutation_report('one', 8, 2)
        self.assertTrue(mutations.inspect(self.root, 1)['failures'])
        self.mutation_report('one', 9, 1)
        self.assertEqual([], mutations.inspect(self.root, 1)['failures'])


if __name__ == '__main__':
    unittest.main()
