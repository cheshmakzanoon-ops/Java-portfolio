#!/usr/bin/env python3
"""Bind launchable JAR bytes to the successfully verified source tree."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

ARTIFACTS = (
    'routeweaver-service/target/routeweaver-service-1.0.0-SNAPSHOT-exec.jar',
    'routeweaver-verification/target/routeweaver-verification-1.0.0-SNAPSHOT-driver.jar',
)


def digest(project, relative):
    path = project / relative
    current = path
    while current != project:
        if current.is_symlink():
            raise ValueError(f'symbolic link is not a verified artifact: {relative}')
        current = current.parent
    if not path.resolve().is_relative_to(project.resolve()) or not path.is_file():
        raise ValueError(f'expected regular in-project artifact: {relative}')
    before = path.stat()
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    after = path.stat()
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
            after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns):
        raise ValueError(f'artifact changed during verification: {relative}')
    return {'bytes': after.st_size, 'sha256': value.hexdigest()}


def receipt(project, source):
    return {'schemaVersion': 1, 'source': source,
            'artifacts': {name: digest(project, name) for name in ARTIFACTS}}


def write(project, source):
    target = project / '.runtime/build-receipt.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.parent.is_symlink():
        raise ValueError("runtime directory must not be a symbolic link")
    result = receipt(project, source)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=target.parent, delete=False) as stream:
            temporary = Path(stream.name)
            json.dump(result, stream, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def check(project, source):
    target = project / '.runtime/build-receipt.json'
    if target.is_symlink():
        raise ValueError('build receipt must not be a symbolic link')
    recorded = json.loads(target.read_text())
    current = receipt(project, source)
    if recorded != current:
        raise ValueError('runtime bytes or source differ from the verified build; run scripts/build.sh')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('write', 'check'))
    parser.add_argument('--source', required=True)
    args = parser.parse_args()
    if len(args.source) != 64 or any(c not in '0123456789abcdef' for c in args.source):
        raise SystemExit('source must be a SHA-256 fingerprint')
    project = Path(__file__).resolve().parents[1]
    try:
        {'write': write, 'check': check}[args.operation](project, args.source)
    except (OSError, ValueError, TypeError) as failure:
        raise SystemExit(str(failure)) from failure


if __name__ == '__main__':
    main()
