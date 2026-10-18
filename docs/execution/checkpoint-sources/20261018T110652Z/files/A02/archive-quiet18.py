#!/usr/bin/env python3
"""Lossless archive and physical restore of two CLOSED captures; NEVER deletes originals."""
from __future__ import annotations

import datetime as dt
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tarfile
import tempfile
import time

ROOT = Path('/workspace/Java-portfolio')
OWNER = ROOT / '.work/A02'
ATTEMPT = OWNER / 'full-reference-20261018T095104-746011'
SOURCES = [ROOT / 'projects/02-durableflow/target/performance' / name for name in
           ('20261018T095149-10000-746947', '20261018T101046-10000-760867')]
BLOCK = 1024 * 1024
FLOOR = 2 * 1024 ** 3


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def emit(message):
    print(json.dumps({'time': utc(), 'message': message}), flush=True)


def write_json(path, data):
    with path.open('x') as output:
        json.dump(data, output, indent=2, sort_keys=True)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())


def reserve(required=FLOOR):
    free = shutil.disk_usage('/tmp').free
    if free < required:
        raise OSError(f'/tmp preservation reserve refused: {free} < {required}')
    return free


def digest_stream(stream, output=None):
    total, h, next_check = 0, hashlib.sha256(), 0
    while True:
        block = stream.read(BLOCK)
        if not block:
            return total, h.hexdigest()
        total += len(block)
        h.update(block)
        if output is not None:
            output.write(block)
        if time.monotonic() >= next_check:
            reserve()
            next_check = time.monotonic() + 2


def digest(path):
    with path.open('rb') as source:
        return digest_stream(source)


def inventory(source):
    entries = {}
    for path in [source, *sorted(source.rglob('*'))]:
        info = path.lstat()
        if stat.S_ISREG(info.st_mode):
            kind = 'file'
        elif stat.S_ISDIR(info.st_mode):
            kind = 'directory'
        else:
            raise RuntimeError('Unsupported raw entry, including links: ' + str(path))
        relative = path.relative_to(source).as_posix()
        name = source.name if relative == '.' else source.name + '/' + relative
        entries[name] = dict(relativePath=relative, kind=kind,
                             bytes=info.st_size if kind == 'file' else 0,
                             mode=stat.S_IMODE(info.st_mode), mtimeNs=info.st_mtime_ns,
                             ctimeNs=info.st_ctime_ns, inode=info.st_ino,
                             device=info.st_dev, uid=info.st_uid, gid=info.st_gid)
    return entries


def check_unchanged(source, entries):
    metadata = {name: {key: value for key, value in row.items() if key != 'sha256'}
                for name, row in entries.items()}
    if inventory(source) != metadata:
        raise RuntimeError('Closed original metadata changed: ' + str(source))


