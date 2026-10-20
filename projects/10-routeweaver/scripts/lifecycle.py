#!/usr/bin/env python3
"""Linux process ownership for the project launcher; PID alone never authorizes a signal."""
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request


class OwnershipError(RuntimeError):
    pass


def process(pid):
    try:
        stat = Path(f'/proc/{pid}/stat').read_text()
        tail = stat[stat.rfind(')') + 2:].split()
        if tail[0] == 'Z':
            return None
        return {'startTicks': int(tail[19]),
                'arguments': Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')[:-1],
                'executable': str(Path(f'/proc/{pid}/exe').resolve(strict=True))}
    except FileNotFoundError:
        return None


def owned(runtime, jar, executable):
    pid_file = runtime / 'service.pid'
    metadata = runtime / 'service-process.json'
    if not pid_file.exists():
        if metadata.exists():
            raise OwnershipError('process metadata exists without its PID file; inspect it before repair')
        return None
    try:
        pid = int(pid_file.read_text().strip())
        if pid <= 1:
            raise ValueError('invalid PID')
        observed = process(pid)
        if observed is None:
            return None
        expected = json.loads(metadata.read_text())
        command = observed['arguments']
        match = expected.get('pid') == pid and expected.get('startTicks') == observed['startTicks']
        match = match and observed['executable'] == str(executable.resolve())
        match = match and expected.get('jar') == str(jar.resolve())
        match = match and any(command[i:i + 2] == [b'-jar', os.fsencode(str(jar))]
                              for i in range(len(command) - 1))
        if not match:
            raise OwnershipError('PID identity does not match the launcher record; refusing to signal it')
        return pid
    except (ValueError, OSError, TypeError) as failure:
        raise OwnershipError('live PID ownership cannot be established; inspect its record') from failure


def clear_records(runtime):
    for name in ('service.pid', 'service-process.json', 'running-source'):
        (runtime / name).unlink(missing_ok=True)


def signal_owned(runtime, jar, executable, pid, sig):
    # Pin kernel process identity before rechecking the record; never signal a recycled PID.
    try:
        descriptor = os.pidfd_open(pid)
    except ProcessLookupError:
        return
    try:
        if owned(runtime, jar, executable) != pid:
            raise OwnershipError('owned process identity changed before signaling')
        try:
            signal.pidfd_send_signal(descriptor, sig)
        except ProcessLookupError:
            pass
    finally:
        os.close(descriptor)


def stop(runtime, jar, executable, timeout=20):
    pid = owned(runtime, jar, executable)
    if pid is None:
        clear_records(runtime)
        return
    signal_owned(runtime, jar, executable, pid, signal.SIGTERM)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if owned(runtime, jar, executable) is None:
            clear_records(runtime)
            return
        time.sleep(0.1)
    # Recheck start time, executable and exact JAR before escalating the signal.
    if owned(runtime, jar, executable) == pid:
        signal_owned(runtime, jar, executable, pid, signal.SIGKILL)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if owned(runtime, jar, executable) is None:
            clear_records(runtime)
            return
        time.sleep(0.1)
    raise OwnershipError('owned process did not exit after bounded termination')


def listener_belongs_to(pid, port):
    sockets = set()
    for descriptor in Path(f'/proc/{pid}/fd').iterdir():
        try:
            link = os.readlink(descriptor)
            if link.startswith('socket:['):
                sockets.add(link[8:-1])
        except FileNotFoundError:
            continue
    for table in ('tcp', 'tcp6'):
        for line in Path(f'/proc/net/{table}').read_text().splitlines()[1:]:
            columns = line.split()
            if columns[3] == '0A' and int(columns[1].split(':')[1], 16) == port:
                if columns[9] in sockets:
                    return True
    return False


def free_port(port):
    try:
        with socket.socket() as listener:
            # A stopped HTTP server can leave TIME_WAIT connections. Reuse does not permit
            # binding over a live listening socket, but allows a legitimate immediate restart.
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', port))
    except OSError as failure:
        raise OwnershipError(f'port {port} is already occupied; refusing an unknown listener') from failure


def start(runtime, jar, executable, source, port):
    if owned(runtime, jar, executable) is not None:
        raise OwnershipError('stop the existing owned process before starting another')
    clear_records(runtime)
    free_port(port)
    with (runtime / 'service.log').open('ab') as log:
        child = subprocess.Popen(
            [str(executable), '-Xmx768m',
             f'-Xlog:gc:file={runtime / "service-gc.log"}:time,uptime,level,tags', '-jar', str(jar)],
            stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        observed = process(child.pid)
        if observed is None:
            child.wait(timeout=5)
            raise OwnershipError('service exited during startup; inspect service.log')
        metadata = {'pid': child.pid, 'startTicks': observed['startTicks'], 'jar': str(jar.resolve()),
                    'source': source}
        (runtime / 'service-process.json').write_text(json.dumps(metadata) + '\n')
        (runtime / 'service.pid').write_text(str(child.pid) + '\n')
        (runtime / 'running-source').write_text(source + '\n')
    except BaseException:
        # Popen still owns this unreaped child identity; do not leave a JVM after record failure.
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        clear_records(runtime)
        raise


def health(runtime, jar, executable, port):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        pid = owned(runtime, jar, executable)
        if pid is None:
            raise OwnershipError('owned service exited before readiness; inspect service.log')
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(
                    f'http://127.0.0.1:{port}/health', timeout=2) as response:
                payload = json.load(response)
            if isinstance(payload, dict) and payload.get('product') == 'RouteWeaver' and payload.get('status') == 'UP':
                if listener_belongs_to(pid, port):
                    return
                raise OwnershipError('health response belongs to a different process')
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.25)
    raise OwnershipError('owned service readiness exceeded 60 seconds; inspect service.log')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('status', 'free-port', 'start', 'stop', 'health'))
    parser.add_argument('--source', default='')
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    runtime = project / '.runtime'
    jar = project / 'routeweaver-service/target/routeweaver-service-1.0.0-SNAPSHOT-exec.jar'
    executable = Path(os.environ['JAVA_HOME']) / 'bin/java'
    port = int(os.environ.get('ROUTEWEAVER_PORT', '19000'))
    if not 1 <= port <= 65535:
        raise SystemExit('ROUTEWEAVER_PORT must be in [1,65535]')
    runtime.mkdir(parents=True, exist_ok=True)
    try:
        if args.operation == 'status':
            raise SystemExit(0 if owned(runtime, jar, executable) else 1)
        if args.operation == 'free-port':
            free_port(port)
        if args.operation == 'start':
            start(runtime, jar, executable, args.source, port)
        if args.operation == 'stop':
            stop(runtime, jar, executable)
        if args.operation == 'health':
            health(runtime, jar, executable, port)
    except (OwnershipError, OSError, ValueError) as failure:
        print(str(failure), file=sys.stderr)
        raise SystemExit(2) from failure


if __name__ == '__main__':
    main()
