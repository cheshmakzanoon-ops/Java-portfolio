#!/usr/bin/env python3
"""Post-terminal preservation only: copy exact quiet16 bytes without changing originals."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat

ROOT = Path('/workspace/Java-portfolio')
PROJECT = ROOT / 'projects/01-clearledger'
ATTEMPT = ROOT / '.work/A01/full-reference/20261018T084821Z-d8958190'
RESERVE = 2 * 1024**3
MAX_BYTES = 12 * 1024**3
MAX_FILES = 4096


def fingerprint(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024**2), b''):
            result.update(block)
    return result.hexdigest()


def main():
    original = json.loads((ATTEMPT / 'run.json').read_text())
    if original['state'] != 'FAIL' or original['exitCode'] != 1 or original['downExitCode'] != 0:
        raise RuntimeError('Expected terminal failed quiet16 with successful teardown')
    files = set()

    def add(path):
        metadata = path.lstat()
        if stat.S_ISLNK(metadata.st_mode):
            raise RuntimeError('Symlink refused: ' + str(path))
        if stat.S_ISREG(metadata.st_mode):
            files.add(path)
        elif stat.S_ISDIR(metadata.st_mode):
            if len(path.relative_to(ROOT).parts) > 24:
                raise RuntimeError('Unexpected preservation depth')
            for child in path.iterdir():
                add(child)
        else:
            raise RuntimeError('Special file refused: ' + str(path))
        if len(files) > MAX_FILES:
            raise RuntimeError('File budget exceeded; no evidence omitted')

    add(ATTEMPT)
    for relative in original['baseline']['sourceManifest']:
        add(ROOT / relative)
    for relative in ('pom.xml', 'mvnw', '.mvn', '.work/A01/run-full-reference.py',
                     '.work/A01/run-full-reference.sh', '.work/A01/full-reference-runner-history',
                     '.work/A01/full-reference-real-descendants-result.json'):
        add(ROOT / relative)
    for relative in ('target/benchmark', 'target/microbenchmark/full', 'target/prepared-runtime.json',
                     'target/prepared-jmh.json', 'evidence/jmh-current.json'):
        add(PROJECT / relative)
    performance = PROJECT / 'evidence/performance-current.json'
    if performance.exists():
        add(performance)
    for module in PROJECT.glob('ledger-*'):
        if not module.is_dir():
            continue
        classes = module / 'target/classes'
        if classes.exists():
            add(classes)
        for artifact in (module / 'target').glob('*.jar'):
            add(artifact)
        dependencies = module / 'target/dependency'
        if dependencies.exists():
            add(dependencies)
    total = sum(path.stat().st_size for path in files)
    if total > MAX_BYTES or shutil.disk_usage('/tmp').free < total + RESERVE:
        raise RuntimeError('Insufficient bounded destination capacity; originals retained')
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    destination = Path('/tmp') / ('clearledger-quiet16-preservation-' + stamp)
    destination.mkdir(exist_ok=False)
    envelope = {'schemaVersion': 1, 'scope': 'Post-terminal copy; no measurements rerun',
                'originalAttempt': str(ATTEMPT), 'originalRunSha256': fingerprint(ATTEMPT / 'run.json'),
                'originalExitCode': original['exitCode'], 'originalState': original['state'],
                'originalFailure': original['failure'],
                'originalPreservationError': original['currentArtifactPreservationError'],
                'measurementProofStatus': 'FAIL: two passed trials, actual32-writer failure; nine-run study incomplete',
                'runnerSha256': original['runnerSha256'], 'sourceBaseline': original['baseline'],
                'plannedFiles': len(files), 'plannedBytes': total, 'reserveBytes': RESERVE,
                'preservationStatus': 'COPYING', 'files': []}
    manifest = destination / 'preservation.json'

    def save():
        temporary = destination / 'preservation.json.tmp'
        temporary.write_text(json.dumps(envelope, indent=2) + '\n')
        temporary.replace(manifest)

    save()
    try:
        for source in sorted(files):
            before = source.stat()
            target = destination / 'snapshot' / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            with source.open('rb') as incoming, target.open('xb') as outgoing:
                for block in iter(lambda: incoming.read(1024**2), b''):
                    digest.update(block)
                    outgoing.write(block)
            after = source.stat()
            if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
                raise RuntimeError('Source changed while preserving: ' + str(source))
            expected = digest.hexdigest()
            if fingerprint(target) != expected:
                raise RuntimeError('Copied hash mismatch: ' + str(source))
            relative = str(source.relative_to(ROOT))
            certified = original['baseline']['sourceManifest'].get(relative)
            if certified is not None and certified != expected:
                raise RuntimeError('Source differs from failed-attempt baseline: ' + relative)
            envelope['files'].append({'path': relative, 'bytes': before.st_size, 'sha256': expected})
            if len(envelope['files']) % 32 == 0:
                save()
        if fingerprint(ATTEMPT / 'run.json') != envelope['originalRunSha256']:
            raise RuntimeError('Original terminal manifest changed')
        envelope.update(preservationStatus='PASS', copiedFiles=len(envelope['files']),
                        copiedBytes=sum(row['bytes'] for row in envelope['files']))
    except BaseException as error:
        envelope.update(preservationStatus='FAIL', failure=repr(error))
        raise
    finally:
        save()
        print(destination, flush=True)
    (ROOT / '.work/A01/quiet16-preservation-location.txt').write_text(str(destination) + '\n')


if __name__ == '__main__':
    main()
