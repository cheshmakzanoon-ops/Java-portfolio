"""Malicious or stale report paths must not establish current-source verification."""
from pathlib import Path
import os
import tempfile
import unittest

from maven_evidence import EvidenceError, fresh_reports, reactor_modules, test_counts
from test_build_tools import make_reactor, write_test_report
from unittest.mock import patch


class FreshReportBoundaryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        make_reactor(self.root, ('core',))
        self.module = reactor_modules(self.root)[1]
        self.path = self.module.directory / 'target/surefire-reports/TEST-Example.xml'
        write_test_report(self.path, 2)

    def test_ordinary_fresh_surefire_report_is_selected(self):
        os.utime(self.path, (200, 200))
        self.assertEqual([self.path], fresh_reports(self.module, 'surefire', 100))
        self.assertEqual([], fresh_reports(self.module, 'surefire', 201))
        self.assertEqual({'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0},
                         test_counts(self.path))

    def test_symlinked_report_is_not_accepted_even_with_current_mtime(self):
        outside = self.root / 'outside.xml'
        write_test_report(outside, 100)
        self.path.unlink()
        self.path.symlink_to(outside)
        with self.assertRaisesRegex(EvidenceError, 'ordinary file'):
            fresh_reports(self.module, 'surefire', 1)
        with self.assertRaisesRegex(EvidenceError, 'ordinary file'):
            test_counts(self.path)

    def test_symlinked_reports_directory_is_not_accepted(self):
        other = self.root / 'fake-reports'
        other.mkdir()
        write_test_report(other / 'TEST-Example.xml', 100)
        self.path.unlink()
        self.path.parent.rmdir()
        self.path.parent.symlink_to(other, target_is_directory=True)
        with self.assertRaisesRegex(EvidenceError, 'redirected'):
            fresh_reports(self.module, 'surefire', 1)

    def test_symlinked_target_directory_is_not_accepted(self):
        target = self.module.directory / 'target'
        other = self.root / 'faux-target'
        target.rename(other)
        target.symlink_to(other, target_is_directory=True)
        with self.assertRaisesRegex(EvidenceError, 'redirected'):
            fresh_reports(self.module, 'surefire', 1)

    def test_pit_reports_under_nested_timestamp_directories_are_checked(self):
        path = self.module.directory / 'target/pit-reports/2026-10-21/mutations.xml'
        path.parent.mkdir(parents=True)
        path.write_text('<mutations><mutation status="KILLED"/></mutations>')
        self.assertEqual([path], fresh_reports(self.module, 'pit', 1))
        outside = self.root / 'external'
        outside.mkdir()
        path.unlink()
        path.parent.rmdir()
        path.parent.symlink_to(outside, target_is_directory=True)
        (outside / 'mutations.xml').write_text('<mutations><mutation status="KILLED"/></mutations>')
        with self.assertRaises(EvidenceError):
            fresh_reports(self.module, 'pit', 1)

    def test_wrong_report_kind_is_rejected(self):
        with self.assertRaises(EvidenceError):
            fresh_reports(self.module, 'checkstyle', 1)

    def test_old_reports_do_not_count_for_new_build(self):
        os.utime(self.path, (100, 100))
        self.assertEqual([], fresh_reports(self.module, 'surefire', 101))
        self.assertEqual([self.path], fresh_reports(self.module, 'surefire', 100))


class SuiteSemanticValidationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / 'TEST-Example.xml'

    def write(self, xml):
        self.path.write_text(xml)
        return self.path

    def test_valid_single_and_parameterized_cases(self):
        xml = ('<testsuite tests="3" failures="1" errors="0" skipped="1">'
               '<testcase name="one"/>'
               '<testcase name="one"><failure>bad</failure></testcase>'
               '<testcase name="one"><skipped/></testcase></testsuite>')
        self.assertEqual({'tests': 3, 'failures': 1, 'errors': 0, 'skipped': 1},
                         test_counts(self.write(xml)))

    def test_single_case_cannot_be_failed_and_skipped_simultaneously(self):
        for first, second in (('failure', 'skipped'), ('error', 'failure'),
                              ('error', 'skipped')):
            with self.subTest(first=first, second=second):
                # Correct counters cannot disguise multiple terminal states on one test.
                report = ('<testsuite tests="2" failures="' +
                          str(first == 'failure' or second == 'failure').lower().replace('true','1').replace('false','0') +
                          '" errors="' + str(first == 'error' or second == 'error').lower().replace('true','1').replace('false','0') +
                          '" skipped="' + str(first == 'skipped' or second == 'skipped').lower().replace('true','1').replace('false','0') +
                          '"><testcase/><testcase><' + first + '/><' + second +
                          '/></testcase></testsuite>')
                with self.assertRaises(EvidenceError):
                    test_counts(self.write(report))

    def test_valid_current_failure_counts_still_fail_verification_at_call_site(self):
        xml = ('<testsuite tests="1" failures="1" errors="0" skipped="0">'
               '<testcase name="f"><failure/></testcase></testsuite>')
        self.assertEqual(1, test_counts(self.write(xml))['failures'])

    def test_counts_disagree_with_testcase_nodes(self):
        for xml in (
            '<testsuite tests="1" failures="0" errors="0" skipped="0"/>',
            '<testsuite tests="-1" failures="0" errors="0" skipped="0"/>',
            '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase><error/></testcase></testsuite>',
            '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/><testcase/></testsuite>',
            '<testsuites tests="0" failures="0" errors="0" skipped="0"/>',
        ):
            with self.subTest(xml=xml):
                with self.assertRaises(EvidenceError):
                    test_counts(self.write(xml))

    def test_untrusted_doctype_or_entity_is_rejected_before_xml_parsing(self):
        for xml in (
            '<!DOCTYPE testsuite><testsuite tests="0" failures="0" errors="0" skipped="0"/>',
            '<!DOCTYPE x [<!ENTITY payload "unsafe">]><testsuite tests="0" failures="0" errors="0" skipped="0"/>',
        ):
            with self.subTest(xml=xml):
                with self.assertRaises(EvidenceError):
                    test_counts(self.write(xml))

    def test_oversized_test_report_does_not_consume_unbounded_memory(self):
        import maven_evidence
        original = maven_evidence.MAX_REPORT_BYTES
        try:
            maven_evidence.MAX_REPORT_BYTES = 16
            self.write('<testsuite tests="0" failures="0" errors="0" skipped="0"/>')
            with self.assertRaisesRegex(EvidenceError, 'budget'):
                test_counts(self.path)
        finally:
            maven_evidence.MAX_REPORT_BYTES = original

    def test_nonsense_xml_is_not_counted(self):
        for xml in ('', '<testsuite', '{ "tests": 100 }', '<testcase/>'):
            with self.subTest(xml=xml):
                with self.assertRaises(EvidenceError):
                    test_counts(self.write(xml))

    def test_utf16_declarations_cannot_bypass_entity_preflight(self):
        xml = ('<?xml version="1.0" encoding="UTF-16"?>'
               '<!DOCTYPE testsuite [<!ENTITY x "expanded">]>'
               '<testsuite tests="1" failures="0" errors="0" skipped="0">'
               '<testcase name="&x;"/></testsuite>')
        for encoding in ('utf-16', 'utf-16-le', 'utf-16-be', 'utf-32'):
            with self.subTest(encoding=encoding):
                self.path.write_bytes(xml.encode(encoding))
                with self.assertRaises(EvidenceError):
                    test_counts(self.path)

    def test_utf8_bom_and_international_names_remain_supported(self):
        xml = ('<?xml version="1.0" encoding="UTF-8"?>'
               '<testsuite tests="1" failures="0" errors="0" skipped="0">'
               '<testcase name="日本語 آزمون"/></testsuite>')
        self.path.write_bytes(xml.encode('utf-8-sig'))
        self.assertEqual(1, test_counts(self.path)['tests'])

    def test_actual_read_is_bounded_even_when_metadata_understates_length(self):
        import maven_evidence
        xml = ('<testsuite tests="1" failures="0" errors="0" skipped="0">'
               '<testcase/></testsuite>')
        self.write(xml)
        real_stat = os.fstat

        def small_stat(fd):
            values = list(real_stat(fd))
            values[6] = 1
            return os.stat_result(values)

        with patch.object(maven_evidence, 'MAX_REPORT_BYTES', 16), \
                patch.object(maven_evidence.os, 'fstat', side_effect=small_stat):
            with self.assertRaisesRegex(EvidenceError, 'budget'):
                test_counts(self.path)

    @unittest.skipUnless(hasattr(os, 'mkfifo'), 'FIFO is a POSIX filesystem feature')
    def test_named_pipe_is_refused_without_waiting_for_writer(self):
        import subprocess
        import sys
        os.mkfifo(self.path)
        script = ('from pathlib import Path; from maven_evidence import test_counts; '
                  'test_counts(Path(' + repr(str(self.path)) + '))')
        result = subprocess.run([sys.executable, '-c', script],
                                cwd=Path(__file__).parent, capture_output=True,
                                text=True, timeout=3)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('ordinary file', result.stderr)

    def test_mutation_reports_share_the_same_xml_boundary(self):
        from test_verification import mutations
        import maven_evidence
        make_reactor(self.path.parent, ('core',), mutation=True)
        report = self.path.parent / 'core/target/pit-reports/mutations.xml'
        report.parent.mkdir(parents=True)
        report.write_text('<!DOCTYPE mutations><mutations><mutation status="KILLED"/></mutations>')
        self.assertTrue(mutations.inspect(self.path.parent, 1)['failures'])
        report.write_text('<mutations><mutation status="KILLED"/></mutations>')
        self.assertEqual([], mutations.inspect(self.path.parent, 1)['failures'])
        with patch.object(maven_evidence, 'MAX_REPORT_BYTES', 16):
            self.assertTrue(mutations.inspect(self.path.parent, 1)['failures'])


if __name__ == '__main__':
    unittest.main()
