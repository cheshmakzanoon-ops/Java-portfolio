#!/usr/bin/env python3
"""Root-authorized exact replacement of independently restored quiet18 raw trees."""
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import time

ROOT = Path('/workspace/Java-portfolio')
OWNER = ROOT / '.work/A02'
PACKET = Path('/tmp/durableflow-quiet18-preservation-6f1_cbot')
ROOT_CLOSURE = ROOT / '.work/coordinator/durableflow-quiet18-archive-recovery.json'
PINS = {
    '20261018T095149-10000-746947': ('2890dc149177f59cfc6bd049b76b60db10c7e871b56f1e30baf03c5864a2240d',
                                    '277f027d6868ad148b0f85d95996b080cddc581c46748aecbe78d4da7911c028'),
    '20261018T101046-10000-760867': ('37a99f19534748febc7efbc56e9174fe67dd6f0a8827f251fc89efde75468b13',
                                    'fb1e2a33ba455b6558ff1369f37a8e0d85476f0567c5102f2f6e9e8fd54fc25d'),
}


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def main():
    started = time.monotonic()
    closure = json.loads(ROOT_CLOSURE.read_text())
    assert closure['status'] == 'PASS_INDEPENDENT_PHYSICAL_RESTORE_ORIGINALS_RETAINED'
    assert closure['files'] == 44 and closure['bytesRestoredAndCompared'] == 3912701887
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    destination = OWNER / 'archive/quiet18' / stamp
    destination.mkdir(parents=True, mode=0o700)
    spec = importlib.util.spec_from_file_location('closed_capture_archiver', OWNER / 'archive-quiet18.py')
    archive_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(archive_module)
    required = sum((PACKET / (name + suffix)).stat().st_size
                   for name in PINS for suffix in ('.tar.gz', '-manifest.json')) + 512 * 1024 ** 2
    assert shutil.disk_usage(ROOT).free > required
    report = dict(schemaVersion=1, status='RUNNING', startedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat(),
                  classification='Lossless storage finalization of FAILED incomplete quiet18; no performance pass',
                  persistentArchiveRoot=str(destination), mutations=[], originalsRemoved=False,
                  sourceAttempt=str(OWNER / 'full-reference-20261018T095104-746011'),
                  freeWorkspaceBytesBefore=shutil.disk_usage(ROOT).free,
                  independentRecoveryReceipt=str(ROOT_CLOSURE), independentRecoveryReceiptSha256=sha(ROOT_CLOSURE))
    receipt = destination / 'replacement.json'

    def save():
        temporary = receipt.with_suffix('.tmp')
        temporary.write_text(json.dumps(report, indent=2) + '\n')
        temporary.replace(receipt)

    save()
    try:
        captures = []
        for name, (archive_pin, manifest_pin) in PINS.items():
            for suffix, expected in (('.tar.gz', archive_pin), ('-manifest.json', manifest_pin)):
                original = PACKET / (name + suffix)
                assert sha(original) == expected
                copied = destination / original.name
                shutil.copy2(original, copied)
                assert sha(copied) == expected
            manifest = json.loads((destination / (name + '-manifest.json')).read_text())
            source = ROOT / 'projects/02-durableflow/target/performance' / name
            assert str(source) == manifest['source'] and source.resolve() == source
            archive_module.check_unchanged(source, manifest['entries'])
            for row in manifest['entries'].values():
                if row['kind'] == 'file':
                    assert archive_module.digest(source / row['relativePath']) == (row['bytes'], row['sha256'])
            archive_module.check_unchanged(source, manifest['entries'])
            captures.append(dict(source=str(source), archive=str(destination / (name + '.tar.gz')),
                                 archiveSha256=archive_pin, manifest=str(destination / (name + '-manifest.json')),
                                 manifestSha256=manifest_pin, originalFiles=manifest['originalFiles'],
                                 originalBytes=manifest['originalBytes']))
        for filename in ('RESTORE.md', 'verification.json'):
            original, copied = PACKET / filename, destination / filename
            shutil.copy2(original, copied)
            assert sha(original) == sha(copied)
        shutil.copy2(ROOT_CLOSURE, destination / 'independent-recovery.json')
        assert sha(destination / 'independent-recovery.json') == report['independentRecoveryReceiptSha256']
        report['captures'] = captures
        report['persistentCopiesVerifiedBeforeRemoval'] = True
        save()
        # The exact two paths are the only original paths this helper can delete.
        for capture in captures:
            source = Path(capture['source'])
            manifest = json.loads(Path(capture['manifest']).read_text())
            archive_module.check_unchanged(source, manifest['entries'])
            shutil.rmtree(source)
            assert not source.exists()
            report['mutations'].append(dict(action='exact-verified-original-directory-removal', path=str(source)))
            save()
        report['originalsRemoved'] = True
        for number in (1, 2):
            restored = PACKET / ('physical-restore-' + str(number))
            assert restored.parent == PACKET and not restored.is_symlink()
            shutil.rmtree(restored)
            report['mutations'].append(dict(action='own-generated-physical-restore-removal', path=str(restored)))
        report['status'] = 'PASS_LOSSLESS_STORAGE_REPLACEMENT'
    except BaseException as error:
        report.update(status='FAIL_RETAINED_PERSISTENT_ARCHIVES', error=repr(error))
        raise
    finally:
        report.update(elapsedSeconds=time.monotonic()-started,
                      completedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat(),
                      freeWorkspaceBytesAfter=shutil.disk_usage(ROOT).free,
                      freeTmpBytesAfter=shutil.disk_usage('/tmp').free)
        save()
        (OWNER / 'quiet18-archive-replacement.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
