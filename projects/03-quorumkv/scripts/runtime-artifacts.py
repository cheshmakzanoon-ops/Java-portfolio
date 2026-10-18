#!/usr/bin/env python3
"""Bind a successful local build cache to every class/resource and dependency byte."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def ensure_direct(path):
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise ValueError(f'Symbolic link is not a verified artifact path: {path}')


def snapshot(project):
    paths = []
    for module in ('raft-core', 'raft-storage', 'raft-transport', 'kv-server', 'kv-client', 'raft-verification'):
        directory = project / module / 'target/classes'
        ensure_direct(directory)
        entries = list(directory.rglob('*'))
        for entry in entries:
            ensure_direct(entry)
        files = [p for p in entries if p.is_file()]
        if not files:
            raise ValueError(f'No compiled outputs in {module}')
        paths.extend(files)
    classpath = project / 'raft-verification/target/runtime-classpath.txt'
    paths.append(classpath)
    dependencies = classpath.read_text().strip().split(':')
    if not dependencies or any(not p for p in dependencies):
        raise ValueError('Missing runtime dependencies')
    paths.extend(Path(p) for p in dependencies)
    for path in paths:
        ensure_direct(path)
    return {str(p.resolve()): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(set(paths))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=('write', 'check'))
    parser.add_argument('--project', type=Path,
                        default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    project = args.project.resolve()
    receipt = project / 'raft-verification/target/verified-artifacts.json'
    try:
        current = {'schemaVersion': 1,
                   'sourceFingerprint': subprocess.check_output(
                       ['python3', str(project / 'scripts/source-fingerprint.py')],
                       text=True).strip(),
                   'artifacts': snapshot(project)}
        if args.operation == 'write':
            temporary = receipt.with_suffix('.json.tmp')
            temporary.write_text(json.dumps(current, indent=2) + '\n')
            temporary.replace(receipt)
        elif json.loads(receipt.read_text()) != current:
            raise ValueError('Compiled outputs, dependencies or source changed')
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f'Build artifact validation failed: {failure}\n')


if __name__ == '__main__':
    main()
