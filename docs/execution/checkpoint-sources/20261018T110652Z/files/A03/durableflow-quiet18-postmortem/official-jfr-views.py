import datetime
import hashlib
import json
import os
import pathlib
import selectors
import signal
import subprocess
import time

BASE = pathlib.Path(__file__).resolve().parent
os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})
records = []
began = time.monotonic()
def save():
    (BASE / 'official-parser-commands.json').write_text(json.dumps({
        'maxConcurrentParserJvms': 1, 'heapMiB': 256, 'processorCount': 1,
        'cpuAffinity': sorted(os.sched_getaffinity(0)), 'buildSlotHeld': os.getenv('PORTFOLIO_BUILD_SLOT_HELD'),
        'perCommandSeconds': 30, 'totalSeconds': 120, 'outputBytesPerCommand': 262144,
        'commands': records}, indent=2) + '\n')
for number in [1, 2]:
    for view in ['summary', 'hot-methods', 'allocation-by-site', 'latencies-by-type',
                 'socket-reads-by-host', 'thread-cpu-load', 'cpu-load',
                 'container-cpu-throttling', 'gc-cpu-time']:
        remaining = 120 - (time.monotonic() - began)
        if remaining <= 0:
            raise RuntimeError('Total parser deadline reached')
        recording = BASE / f'run-{number}/worker-1.jfr'
        args = ['summary', str(recording)] if view == 'summary' else [
            'view', '--width', '180', '--cell-height', '4', view, str(recording)]
        command = ['/workspace/.tools/jdk-25/bin/jfr', '-J-Xmx256m',
                   '-J-XX:ActiveProcessorCount=1', *args]
        row = {'run': number, 'view': view, 'command': command,
               'startedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
        records.append(row)
        save()
        child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        selector = selectors.DefaultSelector()
        handles = {}
        for key, stream in [('stdout', child.stdout), ('stderr', child.stderr)]:
            selector.register(stream, selectors.EVENT_READ, key)
            handles[key] = (BASE / f'run-{number}-{view}.{key}').open('xb')
        limit = min(30, remaining)
        start, size, failure = time.monotonic(), 0, None
        try:
            while selector.get_map():
                if time.monotonic() - start > limit:
                    failure = 'TIMEOUT'
                    break
                for event, unused in selector.select(0.1):
                    block = os.read(event.fileobj.fileno(), 16384)
                    if not block:
                        selector.unregister(event.fileobj)
                        continue
                    if size + len(block) > 262144:
                        failure = 'OUTPUT_LIMIT'
                        break
                    handles[event.data].write(block)
                    size += len(block)
                if failure:
                    break
            if failure:
                os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=max(0.1, limit - (time.monotonic() - start)))
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=2)
                failure = failure or 'TIMEOUT'
        finally:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=2)
            selector.close()
            for handle in handles.values():
                handle.close()
            row.update(exitCode=child.returncode, failure=failure, elapsedSeconds=time.monotonic()-start,
                       bytes=size, finishedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                       outputSha256={key: hashlib.sha256((BASE / f'run-{number}-{view}.{key}').read_bytes()).hexdigest()
                                     for key in handles})
            save()
        print(number, view, child.returncode, failure, flush=True)
        if failure or child.returncode != 0:
            raise RuntimeError('Official parser failed; no broad fallback')
