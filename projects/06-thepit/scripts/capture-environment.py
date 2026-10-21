#!/usr/bin/env python3
"""Record host facts without identifiers, credentials, or environment dumps."""
import datetime
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

run = Path(sys.argv[1])
project = Path(__file__).resolve().parent.parent
repo = project.parent.parent
cpu = next(line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name'))
quota, period = Path('/sys/fs/cgroup/cpu.max').read_text().split()
processes = subprocess.check_output(['ps', '-eo', 'stat=,comm=,args='], text=True).splitlines()
java_rows = [line.split(None, 2) for line in processes if len(line.split(None, 2)) == 3 and line.split(None, 2)[1] == 'java']
javas = [row[2] for row in java_rows if not row[0].startswith('Z')]
defunct_javas = sum(row[0].startswith('Z') for row in java_rows)
maven = sum('org.codehaus.plexus.classworlds.launcher.Launcher' in line for line in javas)
classification = os.environ.get('THEPIT_MEASUREMENT_CLASS', 'diagnostic-shared-host')
record = {
    'schemaVersion': 2,
    'capturedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'classification': classification,
    'sourceFingerprint': subprocess.check_output(['python3', str(project / 'scripts/source-fingerprint.py')], text=True).strip(),
    'mode': sys.argv[2], 'commands': int(sys.argv[3]), 'os': platform.system(),
    'kernel': platform.release(), 'architecture': platform.machine(), 'cpuModel': cpu,
    'visibleProcessors': int(subprocess.check_output(['nproc'], text=True)),
    'cpuQuotaCores': None if quota == 'max' else int(quota) / int(period),
    'memoryLimitBytes': Path('/sys/fs/cgroup/memory.max').read_text().strip(),
    'filesystemType': subprocess.check_output(['stat', '-f', '-c', '%T', str(run)], text=True).strip(),
    'physicalStorageDevice': 'unknown; guest overlay does not identify underlying stable storage',
    'jdk': subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr.strip().splitlines(),
    'mavenJvmsBeforeLaunch': maven, 'otherJavaJvmsBeforeLaunch': len(javas) - maven,
    'defunctJavaProcessesBeforeLaunch': defunct_javas,
    'isolationNote': os.environ.get('THEPIT_ISOLATION_NOTE', 'No quiet-baseline claim; preparation/diagnostic only'),
    'environmentValuesDumped': False,
}
(run / 'environment.json').write_text(json.dumps(record, indent=2) + '\n')
if classification == 'quiet-baseline' and javas:
    raise SystemExit('Refusing quiet baseline: another active Java process is still present; inspect recorded counts.')
