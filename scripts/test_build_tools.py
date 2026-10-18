#!/usr/bin/env python3
"""Regression checks for architecture gate failure detection."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

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


if __name__ == '__main__':
    unittest.main()
