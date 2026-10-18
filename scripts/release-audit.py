#!/usr/bin/env python3
"""Read-only release audit: incomplete mandatory acceptance always fails."""
from pathlib import Path
import json
import re
import sys

root = Path(__file__).resolve().parents[1]
catalog = json.loads((root / 'catalog/projects.yaml').read_text())
errors = []
projects = catalog['projects']
if len(projects) != 10 or len({p['slug'] for p in projects}) != 10:
    errors.append('Catalog must contain exactly ten unique projects')
ports = set()
for project in projects:
    path = root / project['path']
    if not path.is_dir() or not (path / 'pom.xml').is_file():
        errors.append(f'Missing project {path}')
    for port in project['ports']:
        if port in ports:
            errors.append(f'Duplicate port {port}')
        ports.add(port)
    for relative in ['README.md', 'docs/STATUS.md', 'docs/ARCHITECTURE.md', 'docs/INVARIANTS.md',
                     'docs/OPERATIONS.md', 'docs/SECURITY.md', 'docs/PERFORMANCE.md',
                     'docs/CASE_STUDY.md', 'docs/REVIEW.md', 'docs/INTERVIEW.md', 'scripts/demo.sh']:
        if not (path / relative).is_file():
            errors.append(f'Missing {project["slug"]}/{relative}')
    if not list(path.glob('**/src/main/java/**/*.java')):
        errors.append(f'{project["slug"]}: no Java implementation')
brief = (root / 'docs/JAVA_PORTFOLIO_MASTER_EXECUTION_BRIEF.md').read_text()
required = set(re.findall(r'P\d{2}-A\d+|R-A\d{2}', brief))
rows = {}
for line in (root / 'docs/acceptance-matrix.md').read_text().splitlines():
    parts = [p.strip() for p in line.split('|')]
    if len(parts) >= 5 and parts[1] in required:
        rows[parts[1]] = parts[3]
for key in sorted(required):
    if rows.get(key) != 'PASS':
        errors.append(f'{key}: {rows.get(key, "MISSING")}')
for error in errors:
    print(error, file=sys.stderr)
print(f'Release audit: {len(required)} mandatory gates; {len(errors)} outstanding errors')
sys.exit(bool(errors))
