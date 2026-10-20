#!/usr/bin/env python3
"""Fail on cross-project production imports, framework leakage, or reactor cycles."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
PURE = {'ledger-domain', 'workflow-model', 'workflow-engine', 'raft-core', 'matching-core',
        'policy-core', 'routeweaver-domain', 'analysis', 'analysis-core', 'engine'}


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


if __name__ == '__main__':
    problems = inspect(ROOT)
    for problem in problems:
        print(problem, file=sys.stderr)
    print(f'Architecture scan: {len(problems)} violation(s)')
    sys.exit(bool(problems))
