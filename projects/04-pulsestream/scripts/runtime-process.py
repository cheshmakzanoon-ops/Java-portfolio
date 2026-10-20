#!/usr/bin/env python3
"""Launch and stop the recorded JVM using exact artifact and kernel process identity."""
import argparse
import contextlib
import fcntl
import errno
import hashlib
import json
import math
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import time


class OwnershipError(RuntimeError):
    pass


class StartCancelled(Exception):
    def __init__(self, status):
        self.status = status


@contextlib.contextmanager
def deferred_cancellation():
    requested = [0]
    def remember(signum, frame):
        if not requested[0]:
            requested[0] = 128 + signum
    previous = {sig: signal.signal(sig, remember) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        yield requested
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def process_identity(pid):
    try:
        base = Path('/proc') / str(pid)
        before = (base / 'stat').read_text().rsplit(')', 1)[1].split()
        if before[0] in ('Z', 'X'):
            return None
        argv = [part.decode(errors='surrogateescape') for part in
                (base / 'cmdline').read_bytes().split(b'\0') if part]
        try:
            executable = os.readlink(base / 'exe')
        except PermissionError as restricted:
            if restricted.errno not in (errno.EACCES, errno.EPERM):
                raise
            # Managed procfs may restrict executable dereference after reparenting.
            executable = None
        after = (base / 'stat').read_text().rsplit(')', 1)[1].split()
        if after[0] in ('Z', 'X'):
            return None
        if before[19] != after[19]:
            raise OwnershipError('PROCESS_IDENTITY_CHANGED_DURING_INSPECTION')
        return {'start_ticks': int(after[19]), 'executable': executable, 'argv': argv}
    except (FileNotFoundError, ProcessLookupError):
        return None


def arguments_hash(argv):
    # Preserve exact argument identity without writing configuration/secrets to records.
    return hashlib.sha256(json.dumps(argv, ensure_ascii=True, separators=(',', ':')).encode()).hexdigest()


def boot_id():
    return Path('/proc/sys/kernel/random/boot_id').read_text().strip()


def read_record(path):
    try:
        record = json.loads(path.read_text())
    except FileNotFoundError:
        return None
    except (ValueError, OSError) as failure:
        raise OwnershipError('INVALID_PROCESS_RECORD: ' + str(path)) from failure
    if (not isinstance(record, dict) or type(record.get('pid')) is not int
            or record['pid'] <= 1 or type(record.get('start_ticks')) is not int
            or not all(isinstance(record.get(key), str) for key in
                       ('boot_id', 'artifact', 'artifact_sha256', 'executable', 'argv_sha256'))):
        raise OwnershipError('INVALID_PROCESS_RECORD: ' + str(path))
    return record


def owns(record, artifact):
    current = process_identity(record['pid'])
    if current is None:
        return False
    argv = current['argv']
    try:
        jar = str(Path(argv[argv.index('-jar') + 1]).resolve())
    except (ValueError, IndexError):
        jar = None
    if (record['boot_id'] != boot_id() or record['start_ticks'] != current['start_ticks']
            or (current['executable'] is not None and record['executable'] != current['executable'])
            or record['argv_sha256'] != arguments_hash(argv)
            or record['artifact'] != str(artifact.resolve()) or jar != record['artifact']):
        raise OwnershipError('PROCESS_IDENTITY_MISMATCH: refusing to signal PID ' + str(record['pid']))
    return True


@contextlib.contextmanager
def locked(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.with_suffix(path.suffix + '.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def remove_record(path, expected):
    if read_record(path) == expected:
        path.unlink(missing_ok=True)


def wait_stopped(record, artifact, descriptor, timeout):
    deadline = time.monotonic() + timeout
    poller = select.poll() if descriptor is not None else None
    if poller is not None:
        poller.register(descriptor, select.POLLIN)
    while True:
        if poller is not None:
            if poller.poll(0):
                return True
        else:
            try:
                if not owns(record, artifact):
                    return True
            except OwnershipError:
                return True  # The recorded process ended; a reused PID is never signaled.
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        if poller is not None:
            if poller.poll(max(1, min(100, int(remaining * 1000)))):
                return True
        else:
            time.sleep(min(0.05, remaining))


def send(record, artifact, descriptor, sig):
    try:
        if descriptor is not None and hasattr(signal, 'pidfd_send_signal'):
            signal.pidfd_send_signal(descriptor, sig)
        elif owns(record, artifact):
            os.kill(record['pid'], sig)
    except ProcessLookupError:
        pass


def stop(path, artifact, timeout, kill_timeout):
    record = read_record(path)
    if record is None:
        return
    if not owns(record, artifact):
        remove_record(path, record)
        return
    descriptor = None
    try:
        if hasattr(os, 'pidfd_open'):
            try:
                descriptor = os.pidfd_open(record['pid'])
            except ProcessLookupError:
                remove_record(path, record)
                return
        # Verify after opening: a descriptor obtained during PID reuse must not gain authority.
        if not owns(record, artifact):
            remove_record(path, record)
            return
        send(record, artifact, descriptor, signal.SIGTERM)
        if not wait_stopped(record, artifact, descriptor, timeout):
            send(record, artifact, descriptor, signal.SIGKILL)
            if not wait_stopped(record, artifact, descriptor, kill_timeout):
                raise OwnershipError('OWNED_PROCESS_DID_NOT_STOP')
        remove_record(path, record)
    finally:
        if descriptor is not None:
            os.close(descriptor)


def spawn(path, artifact, log, command, cancellation):
    prior = read_record(path)
    if prior is not None and owns(prior, artifact):
        raise OwnershipError('OWNED_PROCESS_ALREADY_RUNNING')
    if command and command[0] == '--':
        command = command[1:]
    if not command or '-jar' not in command:
        raise OwnershipError('EXACT_JAR_COMMAND_REQUIRED')
    index = command.index('-jar')
    if index + 1 >= len(command) or Path(command[index + 1]).resolve() != artifact.resolve():
        raise OwnershipError('EXACT_JAR_COMMAND_REQUIRED')
    artifact_hash = hashlib.sha256(artifact.read_bytes()).hexdigest()
    log.parent.mkdir(parents=True, exist_ok=True)
    child = None
    try:
        if cancellation[0]:
            raise StartCancelled(cancellation[0])
        with log.open('wb') as output:
            child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output,
                                     stderr=subprocess.STDOUT, start_new_session=True)
        deadline = time.monotonic() + 5
        while True:
            if cancellation[0]:
                raise StartCancelled(cancellation[0])
            identity = process_identity(child.pid)
            if child.poll() is not None:
                raise OwnershipError('PROCESS_EXITED_DURING_STARTUP')
            if identity is not None and identity['executable'] is not None and '-jar' in identity['argv']:
                record = {key: value for key, value in identity.items() if key != 'argv'}
                record.update(pid=child.pid, boot_id=boot_id(), artifact=str(artifact.resolve()),
                              artifact_sha256=artifact_hash, argv_sha256=arguments_hash(identity['argv']))
                try:
                    if owns(record, artifact):
                        break
                except OwnershipError:
                    pass  # Fresh unreaped child may still be completing its exec transition.
            if time.monotonic() >= deadline:
                raise OwnershipError('PROCESS_IDENTITY_NOT_READY')
            time.sleep(.01)
        temporary = path.with_suffix(path.suffix + '.tmp')
        with temporary.open('w') as output:
            json.dump(record, output, indent=2)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        temporary.replace(path)
    except BaseException:
        if child is not None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('spawn', 'alive', 'current', 'validate', 'stop'))
    parser.add_argument('record', type=Path)
    parser.add_argument('artifact', type=Path)
    parser.add_argument('--timeout', type=float, default=30)
    parser.add_argument('--kill-timeout', type=float, default=5)
    parser.add_argument('--log', type=Path)
    args, command = parser.parse_known_args()
    if any(not math.isfinite(value) or value < 0 for value in (args.timeout,args.kill_timeout)):
        parser.error('timeouts must be finite and nonnegative')
    with locked(args.record):
        if args.action == 'spawn':
            if args.log is None:
                parser.error('spawn requires --log')
            with deferred_cancellation() as cancellation:
                spawn(args.record, args.artifact, args.log, command, cancellation)
                if cancellation[0]:
                    stop(args.record, args.artifact, args.timeout, args.kill_timeout)
                    return cancellation[0]
        elif args.action == 'stop':
            with deferred_cancellation() as cancellation:
                stop(args.record, args.artifact, args.timeout, args.kill_timeout)
                return cancellation[0]
        else:
            record = read_record(args.record)
            alive = record is not None and owns(record, args.artifact)
            if args.action in ('alive','current') and not alive:
                return 1
            if args.action == 'current' and hashlib.sha256(args.artifact.read_bytes()).hexdigest() != record['artifact_sha256']:
                raise OwnershipError('STALE_PROCESS_ARTIFACT: stop the recorded process before reuse')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except StartCancelled as cancellation:
        sys.exit(cancellation.status)
    except (OwnershipError, OSError) as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(2)
