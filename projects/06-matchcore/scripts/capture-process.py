#!/usr/bin/env python3
"""Capture exact child status and POSIX resource usage without an external time executable."""
import json
from pathlib import Path
import resource
import subprocess
import sys
import time

run = Path(sys.argv[1])
started = time.monotonic_ns()
with (run / 'stdout.txt').open('w') as out, (run / 'stderr.txt').open('w') as err:
    try:
        completed = subprocess.run(sys.argv[2:], stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        status = completed.returncode if completed.returncode >= 0 else 128 - completed.returncode
    except OSError as failure:
        err.write(str(failure) + '\n')
        status = 127
usage = resource.getrusage(resource.RUSAGE_CHILDREN)
result = {
    'schemaVersion': 1, 'boundary': 'Whole Java process and waited-for descendants, including startup/verification/recovery',
    'exitCode': status, 'wallNanos': time.monotonic_ns() - started,
    'userCpuSeconds': usage.ru_utime, 'systemCpuSeconds': usage.ru_stime,
    'maximumResidentSetBytes': usage.ru_maxrss * (1024 if sys.platform.startswith('linux') else 1),
    'minorPageFaults': usage.ru_minflt, 'majorPageFaults': usage.ru_majflt,
    'voluntaryContextSwitches': usage.ru_nvcsw, 'involuntaryContextSwitches': usage.ru_nivcsw,
    'filesystemInputBlocks': usage.ru_inblock, 'filesystemOutputBlocks': usage.ru_oublock,
}
(run / 'resource.json').write_text(json.dumps(result, indent=2) + '\n')
raise SystemExit(status)