def archive_one(source, number, packet):
    emit('Inventory and binary SHA256 of closed capture ' + str(number))
    entries = inventory(source)
    for row in entries.values():
        if row['kind'] == 'file':
            size, value = digest(source / row['relativePath'])
            if size != row['bytes']:
                raise RuntimeError('Original size changed')
            row['sha256'] = value
    check_unchanged(source, entries)
    receipt = ATTEMPT / ('run-' + str(number)) / 'raw-artifacts.json'
    reviewed = json.loads(receipt.read_text())
    indexed = {str((ROOT / row['path']).relative_to(source)): row
               for row in reviewed['files'] if (ROOT / row['path']).is_relative_to(source)}
    files = {row['relativePath']: row for row in entries.values() if row['kind'] == 'file'}
    if set(files) != set(indexed):
        raise RuntimeError('Complete file set differs from prior run receipt')
    for name, row in files.items():
        if (row['bytes'], row['sha256']) != (indexed[name]['bytes'], indexed[name]['sha256']):
            raise RuntimeError('Raw bytes differ from prior run receipt: ' + name)
    archive = packet / (source.name + '.tar.gz')
    manifest = packet / (source.name + '-manifest.json')
    write_json(manifest, dict(schemaVersion=1, source=str(source), archiveRoot=source.name,
                             archive=str(archive), entries=entries,
                             originalFiles=len(files), originalBytes=sum(row['bytes'] for row in files.values()),
                             reviewedReceipt=dict(path=str(receipt), bytes=digest(receipt)[0], sha256=digest(receipt)[1])))
    emit('Streaming gzip level1 capture ' + str(number) + '; every file/directory included')
    with archive.open('xb') as raw:
        with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0, compresslevel=1) as compressed:
            with tarfile.open(fileobj=compressed, mode='w|', format=tarfile.PAX_FORMAT) as tar:
                for name, row in entries.items():
                    reserve()
                    path = source / row['relativePath']
                    member = tar.gettarinfo(str(path), arcname=name)
                    member.mode = stat.S_IMODE(member.mode)
                    if member.mode != row['mode']:
                        raise RuntimeError('Mode changed while archiving')
                    if row['kind'] == 'file':
                        if not member.isfile() or member.size != row['bytes']:
                            raise RuntimeError('Type/size changed while archiving')
                        with path.open('rb') as stream:
                            tar.addfile(member, stream)
                    else:
                        if not member.isdir():
                            raise RuntimeError('Directory changed while archiving')
                        tar.addfile(member)
        raw.flush()
        os.fsync(raw.fileno())
    restore = packet / ('physical-restore-' + str(number))
    restore.mkdir(mode=0o700)
    reserve(sum(row['bytes'] for row in files.values()) + FLOOR)
    emit('Actual physical restore and independent stream validation for capture ' + str(number))
    seen = set()
    with gzip.open(archive, 'rb') as compressed:
        with tarfile.open(fileobj=compressed, mode='r|') as tar:
            for member in tar:
                name = member.name.rstrip('/')
                safe = PurePosixPath(name)
                if safe.is_absolute() or '..' in safe.parts or safe.parts[0] != source.name:
                    raise RuntimeError('Unsafe archive member')
                if name not in entries or name in seen:
                    raise RuntimeError('Unexpected/duplicate archive entry')
                seen.add(name)
                row = entries[name]
                if member.mode != row['mode']:
                    raise RuntimeError('Archive mode mismatch')
                target = restore / name
                if row['kind'] == 'directory':
                    if not member.isdir():
                        raise RuntimeError('Archive directory type mismatch')
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    if not member.isfile() or member.size != row['bytes']:
                        raise RuntimeError('Archive file type/size mismatch')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with tar.extractfile(member) as stream, target.open('xb') as output:
                        size, value = digest_stream(stream, output)
                        output.flush()
                        os.fsync(output.fileno())
                    if (size, value) != (row['bytes'], row['sha256']):
                        raise RuntimeError('Physical restored bytes/hash mismatch')
                    target.chmod(row['mode'])
                    os.utime(target, ns=(row['mtimeNs'], row['mtimeNs']))
        while compressed.read(BLOCK):
            pass  # Force complete gzip trailer/CRC validation.
    if seen != set(entries):
        raise RuntimeError('Archive complete file/directory set mismatch')
    for name, row in sorted(entries.items(), reverse=True):
        if row['kind'] == 'directory':
            target = restore / name
            target.chmod(row['mode'])
            os.utime(target, ns=(row['mtimeNs'], row['mtimeNs']))
    restored = inventory(restore / source.name)
    if set(restored) != set(entries):
        raise RuntimeError('Physical restored directory/file set mismatch')
    for name, row in entries.items():
        actual = restored[name]
        if any(actual[key] != row[key] for key in ('kind', 'bytes', 'mode', 'mtimeNs')):
            raise RuntimeError('Physical restored metadata mismatch: ' + name)
        if row['kind'] == 'file' and digest(restore / name) != (row['bytes'], row['sha256']):
            raise RuntimeError('Independent disk re-read hash mismatch: ' + name)
    emit('Rehashing unchanged original capture ' + str(number))
    check_unchanged(source, entries)
    for row in files.values():
        if digest(source / row['relativePath']) != (row['bytes'], row['sha256']):
            raise RuntimeError('Original changed after archive/restore')
    check_unchanged(source, entries)
    result = dict(source=str(source), archive=str(archive), manifest=str(manifest),
                  archiveBytes=archive.stat().st_size, archiveSha256=digest(archive)[1],
                  manifestSha256=digest(manifest)[1], physicalRestoreRoot=str(restore),
                  files=len(files), directories=len(entries)-len(files),
                  originalBytes=sum(row['bytes'] for row in files.values()),
                  reviewedOriginalReceiptMatched=True, completeArchiveAndRestoreSetsMatched=True,
                  physicalRestoredBytesModesMtimesHashesMatched=True, unchangedOriginalsRehashed=True,
                  originalsRemoved=False)
    emit('PASS capture ' + str(number) + '; original and restored trees both retained')
    return result


def main():
    started = time.monotonic()
    terminal = json.loads((ATTEMPT / 'envelope.json').read_text())
    if terminal.get('exitCode') != 1 or terminal.get('assertions', {}).get('successfulFullRuns') != 2:
        raise RuntimeError('Expected closed failed quiet18 attempt with exactly two complete runs')
    required = sum(row['bytes'] for source in SOURCES for row in inventory(source).values()) + FLOOR
    reserve(required)
    packet = Path(tempfile.mkdtemp(prefix='durableflow-quiet18-preservation-', dir='/tmp'))
    packet.chmod(0o700)
    os.nice(10)
    report = dict(schemaVersion=1, status='RUNNING', startedAtUtc=utc(), packet=str(packet),
                  sourceAttempt=str(ATTEMPT), sourceAttemptExitCode=1,
                  classification='Lossless failed-attempt preservation; no performance acceptance',
                  helperSha256=digest(Path(__file__))[1], originalsRemoved=False,
                  tmpFloorBytes=FLOOR, tmpFreeBytesBefore=shutil.disk_usage('/tmp').free, captures=[])
    try:
        for number, source in enumerate(SOURCES, 1):
            report['captures'].append(archive_one(source, number, packet))
        report['status'] = 'PASS_PRESERVATION_ORIGINALS_RETAINED'
    except BaseException as error:
        report.update(status='FAIL_ORIGINALS_RETAINED', error=repr(error))
        raise
    finally:
        report.update(completedAtUtc=utc(), elapsedSeconds=time.monotonic()-started,
                      tmpFreeBytesAfter=shutil.disk_usage('/tmp').free,
                      workspaceFreeBytesAfter=shutil.disk_usage(ROOT).free)
        write_json(packet / 'verification.json', report)
        write_json(OWNER / ('quiet18-preservation-' + packet.name + '.json'), report)
        (packet / 'RESTORE.md').write_text(
            '# Original-path recovery\n\nThis packet preserves the closed FAILED quiet18 attempt. '
            'It does not complete a three-run reference study. Original directories were NOT removed.\n\n'
            'After any separately authorized original removal, choose a fresh empty directory, '
            'extract the selected tar.gz with `tar --extract --gzip --file ARCHIVE --directory RESTORE '
            '--no-same-owner --same-permissions --keep-old-files`, and verify every file/directory '
            'against its manifest including binary SHA-256, size and mode before placing the root '
            'directory back at the exact manifest source path. Never overwrite an existing raw tree. '
            'The physical-restore-1/2 directories here already provide independently re-read copies.\n')
        emit('Verification packet: ' + str(packet))
    return 0 if report['status'].startswith('PASS') else 1


if __name__ == '__main__':
    raise SystemExit(main())
