#!/usr/bin/env python3
"""Identify executable inputs and the shared, released build contract; omit generated outputs."""
import hashlib
from pathlib import Path
project = Path(__file__).resolve().parents[1]
root = project.parents[1]
ignored = {'target', 'docs', 'evidence', '__pycache__', '.jqwik-database'}
files = [p for p in project.rglob('*') if p.is_file() and p.name != 'README.md'
         and not ignored.intersection(p.relative_to(project).parts)]
files += [p for p in (root/'pom.xml', root/'mvnw', root/'scripts/cloud-env.sh',
                     root/'scripts/with-build-slot.sh', root/'.mvn/maven.config',
                     root/'.mvn/wrapper/maven-wrapper.properties') if p.exists()]
h = hashlib.sha256()
for p in sorted(files):
    data = p.read_bytes()
    h.update(str(p.relative_to(root)).encode() + b'\0' + len(data).to_bytes(8, 'big') + data)
print('working-tree-sha256:' + h.hexdigest())
