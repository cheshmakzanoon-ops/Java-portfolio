#!/usr/bin/env python3
"""Regression checks for architecture gate failure detection."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import os
import subprocess

spec = importlib.util.spec_from_file_location('architecture', Path(__file__).with_name('check-architecture.py'))
architecture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(architecture)


class ArchitectureGateTest(unittest.TestCase):
    def make_root(self, directory, source):
        root = Path(directory)
        path = root / 'projects/01-clearledger/ledger-domain/src/main/java/example/Domain.java'
        path.parent.mkdir(parents=True)
        path.write_text(source)
        return root

    def test_cross_project_import_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'import io.github.cheshmakzanoon.portfolio.quorumkv.Model;')
            self.assertIn('cross-project import', architecture.inspect(root)[0])

    def test_domain_framework_import_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'import org.springframework.stereotype.Service;')
            self.assertIn('domain framework', architecture.inspect(root)[0])

    def test_own_project_and_jdk_are_allowed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'import java.math.BigInteger;\nimport io.github.cheshmakzanoon.portfolio.clearledger.Money;')
            self.assertEqual([], architecture.inspect(root))

    def test_dependency_cycle_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'import java.time.Instant;')
            for name, dependency in [('one', 'two'), ('two', 'one')]:
                pom = root / f'projects/01-clearledger/{name}/pom.xml'
                pom.parent.mkdir(parents=True)
                pom.write_text(f'<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>{name}</artifactId><dependencies><dependency><groupId>io.github.cheshmakzanoon.portfolio</groupId><artifactId>{dependency}</artifactId></dependency></dependencies></project>')
            self.assertTrue(any('cycle' in item for item in architecture.inspect(root)))


class MutationEvidenceGateTest(unittest.TestCase):
    def invoke(self, statuses, started='0', timestamp=None):
        with tempfile.TemporaryDirectory() as temp:
            report = Path(temp) / 'core/target/pit-reports/mutations.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<mutations>' + ''.join(
                f'<mutation status="{status}"/>' for status in statuses) + '</mutations>')
            if timestamp is not None:
                os.utime(report, (timestamp, timestamp))
            return subprocess.run(
                ['python3', str(Path(__file__).with_name('check-mutations.py')), temp],
                env={**os.environ, 'PORTFOLIO_TEST_RUN_STARTED': started},
                capture_output=True, text=True).returncode

    def test_nonviable_is_excluded_but_survivors_are_counted(self):
        self.assertEqual(0, self.invoke(['KILLED'] * 7 + ['SURVIVED'] * 3 + ['NON_VIABLE'] * 5))
        self.assertEqual(1, self.invoke(['KILLED'] * 6 + ['SURVIVED'] * 4))

    def test_runner_failure_cannot_masquerade_as_kill(self):
        self.assertEqual(1, self.invoke(['KILLED'] * 99 + ['RUN_ERROR']))
        self.assertEqual(1, self.invoke(['KILLED'] * 99 + ['MEMORY_ERROR']))

    def test_empty_and_stale_reports_fail(self):
        self.assertEqual(1, self.invoke([]))
        self.assertEqual(1, self.invoke(['KILLED'], started='200', timestamp=100))


if __name__ == '__main__':
    unittest.main()
