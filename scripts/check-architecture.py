#!/usr/bin/env python3
"""Fail on cross-project production imports, framework leakage, or reactor cycles."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
PURE = {'ledger-domain', 'workflow-model', 'workflow-engine', 'raft-core', 'matching-core',
        'policy-core', 'routeweaver-domain', 'analysis', 'engine'}


def inspect(root):
    errors = []
    graph = {}
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
                if module in PURE and imported.startswith(('org.springframework.', 'jakarta.persistence.', 'org.hibernate.')):
                    errors.append(f'{source.relative_to(root)}: domain framework import {imported}')
        for pom in project.glob('**/pom.xml'):
            if 'target' in pom.parts:
                continue
            tree = ET.parse(pom).getroot()
            aid = tree.findtext('m:artifactId', namespaces=NS)
            if aid in graph:
                errors.append(f'Duplicate artifact ID: {aid}')
            graph[aid] = {x.findtext('m:artifactId', namespaces=NS)
                          for x in tree.findall('m:dependencies/m:dependency', NS)
                          if x.findtext('m:groupId', namespaces=NS) == 'io.github.cheshmakzanoon.portfolio'}
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
