import hashlib
import json
import pathlib
import shutil
import stat
import subprocess
import tempfile
import datetime

root = pathlib.Path('/workspace/Java-portfolio')
packet = root / '.work/A06/archive/reference.2lkNWp.0n0jw_63'
manifest = json.loads((packet / 'manifest.json').read_text())
archive = packet / 'reference.2lkNWp.tar.gz'

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(data)
    return h.hexdigest()

assert digest(archive) == '4a12347b29bad6e7ffe4ec149ab1c783bc9dbebde23a70fca0025bceded4f361'
assert digest(packet / 'manifest.json') == '618917cde75957e9575246ba9b18492f453dc4e75b409c801f4bfcd3b73ee9c0'
for name in manifest['entries']:
    rel = pathlib.PurePosixPath(name)
    assert not rel.is_absolute() and '..' not in rel.parts

destination = pathlib.Path(tempfile.mkdtemp(prefix='archive-recovery-', dir=root / '.work/coordinator'))
subprocess.run(['tar', '--extract', '--gzip', '--file', str(archive), '--directory', str(destination), '--no-same-owner', '--same-permissions', '--keep-old-files'], check=True, timeout=120)
original = root / 'projects/06-matchcore/.runtime'
expected = set(manifest['entries'])
restored = {p.relative_to(destination).as_posix() for p in destination.rglob('*')}
assert restored == expected, (restored - expected, expected - restored)
for name, entry in manifest['entries'].items():
    for base in (destination, original):
        path = base / name
        info = path.lstat()
        assert stat.S_IMODE(info.st_mode) == entry['mode'], name
        if entry['kind'] == 'directory':
            assert stat.S_ISDIR(info.st_mode), name
        else:
            assert stat.S_ISREG(info.st_mode) and info.st_size == entry['bytes'], name
            assert digest(path) == entry['sha256'], name
original_set = {p.relative_to(original).as_posix() for p in (original / manifest['archiveRoot']).rglob('*')}
original_set.add(manifest['archiveRoot'])
assert original_set == expected
receipt = {'status': 'PASS', 'checkedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'archiveSha256': digest(archive), 'manifestSha256': digest(packet / 'manifest.json'), 'files': manifest['allRegularFileCount'], 'bytesRestoredAndCompared': manifest['allRegularBytes'], 'directories': len(expected) - manifest['allRegularFileCount'], 'validation': 'Independent physical extraction into a fresh directory; complete restored and original sets, regular-file bytes, SHA256 and permission modes match the manifest. Original files retained.', 'temporaryRestore': str(destination)}
(root / '.work/coordinator/matchcore-archive-recovery.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt, indent=2), flush=True)
# Only remove the new verified recovery copy. The original batch is untouched.
shutil.rmtree(destination)
