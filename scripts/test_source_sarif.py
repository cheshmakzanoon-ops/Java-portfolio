"""Regression tests for the CodeQL source finding release gate."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from importlib.machinery import SourceFileLoader

GATE = SourceFileLoader('source_gate', str(Path(__file__).with_name('check-source-sarif.py'))).load_module()


def sample(*, severity='5.1', level='warning', rule='java/sql-injection', producer='CodeQL'):
    declaration = {'id': rule, 'defaultConfiguration': {'level': 'warning'}}
    if severity is not None:
        declaration['properties'] = {'security-severity': severity}
    return {'version': '2.1.0', 'runs': [{'tool': {'driver': {'name': producer, 'rules': [declaration]}},
                                          'invocations': [{'executionSuccessful': True}],
                                          'results': [{'ruleId': rule, 'level': level}]}]}


def evaluate(document):
    return GATE.inspect([('evidence.sarif', document)])


class SarifGateTest(unittest.TestCase):
    def test_empty_and_missing_documents_are_not_success(self):
        self.assertTrue(GATE.inspect([])['failures'])
        for document in ({}, {'version': '2.1.0'}, {'version': '2.1.0', 'runs': []},
                         {'version': '2.0.0', 'runs': [sample()['runs'][0]]}, None, [], 'invalid'):
            with self.subTest(document=document):
                self.assertTrue(evaluate(document)['failures'])

    def test_valid_low_severity_scan_passes(self):
        report = evaluate(sample())
        self.assertEqual([], report['failures'])
        self.assertEqual(1, report['runs'])
        self.assertEqual(1, report['findings'])

    def test_valid_empty_finding_scan_passes_if_rules_exist(self):
        document = sample()
        document['runs'][0]['results'] = []
        self.assertEqual([], evaluate(document)['failures'])
        self.assertEqual(0, evaluate(document)['findings'])

    def test_threshold_is_inclusive_and_unscored_errors_fail(self):
        for score in ('7.0', 7, 9.9, 10):
            with self.subTest(score=score):
                self.assertTrue(evaluate(sample(severity=score))['failures'])
        for score in ('0', '6.999', 6, None):
            with self.subTest(score=score):
                self.assertEqual([], evaluate(sample(severity=score))['failures'])
        self.assertTrue(evaluate(sample(severity=None, level='error'))['failures'])

    def test_suppressed_finding_remains_a_violation(self):
        document = sample(severity='8.8')
        document['runs'][0]['results'][0]['suppressions'] = [{'kind': 'external', 'status': 'accepted'}]
        self.assertTrue(evaluate(document)['failures'])

    def test_invalid_security_score_never_passes(self):
        for value in ('NaN', 'Infinity', '-0.1', '10.1', 'not-a-number', True, [], {}, 1e100):
            with self.subTest(value=value):
                self.assertTrue(evaluate(sample(severity=value))['failures'])

    def test_forged_non_codeql_results_cannot_pass(self):
        self.assertTrue(evaluate(sample(producer='Custom Scanner'))['failures'])
        document = sample()
        document['runs'][0]['tool']['driver'] = {}
        self.assertTrue(evaluate(document)['failures'])

    def test_invalid_rules_fail_closed(self):
        for rules in ([], [{}], [{'id': ''}], [{'id': 15}], None, 'invalid'):
            with self.subTest(rules=rules):
                document = sample()
                document['runs'][0]['tool']['driver']['rules'] = rules
                self.assertTrue(evaluate(document)['failures'])

    def test_duplicate_rule_ids_fail(self):
        document = sample()
        rule = document['runs'][0]['tool']['driver']['rules'][0]
        document['runs'][0]['tool']['driver']['rules'].append(copy.deepcopy(rule))
        self.assertTrue(evaluate(document)['failures'])

    def test_unknown_result_rule_fails(self):
        document = sample()
        document['runs'][0]['results'][0]['ruleId'] = 'java/other'
        self.assertTrue(evaluate(document)['failures'])

    def test_invalid_run_and_findings_shapes(self):
        cases = [
            lambda doc: doc.update(runs=[None]),
            lambda doc: doc.update(runs='not a list'),
            lambda doc: doc['runs'][0].update(results='not a list'),
            lambda doc: doc['runs'][0].update(results=[None]),
            lambda doc: doc['runs'][0].update(invocations='not a list'),
            lambda doc: doc['runs'][0].update(invocations=[{'executionSuccessful': False}]),
            lambda doc: doc['runs'][0].update(invocations=[None]),
            lambda doc: doc['runs'][0]['tool'].update(extensions='invalid'),
            lambda doc: doc['runs'][0]['results'][0].update(level='unsafe'),
        ]
        for operation in cases:
            with self.subTest(operation=operation):
                document = sample()
                operation(document)
                self.assertTrue(evaluate(document)['failures'])

    def test_multiple_runs_and_extensions_are_inspected(self):
        document = sample()
        run = document['runs'][0]
        run['results'] = []
        run['tool']['extensions'] = [{'rules': [{'id': 'java/ext', 'properties': {'security-severity': '7.1'}}]}]
        run['results'].append({'ruleId': 'java/ext'})
        self.assertTrue(evaluate(document)['failures'])
        run['results'][0]['ruleId'] = 'java/sql-injection'
        self.assertEqual([], evaluate(document)['failures'])
        document['runs'].append(copy.deepcopy(run))
        self.assertEqual(2, evaluate(document)['runs'])

    def test_directory_scan_rejects_malformed_file_and_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            path = directory / 'report.sarif'
            path.write_text('{', encoding='utf-8')
            process = subprocess.run([sys.executable, str(Path(GATE.__file__)), str(directory)],
                                     capture_output=True, text=True)
            self.assertNotEqual(0, process.returncode)
            path.write_text(json.dumps(sample()), encoding='utf-8')
            process = subprocess.run([sys.executable, str(Path(GATE.__file__)), str(directory)],
                                     capture_output=True, text=True)
            self.assertEqual(0, process.returncode, process.stderr + process.stdout)
            path.unlink()
            outside = directory / 'elsewhere.json'
            outside.write_text(json.dumps(sample()), encoding='utf-8')
            path.symlink_to(outside)
            process = subprocess.run([sys.executable, str(Path(GATE.__file__)), str(directory)],
                                     capture_output=True, text=True)
            self.assertNotEqual(0, process.returncode)

    def test_empty_directory_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            process = subprocess.run([sys.executable, str(Path(GATE.__file__)), tmp],
                                     capture_output=True, text=True)
            self.assertNotEqual(0, process.returncode)
            self.assertIn('any SARIF documents', process.stdout)


if __name__ == '__main__':
    unittest.main()
