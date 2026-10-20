#!/usr/bin/env python3
"""Regression checks for architecture gate failure detection."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import os
import subprocess
import shutil
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('architecture', Path(__file__).with_name('check-architecture.py'))
architecture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(architecture)
spec = importlib.util.spec_from_file_location('container_images', Path(__file__).with_name('container-images.py'))
container_images = importlib.util.module_from_spec(spec)
spec.loader.exec_module(container_images)
spec = importlib.util.spec_from_file_location('markdown_links', Path(__file__).with_name('check-links.py'))
markdown_links = importlib.util.module_from_spec(spec)
spec.loader.exec_module(markdown_links)
spec = importlib.util.spec_from_file_location('source_sarif', Path(__file__).with_name('check-source-sarif.py'))
source_sarif = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source_sarif)


def make_reactor(root, names=('core',), mutation=False):
    namespace = 'http://maven.apache.org/POM/4.0.0'
    (root / 'pom.xml').write_text(
        f'<project xmlns="{namespace}"><modelVersion>4.0.0</modelVersion>'
        '<groupId>example</groupId><artifactId>reactor</artifactId><version>1</version>'
        '<packaging>pom</packaging><modules>' + ''.join(f'<module>{name}</module>' for name in names)
        + '</modules></project>')
    for name in names:
        directory = root / name
        directory.mkdir(parents=True, exist_ok=True)
        selection = ('<build><plugins><plugin><artifactId>pitest-maven</artifactId><configuration>'
                     '<targetClasses><param>example.Core</param></targetClasses>'
                     '</configuration></plugin></plugins></build>') if mutation else ''
        (directory / 'pom.xml').write_text(
            f'<project xmlns="{namespace}"><modelVersion>4.0.0</modelVersion>'
            '<parent><groupId>example</groupId><artifactId>reactor</artifactId><version>1</version>'
            f'</parent><artifactId>{name}</artifactId>{selection}</project>')


def write_test_report(path, tests=1, skipped=0):
    path.parent.mkdir(parents=True, exist_ok=True)
    suite = ET.Element('testsuite', tests=str(tests), failures='0', errors='0', skipped=str(skipped))
    for index in range(tests):
        case = ET.SubElement(suite, 'testcase', name='case' + str(index))
        if index < skipped:
            ET.SubElement(case, 'skipped')
    ET.ElementTree(suite).write(path, encoding='unicode')


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

    def test_pure_domain_cannot_import_jdbc(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'import java.sql.Connection;')
            self.assertIn('domain framework', architecture.inspect(root)[0])

    def test_byteguard_analysis_core_cannot_import_frameworks(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / 'projects/09-byteguard/analysis-core/src/main/java/example/Analyzer.java'
            source.parent.mkdir(parents=True)
            source.write_text('import org.springframework.stereotype.Service;')
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

    def test_cross_project_artifact_is_rejected_without_java_imports(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'import java.time.Instant;')
            for slug, artifact, dependency in [
                    ('01-clearledger', 'ledger-api', 'quorumkv-raft'),
                    ('03-quorumkv', 'quorumkv-raft', None)]:
                pom = root / f'projects/{slug}/service/pom.xml'
                pom.parent.mkdir(parents=True)
                deps = (f'<dependencies><dependency><groupId>io.github.cheshmakzanoon.portfolio</groupId>'
                        f'<artifactId>{dependency}</artifactId></dependency></dependencies>') if dependency else ''
                pom.write_text(f'<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>{artifact}</artifactId>{deps}</project>')
            self.assertTrue(any('cross-project dependency' in item for item in architecture.inspect(root)))

    def test_pure_module_rejects_declared_framework_dependency(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'import java.time.Instant;')
            pom = root / 'projects/01-clearledger/ledger-domain/pom.xml'
            for scope, should_fail in [('compile', True), ('provided', True),
                                       ('runtime', True), ('test', False)]:
                pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                               '<artifactId>ledger-domain</artifactId><dependencies><dependency>'
                               '<groupId>org.springframework</groupId><artifactId>spring-core</artifactId>'
                               f'<scope>{scope}</scope></dependency></dependencies></project>')
                failures = architecture.inspect(root)
                self.assertEqual(should_fail, any('domain framework dependency' in item for item in failures), scope)

    def test_profile_dependencies_cannot_bypass_project_or_domain_boundaries(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'import java.time.Instant;')
            target = root / 'projects/03-quorumkv/raft-core/pom.xml'
            target.parent.mkdir(parents=True)
            target.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                              '<artifactId>quorumkv-raft</artifactId></project>')
            pom = root / 'projects/01-clearledger/ledger-domain/pom.xml'
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                           '<artifactId>ledger-domain</artifactId><profiles><profile><id>integration</id>'
                           '<activation><activeByDefault>true</activeByDefault></activation><dependencies>'
                           '<dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId></dependency>'
                           '<dependency><groupId>io.github.cheshmakzanoon.portfolio</groupId><artifactId>quorumkv-raft</artifactId></dependency>'
                           '</dependencies></profile></profiles></project>')
            failures = architecture.inspect(root)
            self.assertTrue(any('domain framework dependency' in item for item in failures))
            self.assertTrue(any('cross-project dependency' in item for item in failures))

    def test_compiled_qualified_references_cannot_bypass_import_checks(self):
        compiler = shutil.which('javac') or str(Path(os.environ.get('JAVA_HOME', '/workspace/.tools/jdk-25')) / 'bin/javac')
        jdeps = str(Path(compiler).resolve().with_name('jdeps'))
        self.assertTrue(Path(compiler).is_file(), 'Tooling tests require the documented JDK')
        for namespace in ('io.github.cheshmakzanoon.portfolio.clearledger', 'example'):
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                project = root / 'projects/01-clearledger'
                project.mkdir(parents=True)
                make_reactor(project, ('ledger-domain',))
                source = project / 'ledger-domain/src/main/java/example/Domain.java'
                source.parent.mkdir(parents=True)
                source.write_text('package ' + namespace + '; '
                                  'public class Domain { org.springframework.Context context; '
                                  'io.github.cheshmakzanoon.portfolio.quorumkv.Other foreign; }')
                support = root / 'fixture-support'
                support.mkdir()
                context = support / 'Context.java'
                context.write_text('package org.springframework; public class Context {}')
                other = support / 'Other.java'
                other.write_text('package io.github.cheshmakzanoon.portfolio.quorumkv; public class Other {}')
                classes = project / 'ledger-domain/target/classes'
                classes.mkdir(parents=True)
                subprocess.run([compiler, '--release', '17', '-d', str(classes), str(source), str(context), str(other)],
                               check=True, capture_output=True, text=True)
                self.assertEqual([], architecture.inspect(root))
                failures = architecture.inspect_bytecode(project, jdeps)
                self.assertTrue(any('cross-project bytecode dependency' in failure for failure in failures))
                self.assertTrue(any('domain framework bytecode dependency' in failure for failure in failures))

    def test_compiled_architecture_requires_build_outputs(self):
        with tempfile.TemporaryDirectory() as temp:
            project = Path(temp) / '01-clearledger'
            project.mkdir()
            make_reactor(project, ('ledger-domain',))
            source = project / 'ledger-domain/src/main/java/Domain.java'
            source.parent.mkdir(parents=True)
            source.write_text('class Domain {}')
            self.assertTrue(architecture.inspect_bytecode(project, '/unneeded/jdeps'))


class MutationEvidenceGateTest(unittest.TestCase):
    def invoke(self, statuses, started='1', timestamp=None):
        with tempfile.TemporaryDirectory() as temp:
            make_reactor(Path(temp), mutation=True)
            report = Path(temp) / 'core/target/pit-reports/mutations.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<mutations>' + ''.join(
                f'<mutation status="{status}"/>' for status in statuses) + '</mutations>')
            if timestamp is not None:
                os.utime(report, (timestamp, timestamp))
            env = {**os.environ}
            env.pop('PORTFOLIO_TEST_RUN_STARTED', None)
            if started is not None:
                env['PORTFOLIO_TEST_RUN_STARTED'] = started
            return subprocess.run(
                ['python3', str(Path(__file__).with_name('check-mutations.py')), temp],
                env=env,
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

    def test_invalid_cutoff_and_unfinished_mutants_fail(self):
        self.assertEqual(1, self.invoke(['KILLED'], started=None))
        self.assertEqual(1, self.invoke(['KILLED'], started='0'))
        self.assertEqual(1, self.invoke(['KILLED'], started='invalid'))
        self.assertEqual(1, self.invoke(['KILLED'] * 99 + ['NOT_STARTED']))


class TestEvidenceGateTest(unittest.TestCase):
    def invoke(self, suites, action='verify', started='1', timestamp=None):
        with tempfile.TemporaryDirectory() as temp:
            make_reactor(Path(temp))
            for index, (kind, tests, skipped) in enumerate(suites):
                report = Path(temp) / f'core/target/{kind}-reports/TEST-suite{index}.xml'
                write_test_report(report, tests, skipped)
                source = Path(temp) / 'core/src/test/java/example' / ('Suite' + str(index) + ('IT.java' if kind == 'failsafe' else 'Test.java'))
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text('// selected Maven test source\n')
                if timestamp is not None:
                    os.utime(report, (timestamp, timestamp))
            env = {**os.environ}
            env.pop('PORTFOLIO_TEST_RUN_STARTED', None)
            if started is not None:
                env['PORTFOLIO_TEST_RUN_STARTED'] = started
            return subprocess.run(
                ['python3', str(Path(__file__).with_name('check-tests.py')), temp, action],
                env=env, capture_output=True, text=True).returncode

    def test_current_nonempty_selection_passes(self):
        self.assertEqual(0, self.invoke([('surefire', 3, 0)]))
        self.assertEqual(0, self.invoke([('surefire', 3, 0), ('failsafe', 2, 0)], 'integration'))

    def test_mixed_required_skips_cannot_hide_behind_executed_tests(self):
        self.assertEqual(1, self.invoke([('surefire', 3, 1)]))
        self.assertEqual(1, self.invoke([('surefire', 3, 0), ('failsafe', 2, 1)], 'integration'))

    def test_missing_stale_or_invalid_run_identity_fails(self):
        self.assertEqual(1, self.invoke([]))
        self.assertEqual(1, self.invoke([('surefire', 3, 0)], started=None))
        self.assertEqual(1, self.invoke([('surefire', 3, 0)], started='0'))
        self.assertEqual(1, self.invoke([('surefire', 3, 0)], started='invalid'))
        self.assertEqual(1, self.invoke([('surefire', 3, 0)], started='200', timestamp=100))

    def test_integration_requires_executed_integration_suite(self):
        self.assertEqual(1, self.invoke([('surefire', 3, 0)], 'integration'))
        self.assertEqual(1, self.invoke([('surefire', 3, 0), ('failsafe', 2, 2)], 'integration'))


class ContainerInventoryGateTest(unittest.TestCase):
    def make_root(self, directory, image):
        import json
        root = Path(directory)
        inventory = root / 'docs/execution/container-images.json'
        inventory.parent.mkdir(parents=True)
        inventory.write_text(json.dumps({'images': [{'repoDigests': ['postgres@sha256:' + 'a' * 64]}]}))
        compose = root / 'projects/01-clearledger/infra/compose.yaml'
        compose.parent.mkdir(parents=True)
        compose.write_text('services:\n  postgres:\n    image: ' + image + '\n')
        return root

    def test_exact_tag_and_digest_select_canonical_target(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'postgres:18.3@sha256:' + 'a' * 64)
            self.assertEqual([{'id': 'image-01', 'image': 'postgres@sha256:' + 'a' * 64}],
                             container_images.inspect(root)['include'])

    def test_mutable_image_and_undeclared_digest_fail(self):
        for image in ('postgres:18.3', 'postgres:18.3@sha256:' + 'b' * 64):
            with self.subTest(image=image), tempfile.TemporaryDirectory() as temp:
                with self.assertRaises(ValueError):
                    container_images.inspect(self.make_root(temp, image))

    def test_mutable_testcontainers_reference_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'postgres:18.3@sha256:' + 'a' * 64)
            source = root / 'projects/01-clearledger/verification/src/test/java/DatabaseIT.java'
            source.parent.mkdir(parents=True)
            source.write_text('DockerImageName.parse(\n"postgres:18.3");')
            with self.assertRaises(ValueError):
                container_images.inspect(root)

    def test_flow_yaml_and_unresolved_container_arguments_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.make_root(temp, 'postgres:18.3@sha256:' + 'a' * 64)
            extra = root / 'projects/01-clearledger/infra/extra.yaml'
            extra.write_text('services: {extra: {image: "postgres:latest"}}\n')
            with self.assertRaises(ValueError):
                container_images.inspect(root)
            extra.unlink()
            source = root / 'projects/01-clearledger/verification/src/test/java/DatabaseIT.java'
            source.parent.mkdir(parents=True)
            for text in ('new PostgreSQLContainer<>(IMAGE);', 'DockerImageName.parse(IMAGE);'):
                source.write_text(text)
                with self.subTest(text=text), self.assertRaises(ValueError):
                    container_images.inspect(root)


class MarkdownLinkGateTest(unittest.TestCase):
    def test_discovery_checks_new_documents_and_excludes_deleted_and_ignored_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            deleted = root / 'deleted.md'
            deleted.write_text('# Removed\n')
            subprocess.run(['git', 'add', 'deleted.md'], cwd=root, check=True)
            deleted.unlink()
            (root / '.gitignore').write_text('ignored.md\n')
            (root / 'ignored.md').write_text('[bad](missing.md)\n')
            added = root / 'new.md'
            added.write_text('[bad](missing.md)\n')
            files = markdown_links.markdown_files(root)
            self.assertEqual([added], files)
            self.assertEqual(1, len(markdown_links.inspect(root, files)))

    def test_targets_and_duplicate_heading_anchors(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / 'README.md'
            other = root / 'Other File.md'
            other.write_text('# Hello, world!\n# Hello, world!\n')
            source.write_text('[one](<Other File.md#hello-world>)\n[two](Other%20File.md#hello-world-1)\n')
            self.assertEqual([], markdown_links.inspect(root, [source, other]))
            source.write_text('[bad](Other%20File.md#missing)\n[absent](absent.md)\n')
            self.assertEqual(2, len(markdown_links.inspect(root, [source, other])))

    def test_fenced_examples_are_not_live_links(self):
        self.assertEqual([], list(markdown_links.destinations('```md\n[x](absent.md)\n```\n')))

    def test_inline_examples_and_fenced_anchors_are_not_live(self):
        self.assertEqual([], list(markdown_links.destinations('Use `[x](absent.md)` as an example.')))
        self.assertEqual([], list(markdown_links.destinations('Use ``[x](absent.md)`` as an example.')))
        self.assertNotIn('example', markdown_links.anchors('```html\n<a id="example"></a>\n```\n'))
        self.assertNotIn('example', markdown_links.anchors('Use `<a id="example"></a>` as an example.'))
        self.assertEqual(['missing.md'], list(markdown_links.destinations('Use ``[broken](missing.md)`')))


class SourceSarifGateTest(unittest.TestCase):
    def document(self, severity='8.0', results=None):
        rule = {'id': 'java/example', 'properties': {'security-severity': severity}}
        return {'version': '2.1.0', 'runs': [{'tool': {'driver': {'rules': [rule]}},
                                           'results': [] if results is None else results}]}

    def test_completed_scan_without_findings_passes(self):
        self.assertEqual([], source_sarif.inspect([('scan.sarif', self.document())])['failures'])

    def test_high_findings_are_not_hidden_by_success_or_suppression(self):
        document = self.document(results=[{'ruleId': 'java/example', 'level': 'warning',
                                           'suppressions': [{'status': 'accepted'}]}])
        self.assertTrue(source_sarif.inspect([('scan.sarif', document)])['failures'])

    def test_missing_failed_or_invalid_scan_cannot_pass(self):
        self.assertTrue(source_sarif.inspect([])['failures'])
        document = self.document()
        document['runs'][0]['invocations'] = [{'executionSuccessful': False}]
        self.assertTrue(source_sarif.inspect([('scan.sarif', document)])['failures'])
        document = self.document(severity='NaN', results=[{'ruleId': 'java/example'}])
        self.assertTrue(source_sarif.inspect([('scan.sarif', document)])['failures'])


if __name__ == '__main__':
    unittest.main()
