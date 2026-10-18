#!/usr/bin/env python3
"""Recover disk by hard-linking byte-identical immutable Maven cache artifacts.

Run only after the coordinator reserves all build slots and stops Java. Logical
paths and bytes remain unchanged; metadata and mutable Maven files are excluded.
"""
from pathlib import Path
import argparse, hashlib, json, os, stat, subprocess, time

root = Path('/workspace/Java-portfolio')
parser = argparse.ArgumentParser()
parser.add_argument('--apply', action='store_true')
parser.add_argument('--output', required=True)
args = parser.parse_args()
output = Path(args.output)
if output.exists():
    raise SystemExit('Refusing to replace an earlier maintenance record')
observed = subprocess.run(['ps', '-C', 'java', '-o', 'stat='], capture_output=True, text=True)
if observed.returncode not in (0, 1):
    raise SystemExit('Could not inspect Java processes')
active = [r for r in observed.stdout.splitlines() if not r.strip().startswith('Z')]
if active:
    raise SystemExit('Java must be stopped before cache maintenance')
if args.apply:
    ancestors = set()
    pid = os.getpid()
    while pid > 1:
        ancestors.add(pid)
        data = Path(f'/proc/{pid}/status').read_text()
        pid = int(next(line.split()[1] for line in data.splitlines() if line.startswith('PPid:')))
    held = set()
    for line in Path('/proc/locks').read_text().splitlines():
        fields = line.split()
        if len(fields) < 6 or fields[1:4] != ['FLOCK', 'ADVISORY', 'WRITE']:
            continue
        if int(fields[4]) not in ancestors:
            continue
        identity = fields[5].split(':')
        if len(identity) != 3:
            continue
        for index in range(3):
            lock = (root / f'.work/build-slots/{index}.lock').stat()
            if int(identity[0], 16) == os.major(lock.st_dev) and int(identity[1], 16) == os.minor(lock.st_dev) and int(identity[2]) == lock.st_ino:
                held.add(index)
    if held != {0, 1, 2}:
        raise SystemExit('Apply requires all three actual locks held by an ancestor')

def free():
    value = os.statvfs(root)
    return value.f_bavail * value.f_frsize

def identity(value):
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_mode

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

record = {'operation': 'hard-link only identical immutable .jar/.pom cache files', 'apply': args.apply, 'beforeFreeBytes': free(), 'changed': [], 'mismatched': [], 'bytesAvoided': 0}
canonical = {}
started = time.monotonic()
try:
    caches = sorted(p for p in (root / '.work').glob('*/m2') if p.is_dir() and not p.is_symlink())
    for cache in caches:
        for directory, dirs, names in os.walk(cache, followlinks=False):
            parent = Path(directory)
            dirs[:] = [name for name in sorted(dirs) if not (parent / name).is_symlink()]
            for name in sorted(names):
                path = parent / name
                if path.suffix not in {'.jar', '.pom'} or path.is_symlink():
                    continue
                relative = path.relative_to(cache)
                if any(part.endswith('-SNAPSHOT') for part in relative.parts):
                    continue
                if relative.parts[:4] == ('io', 'github', 'cheshmakzanoon', 'portfolio'):
                    continue
                before = path.stat()
                if not stat.S_ISREG(before.st_mode):
                    continue
                key = (relative.as_posix(), before.st_size, stat.S_IMODE(before.st_mode))
                if key not in canonical:
                    canonical[key] = [path, before, None]
                    continue
                original, original_stat, original_hash = canonical[key]
                if (before.st_dev, before.st_ino) == (original_stat.st_dev, original_stat.st_ino):
                    continue
                if original_hash is None:
                    original_hash = digest(original)
                    canonical[key][2] = original_hash
                candidate_hash = digest(path)
                if identity(original.stat()) != identity(original_stat) or identity(path.stat()) != identity(before):
                    raise RuntimeError('Cache changed during maintenance; stop and retry after draining builds')
                if candidate_hash != original_hash:
                    record['mismatched'].append(str(path.relative_to(root)))
                    continue
                row = {'path': str(path.relative_to(root)), 'canonical': str(original.relative_to(root)), 'bytes': before.st_size, 'sha256': candidate_hash}
                if args.apply:
                    temporary = path.with_name(name + f'.portfolio-link-{os.getpid()}')
                    try:
                        os.link(original, temporary)
                        if identity(path.stat()) != identity(before):
                            raise RuntimeError('Candidate changed before replacement')
                        os.replace(temporary, path)
                    finally:
                        temporary.unlink(missing_ok=True)
                    if path.stat().st_ino != original.stat().st_ino:
                        raise RuntimeError('Hard-link replacement did not retain canonical inode')
                record['changed'].append(row)
                if before.st_nlink == 1:
                    record['bytesAvoided'] += before.st_size
    record['status'] = 'PASS'
finally:
    record['afterFreeBytes'] = free()
    record['elapsedSeconds'] = time.monotonic() - started
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(record, indent=2) + '\n')
print(json.dumps({key: record[key] for key in ['status', 'beforeFreeBytes', 'afterFreeBytes', 'bytesAvoided', 'elapsedSeconds']}, indent=2))
