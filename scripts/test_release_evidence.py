"""A historical PASS or a tampered report must never authorize a release."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from release_evidence import inspect, source_fingerprint


class CurrentReleaseEvidenceTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        subprocess.run(['git', 'init', '-q', directory.name], check=True)
        self.source = self.root / 'Source.java'
        self.source.write_text('class Source {}\n')
        subprocess.run(['git', 'add', 'Source.java'], cwd=self.root, check=True)
        self.report = self.root / 'docs/execution/report.json'
        self.report.parent.mkdir(parents=True)
        self.report.write_text('{"tests": 1, "failures": 0}\n')
        self.receipt = self.root / 'docs/execution/release-current.json'

    def save(self, **changes):
        document = {'schemaVersion': 1, 'sourceFingerprint': source_fingerprint(self.root),
                    'criteria': {'P01-A1': {'status': 'PASS', 'evidence': {
                        'docs/execution/report.json': hashlib.sha256(self.report.read_bytes()).hexdigest()}}}}
        document.update(changes)
        self.receipt.write_text(json.dumps(document))

    def check(self):
        return inspect(self.root, {'P01-A1'})

    def test_missing_and_malformed_current_evidence_fail_closed(self):
        self.assertTrue(self.check())
        self.receipt.write_text('{')
        self.assertTrue(self.check())

    def test_unchanged_source_and_report_pass_but_changed_source_cannot_inherit_pass(self):
        self.save()
        self.assertEqual([], self.check())
        self.source.write_text('class Source { void changed() {} }\n')
        self.assertTrue(self.check())

    def test_added_or_removed_sources_invalidate_evidence(self):
        self.save()
        new = self.root / 'New.java'
        new.write_text('class New {}\n')
        self.assertTrue(self.check())
        new.unlink()
        self.source.unlink()
        self.assertTrue(self.check())

    def test_changed_or_missing_raw_report_cannot_inherit_pass(self):
        self.save()
        self.report.write_text('{"tests": 1, "failures": 1}\n')
        self.assertTrue(self.check())
        self.report.unlink()
        self.assertTrue(self.check())

    def test_pending_missing_or_escaping_evidence_cannot_pass(self):
        for row in ({'status': 'IN_PROGRESS'}, {'status': 'PASS', 'evidence': {}},
                    {'status': 'PASS', 'evidence': {'../outside.json': '0' * 64}}):
            with self.subTest(row=row):
                self.save(criteria={'P01-A1': row})
                self.assertTrue(self.check())
        self.save(criteria={})
        self.assertTrue(self.check())

    def test_missing_criterion_cannot_be_hidden_by_unrelated_pass(self):
        self.save()
        self.assertTrue(inspect(self.root, {'P01-A1', 'P02-A1'}))


if __name__ == '__main__':
    unittest.main()
