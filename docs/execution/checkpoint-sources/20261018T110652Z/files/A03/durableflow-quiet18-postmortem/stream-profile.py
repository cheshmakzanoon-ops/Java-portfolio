import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import time

base = pathlib.Path(__file__).resolve().parent
os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})
source = base / 'ProfileCounters.java'
command = ['/workspace/.tools/jdk-25/bin/java', '-Xmx256m', '-XX:ActiveProcessorCount=1', str(source)]
for number in [1, 2]:
    workload = json.loads((base.parents[1] / 'A02/full-reference-20261018T095104-746011' / f'run-{number}/workload-10000.json').read_text())
    start = datetime.datetime.fromisoformat(workload['startedAt'])
    end = start + datetime.timedelta(seconds=workload['measurements']['elapsedSeconds'])
    command.extend([str(base / f'run-{number}/worker-1.jfr'), start.isoformat(), end.isoformat()])
receipt = {'command': command, 'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
           'startedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
           'maxSeconds': 45, 'maxOutputBytes': 65536, 'heapMiB': 256, 'activeProcessors': 1,
           'affinity': sorted(os.sched_getaffinity(0)), 'ordinaryBuildSlotHeld': os.getenv('PORTFOLIO_BUILD_SLOT_HELD')}
started = time.monotonic()
try:
    result = subprocess.run(command, capture_output=True, timeout=45)
    assert len(result.stdout) + len(result.stderr) < 65536
    for name, data in [('stdout', result.stdout), ('stderr', result.stderr)]:
        (base / f'stream-profile.{name}').write_bytes(data)
    receipt.update(exitCode=result.returncode, outputSha256=hashlib.sha256(result.stdout).hexdigest(),
                   stderrSha256=hashlib.sha256(result.stderr).hexdigest())
finally:
    receipt.update(elapsedSeconds=time.monotonic()-started,
                   finishedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    (base / 'stream-profile-command.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt))
assert receipt['exitCode'] == 0
