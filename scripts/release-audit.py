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
                     'docs/CASE_STUDY.md', 'docs/REVIEW.md', 'docs/INTERVIEW.md',
                     'scripts/up.sh', 'scripts/down.sh', 'scripts/reset-demo.sh', 'scripts/demo.sh']:
        if not (path / relative).is_file():
            errors.append(f'Missing {project["slug"]}/{relative}')
    for relative in ['contracts', 'demo', 'docs/DECISIONS']:
        directory = path / relative
        if not directory.is_dir() or not any(p.is_file() and p.name != '.gitkeep' for p in directory.rglob('*')):
            errors.append(f'Missing substantive {project["slug"]}/{relative}')
    if not list(path.glob('**/src/main/java/**/*.java')):
        errors.append(f'{project["slug"]}: no Java implementation')
criteria = (root / 'docs/release.md').read_text()
required = set(re.findall(r'P\d{2}-A\d+|R-A\d{2}', criteria))
rows = {}
for line in (root / 'docs/acceptance-matrix.md').read_text().splitlines():
    parts = [p.strip() for p in line.split('|')]
    if len(parts) >= 5 and parts[1] in required:
        rows[parts[1]] = parts[3]
        if parts[3] == 'PASS':
            references = re.findall(r'(?:projects|docs|scripts|integration)/[A-Za-z0-9_./-]+', parts[4])
            if not references:
                errors.append(f'{parts[1]}: PASS has no repository evidence reference')
            for reference in references:
                if not (root / reference.rstrip('.')).exists():
                    errors.append(f'{parts[1]}: missing evidence reference {reference}')
for key in sorted(required):
    if rows.get(key) != 'PASS':
        errors.append(f'{key}: {rows.get(key, "MISSING")}')
for error in errors:
    print(error, file=sys.stderr)
print(f'Release audit: {len(required)} mandatory gates; {len(errors)} outstanding errors')
sys.exit(bool(errors))
