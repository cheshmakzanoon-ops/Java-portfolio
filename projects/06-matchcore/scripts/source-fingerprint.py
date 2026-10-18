#!/usr/bin/env python3
"""Fingerprint executable source/build inputs; exclude generated outputs and evidence text."""
import hashlib
from pathlib import Path

project = Path(__file__).resolve().parent.parent
repo = project.parent.parent
paths = [repo / 'pom.xml', repo / 'mvnw', repo / 'scripts/cloud-env.sh']
paths += list((repo / '.mvn').rglob('*'))
for directory in ('matching-core', 'journal', 'gateway', 'simulator', 'benchmarks'):
    paths += [project / directory / 'pom.xml']
    paths += list((project / directory / 'src').rglob('*'))
paths += [project / 'pom.xml']
for directory in ('scripts', 'fixtures'):
    paths += list((project / directory).rglob('*'))
digest = hashlib.sha256()
for path in sorted(set(p for p in paths if p.is_file())):
    digest.update(str(path.relative_to(repo)).encode())
    digest.update(b'\0')
    digest.update(path.read_bytes())
    digest.update(b'\0')
print(digest.hexdigest())
