"""Extract the packaged runtime graph once; verify every cached byte on every reuse."""
import hashlib
import json
import pathlib
import shutil
import sys
import tempfile
import zipfile

archive = pathlib.Path(sys.argv[1])
parent = pathlib.Path(sys.argv[2])
if not archive.is_file() or archive.is_symlink():
    raise SystemExit('Verified executable JAR is missing or symlinked')
archive_sha = hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest()
base = parent / 'classpath-v3'
directory = base / archive_sha
expected = {}
entries = {}
with zipfile.ZipFile(archive) as z:
    for item in z.infolist():
        name = pathlib.PurePosixPath(item.filename)
        parts = name.parts
        if item.filename in ('META-INF/BenchmarkList', 'META-INF/CompilerHints'):
            parts = ('BOOT-INF', 'classes', *parts)
        if len(parts) < 3 or parts[0] != 'BOOT-INF' or parts[1] not in ('classes', 'lib') or item.is_dir():
            continue
        if '..' in parts or name.is_absolute() or (item.external_attr >> 16) & 0o170000 == 0o120000:
            raise SystemExit('Unsafe executable JAR entry')
        relative = pathlib.Path(*parts[1:])
        if str(relative) in expected:
            raise SystemExit('Duplicate runtime JAR entry')
        expected[str(relative)] = hashlib.sha256(z.read(item)).hexdigest()
        entries[str(relative)] = item.filename
    if not expected or not any(p.startswith('lib/') for p in expected):
        raise SystemExit('Executable archive has no runtime dependency graph')
    if not directory.exists():
        base.mkdir(parents=True, exist_ok=True)
        temporary = pathlib.Path(tempfile.mkdtemp(prefix='.extract-', dir=base))
        try:
            for relative in expected:
                output = temporary / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(z.read(entries[relative]))
            (temporary / '.complete').write_text(json.dumps({'archiveSha256': archive_sha, 'files': expected}, sort_keys=True) + '\n')
            temporary.rename(directory)
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)

try:
    if directory.is_symlink() or any(p.is_symlink() for p in directory.rglob('*')):
        raise ValueError('Symlink in extracted runtime')
    actual_files = {str(p.relative_to(directory)) for p in directory.rglob('*') if p.is_file()}
    if actual_files != set(expected) | {'.complete'}:
        raise ValueError('Added or missing extracted runtime file')
    marker = json.loads((directory / '.complete').read_text())
    if marker != {'archiveSha256': archive_sha, 'files': expected}:
        raise ValueError('Modified runtime extraction manifest')
    for relative, digest in expected.items():
        with (directory / relative).open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
                raise ValueError('Modified extracted runtime file: ' + relative)
except (OSError, ValueError) as failure:
    raise SystemExit('Runtime cache rejected; preserve evidence and explicitly remove the owned damaged cache: ' + str(failure))
print(str(directory / 'classes') + ':' + str(directory / 'lib' / '*'))
