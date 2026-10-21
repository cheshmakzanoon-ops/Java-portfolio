#!/usr/bin/env python3
"""Fail on cross-project production imports, framework leakage, or reactor cycles."""
from pathlib import Path
import argparse
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

from maven_evidence import EvidenceError, reactor_modules

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
PURE = {'ledger-domain', 'workflow-model', 'workflow-engine', 'raft-core', 'matching-core',
        'policy-core', 'switchback-domain', 'analysis', 'analysis-core', 'engine'}


def inspect(root):
    errors = []
    graph = {}
    owners = {}
    dependencies = []
    for project in sorted((root / 'projects').glob('[0-9][0-9]-*')):
        slug = project.name.split('-', 1)[1]
        prefix = f'io.github.cheshmakzanoon.portfolio.{slug}.'
        for source in project.glob('**/src/main/java/**/*.java'):
            if 'target' in source.parts:
                continue
            text = source.read_text(encoding='utf-8')
            for imported in re.findall(r'^import\s+(?:static\s+)?([\w.]+)', text, re.M):
                if imported.startswith('io.github.cheshmakzanoon.portfolio.') and not imported.startswith(prefix):
                    errors.append(f'{source.relative_to(root)}: cross-project import {imported}')
                module = source.relative_to(project).parts[0]
                if module in PURE and imported.startswith(('org.springframework.', 'jakarta.persistence.', 'org.hibernate.', 'java.sql.', 'javax.sql.')):
                    errors.append(f'{source.relative_to(root)}: domain framework import {imported}')
        for pom in project.glob('**/pom.xml'):
            if 'target' in pom.parts:
                continue
            tree = ET.parse(pom).getroot()
            aid = tree.findtext('m:artifactId', namespaces=NS)
            if aid in graph:
                errors.append(f'Duplicate artifact ID: {aid}')
            owners[aid] = project.name
            declared = (tree.findall('m:dependencies/m:dependency', NS)
                        + tree.findall('m:profiles/m:profile/m:dependencies/m:dependency', NS))
            for dependency in declared:
                group = dependency.findtext('m:groupId', namespaces=NS)
                artifact = dependency.findtext('m:artifactId', namespaces=NS)
                scope = dependency.findtext('m:scope', default='compile', namespaces=NS)
                dependencies.append((pom, aid, group, artifact, scope))
                if (pom.parent.name in PURE and scope in ('compile', 'runtime', 'provided')
                        and group and group.startswith(('org.springframework', 'org.hibernate',
                                                       'jakarta.persistence', 'javax.persistence'))):
                    errors.append(f'{pom.relative_to(root)}: domain framework dependency {group}:{artifact}')
            graph[aid] = {x.findtext('m:artifactId', namespaces=NS)
                          for x in declared
                          if x.findtext('m:groupId', namespaces=NS) == 'io.github.cheshmakzanoon.portfolio'}
    for pom, aid, group, artifact, scope in dependencies:
        if (group == 'io.github.cheshmakzanoon.portfolio' and artifact in owners
                and owners[aid] != owners[artifact]):
            errors.append(f'{pom.relative_to(root)}: cross-project dependency {artifact} ({scope})')
    active, done = set(), set()
    def visit(node):
        if node in active:
            errors.append(f'Artifact dependency cycle at {node}')
            return
        if node in done:
            return
        active.add(node)
        for dependency in graph.get(node, ()):
            visit(dependency)
        active.remove(node)
        done.add(node)
    for node in graph:
        visit(node)
    return errors


def inspect_bytecode(project, jdeps=None):
    """Check compiled type references, including fully qualified uses without imports."""
    project = Path(project).resolve()
    prefix = 'io.github.cheshmakzanoon.portfolio.' + project.name.split('-', 1)[1] + '.'
    executable = jdeps or shutil.which('jdeps')
    if executable is None:
        return ['Bytecode architecture requires jdeps from the pinned JDK on PATH']
    errors = []
    for module in reactor_modules(project):
        if not any((module.directory / 'src/main/java').rglob('*.java')):
            continue
        classes = module.directory / 'target/classes'
        if not classes.resolve().is_relative_to(project) or not any(classes.rglob('*.class')):
            errors.append(str(module.directory) + ': missing or external compiled production classes')
            continue
        try:
            result = subprocess.run(
                [str(executable), '-q', '-verbose:class', '-filter:none', '--ignore-missing-deps', str(classes)],
                text=True, capture_output=True, timeout=60, check=True)
        except (OSError, subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
            errors.append(str(module.directory) + ': jdeps failed; use the pinned JDK and build the module: ' + str(error))
            continue
        for line in result.stdout.splitlines():
            fields = line.split()
            if len(fields) < 3 or fields[1] != '->':
                continue
            source, target = fields[0], fields[2]
            if target.startswith('io.github.cheshmakzanoon.portfolio.') and not target.startswith(prefix):
                errors.append(str(module.directory) + ': cross-project bytecode dependency ' + source + ' -> ' + target)
            if module.directory.name in PURE and target.startswith(
                    ('org.springframework.', 'jakarta.persistence.', 'org.hibernate.', 'java.sql.', 'javax.sql.')):
                errors.append(str(module.directory) + ': domain framework bytecode dependency ' + source + ' -> ' + target)
    return sorted(set(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bytecode', metavar='PROJECT', help='also inspect compiled references for a project slug/path or all')
    args = parser.parse_args()
    problems = inspect(ROOT)
    if args.bytecode:
        projects = sorted((ROOT / 'projects').glob('[0-9][0-9]-*'))
        if args.bytecode != 'all':
            requested = (ROOT / args.bytecode).resolve()
            projects = [p for p in projects if p.resolve() == requested or p.name.split('-', 1)[1] == args.bytecode]
            if not projects:
                problems.append('Unknown bytecode project: ' + args.bytecode)
        for project in projects:
            problems.extend(inspect_bytecode(project))
    for problem in problems:
        print(problem, file=sys.stderr)
    print(f'Architecture scan: {len(problems)} violation(s)')
    return bool(problems)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except EvidenceError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
