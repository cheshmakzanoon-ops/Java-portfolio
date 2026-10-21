#!/usr/bin/env python3
"""Hash source/build inputs; runtime recordings, docs and evidence are excluded."""
from pathlib import Path
import hashlib
project = Path(__file__).resolve().parent.parent
repo = project.parent.parent
files = [repo / name for name in ('pom.xml', 'mvnw', 'scripts/cloud-env.sh', 'scripts/with-build-slot.sh')]
files += list((repo / '.mvn').rglob('*'))
files += [f for f in project.rglob('*') if 'target' not in f.parts and (f.suffix in ('.java', '.sh', '.py') or f.name == 'pom.xml')]
digest = hashlib.sha256()
for path in sorted(set(p for p in files if p.is_file())):
    digest.update(path.relative_to(repo).as_posix().encode() + b'\0' + path.read_bytes() + b'\0')
print(digest.hexdigest())
