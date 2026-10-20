"""Read standard reactor/report contracts without invoking Maven or trusting aggregates."""
from dataclasses import dataclass
from pathlib import Path
import math
import os
import xml.etree.ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
UNIT_PATTERNS = ('Test*.java', '*Test.java', '*Tests.java', '*TestCase.java')
INTEGRATION_PATTERNS = ('IT*.java', '*IT.java', '*ITCase.java')
COUNT_KEYS = ('tests', 'failures', 'errors', 'skipped')


class EvidenceError(ValueError):
    """An incomplete run or invalid runner artifact cannot establish success."""


def run_started():
    try:
        value = float(os.environ['PORTFOLIO_TEST_RUN_STARTED'])
        if not math.isfinite(value) or value <= 0:
            raise ValueError('invalid cutoff')
        return value
    except (KeyError, ValueError) as error:
        raise EvidenceError('Set PORTFOLIO_TEST_RUN_STARTED to the actual run start before Maven.') from error


@dataclass(frozen=True)
class Module:
    directory: Path
    pom: ET.Element

    def has_tests(self, integration=False):
        source = self.directory / 'src/test/java'
        patterns = INTEGRATION_PATTERNS if integration else UNIT_PATTERNS
        return any(any(source.rglob(pattern)) for pattern in patterns)

    def mutation_configurations(self):
        selected = []
        for plugin in self.pom.findall('m:build/m:plugins/m:plugin', NS) + self.pom.findall(
                'm:profiles/m:profile/m:build/m:plugins/m:plugin', NS):
            if plugin.findtext('m:artifactId', namespaces=NS) != 'pitest-maven':
                continue
            configurations = plugin.findall('m:configuration', NS) + plugin.findall(
                'm:executions/m:execution/m:configuration', NS)
            for configuration in configurations:
                if configuration.findtext('m:skip', namespaces=NS) == 'true':
                    continue
                targets = configuration.find('m:targetClasses', NS)
                if targets is not None and any((child.text or '').strip() for child in targets):
                    selected.append(configuration)
        return selected

    def mutation_selected(self):
        return bool(self.mutation_configurations())

    def mutation_threshold(self):
        thresholds = [70]
        for configuration in self.mutation_configurations():
            value = configuration.findtext('m:mutationThreshold', default='70', namespaces=NS)
            try:
                threshold = int(value)
                if not 70 <= threshold <= 100:
                    raise ValueError('threshold outside repository policy')
                thresholds.append(threshold)
            except ValueError as error:
                raise EvidenceError(str(self.directory) + ': invalid mutation threshold ' + value) from error
        return max(thresholds)


def reactor_modules(root, profiles=()):
    root = Path(root).resolve()
    modules, visited = [], set()

    def visit(directory):
        directory = directory.resolve()
        if not directory.is_relative_to(root):
            raise EvidenceError('Reactor module escapes the selected project: ' + str(directory))
        if directory in visited:
            raise EvidenceError('Duplicate or cyclic reactor module: ' + str(directory))
        visited.add(directory)
        try:
            pom = ET.parse(directory / 'pom.xml').getroot()
        except (OSError, ET.ParseError) as error:
            raise EvidenceError('Cannot read reactor POM: ' + str(directory / 'pom.xml')) from error
        modules.append(Module(directory, pom))
        children = list(pom.findall('m:modules/m:module', NS))
        declared_profiles = pom.findall('m:profiles/m:profile', NS)
        explicitly_active = any(profile.findtext('m:id', namespaces=NS) in profiles for profile in declared_profiles)
        for profile in declared_profiles:
            active = profile.findtext('m:id', namespaces=NS) in profiles
            default = profile.findtext('m:activation/m:activeByDefault', namespaces=NS) == 'true'
            if active or default and not explicitly_active:
                children.extend(profile.findall('m:modules/m:module', NS))
        for child in children:
            name = (child.text or '').strip()
            if not name or '${' in name:
                raise EvidenceError('Unresolved reactor module in ' + str(directory / 'pom.xml'))
            visit(directory / name)

    visit(root)
    return modules


def fresh_reports(module, kind, started):
    directory = module.directory / 'target' / (kind + '-reports')
    pattern = '**/mutations.xml' if kind == 'pit' else 'TEST-*.xml'
    return sorted(path for path in directory.glob(pattern) if path.stat().st_mtime >= started)


def test_counts(report):
    try:
        suite = ET.parse(report).getroot()
        if suite.tag != 'testsuite':
            raise ValueError('expected testsuite')
        counts = {key: int(suite.attrib[key]) for key in COUNT_KEYS}
        cases = suite.findall('testcase')
        actual = {'tests': len(cases), **{
            key: sum(case.find(tag) is not None for case in cases)
            for key, tag in (('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped'))}}
        if any(value < 0 for value in counts.values()) or counts != actual:
            raise ValueError('suite counters disagree with test cases')
        if sum(counts[key] for key in COUNT_KEYS[1:]) > counts['tests']:
            raise ValueError('inconsistent test outcomes')
        return counts
    except (OSError, ET.ParseError, KeyError, ValueError) as error:
        raise EvidenceError(str(report) + ': invalid or inconsistent test report') from error
