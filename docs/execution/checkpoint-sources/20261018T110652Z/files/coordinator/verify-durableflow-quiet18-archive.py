import datetime
import hashlib
import json
import os
import pathlib
import shutil
import stat
import tarfile
import tempfile

ROOT = pathlib.Path('/workspace/Java-portfolio')
PACKET = pathlib.Path('/tmp/durableflow-quiet18-preservation-6f1_cbot')
EXPECTED = [
    ('20261018T095149-10000-746947', '2890dc149177f59cfc6bd049b76b60db10c7e871b56f1e30baf03c5864a2240d', '277f027d6868ad148b0f85d95996b080cddc581c46748aecbe78d4da7911c028'),
    ('20261018T101046-10000-760867', '37a99f19534748febc7efbc56e9174fe67dd6f0a8827f251fc89efde75468b13', 'fb1e2a33ba455b6558ff1369f37a8e0d85476f0567c5102f2f6e9e8fd54fc25d'),
]

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()

checks = []
for name, archive_hash, manifest_hash in EXPECTED:
    archive = PACKET / (name + '.tar.gz')
    manifest_path = PACKET / (name + '-manifest.json')
    assert digest(archive) == archive_hash and digest(manifest_path) == manifest_hash
    manifest = json.loads(manifest_path.read_text())
    entries = manifest['entries']
    assert len(entries) == 24
    original = ROOT / 'projects/02-durableflow/target/performance' / name
    assert pathlib.Path(manifest['source']) == original
    prior = manifest['reviewedReceipt']
    assert digest(pathlib.Path(prior['path'])) == prior['sha256']
    for member_name in entries:
        path = pathlib.PurePosixPath(member_name)
        assert not path.is_absolute() and '..' not in path.parts and path.parts[0] == name
    destination = pathlib.Path(tempfile.mkdtemp(prefix='root-durableflow-archive-check-', dir='/tmp'))
    seen = set()
    with tarfile.open(archive, 'r|gz') as stream:
        for member in stream:
            assert member.name in entries and member.name not in seen
            seen.add(member.name)
            expected = entries[member.name]
            assert stat.S_IMODE(member.mode) == expected['mode']
            target = destination / member.name
            if expected['kind'] == 'directory':
                assert member.isdir()
                target.mkdir(parents=True, exist_ok=True)
            else:
                assert member.isfile() and member.size == expected['bytes']
                target.parent.mkdir(parents=True, exist_ok=True)
                value = hashlib.sha256()
                with stream.extractfile(member) as source, target.open('xb') as output:
                    for block in iter(lambda: source.read(1024 * 1024), b''):
                        value.update(block)
                        output.write(block)
                    output.flush()
                    os.fsync(output.fileno())
                assert value.hexdigest() == expected['sha256']
                target.chmod(expected['mode'])
                os.utime(target, ns=(expected['mtimeNs'], expected['mtimeNs']))
    assert seen == set(entries)
    for member_name, entry in sorted(entries.items(), key=lambda row: len(row[0]), reverse=True):
        if entry['kind'] == 'directory':
            target = destination / member_name
            target.chmod(entry['mode'])
            os.utime(target, ns=(entry['mtimeNs'], entry['mtimeNs']))
    restored = {str(path.relative_to(destination)) for path in destination.rglob('*')}
    original_set = {name, *(str(path.relative_to(original.parent)) for path in original.rglob('*'))}
    assert restored == original_set == set(entries)
    for member_name, entry in entries.items():
        for base in (destination, original.parent):
            path = base / member_name
            info = path.lstat()
            assert stat.S_IMODE(info.st_mode) == entry['mode']
            assert info.st_mtime_ns == entry['mtimeNs']
            if entry['kind'] == 'directory':
                assert stat.S_ISDIR(info.st_mode)
            else:
                assert stat.S_ISREG(info.st_mode) and info.st_size == entry['bytes']
                assert digest(path) == entry['sha256']
    assert digest(archive) == archive_hash
    checks.append(dict(source=str(original), archive=str(archive), archiveSha256=archive_hash,
                       manifestSha256=manifest_hash, files=manifest['originalFiles'],
                       bytesRestoredAndCompared=manifest['originalBytes'],
                       completeSetsModesMtimesBytesHashesMatch=True, originalsRetained=True))
    shutil.rmtree(destination)
receipt = dict(status='PASS_INDEPENDENT_PHYSICAL_RESTORE_ORIGINALS_RETAINED',
               completedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
               checks=checks, files=sum(row['files'] for row in checks),
               bytesRestoredAndCompared=sum(row['bytesRestoredAndCompared'] for row in checks))
(ROOT / '.work/coordinator/durableflow-quiet18-archive-recovery.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt, indent=2), flush=True)
