import hashlib
import json
import pathlib
import tarfile

ROOT = pathlib.Path('/workspace/Java-portfolio')
ARCHIVES = ROOT / '.work/A02/archive/quiet18/20261018T105252896249Z'
OUTPUT = pathlib.Path(__file__).resolve().parent
RECEIPT = json.loads((ROOT / '.work/coordinator/durableflow-quiet18-archive-recovery.json').read_text())
checks = []
for number, expected in enumerate(RECEIPT['checks'], 1):
    name = pathlib.Path(expected['archive']).name
    archive = ARCHIVES / name
    manifest_path = ARCHIVES / name.replace('.tar.gz', '-manifest.json')
    manifest_bytes = manifest_path.read_bytes()
    assert hashlib.sha256(manifest_bytes).hexdigest() == expected['manifestSha256']
    manifest = json.loads(manifest_bytes)
    digest = hashlib.sha256()
    with archive.open('rb') as stream:
        for block in iter(lambda: stream.read(1024**2), b''):
            digest.update(block)
    assert digest.hexdigest() == expected['archiveSha256']
    wanted = {path: metadata for path, metadata in manifest['entries'].items()
              if metadata['kind'] == 'file'
              and (metadata['relativePath'] == 'worker-1.jfr'
                   or metadata['relativePath'].startswith('gc/'))}
    assert len(wanted) == 14
    assert sum(item['bytes'] for item in wanted.values()) < 40 * 1024**2
    destination = OUTPUT / f'run-{number}'
    destination.mkdir(exist_ok=False)
    seen = set()
    copied = []
    with tarfile.open(archive, mode='r|gz') as stream:
        for member in stream:
            assert member.name in manifest['entries'], member.name
            metadata = manifest['entries'][member.name]
            assert member.size == metadata['bytes']
            assert member.isdir() if metadata['kind'] == 'directory' else member.isfile()
            assert not member.issym() and not member.islnk()
            assert member.name not in seen
            seen.add(member.name)
            if member.name not in wanted:
                continue
            relative = pathlib.PurePosixPath(metadata['relativePath'])
            assert not relative.is_absolute() and '..' not in relative.parts
            target = destination.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            copied_bytes = 0
            with stream.extractfile(member) as source, target.open('xb') as output:
                for block in iter(lambda: source.read(1024**2), b''):
                    copied_bytes += len(block)
                    assert copied_bytes <= metadata['bytes']
                    digest.update(block)
                    output.write(block)
            assert copied_bytes == metadata['bytes']
            assert digest.hexdigest() == metadata['sha256']
            copied.append({'path': str(target.relative_to(OUTPUT)), 'bytes': copied_bytes,
                           'sha256': digest.hexdigest()})
    assert seen == set(manifest['entries'])
    checks.append({'run': number, 'archiveSha256': expected['archiveSha256'],
                   'manifestSha256': expected['manifestSha256'], 'files': copied,
                   'allocationJsonRestored': False})
    print('Verified and extracted run', number, len(copied), 'JFR/GC files', flush=True)
(OUTPUT / 'extraction.json').write_text(json.dumps(checks, indent=2) + '\n')
