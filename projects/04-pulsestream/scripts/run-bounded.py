#!/usr/bin/env python3
"""Bound one owned child process group, preserving its actual exit code."""
import os
import signal
import subprocess
import sys
seconds = int(sys.argv[1])
process = subprocess.Popen(sys.argv[2:], start_new_session=True)
try:
    code = process.wait(timeout=seconds)
except subprocess.TimeoutExpired:
    print(f'{os.path.basename(sys.argv[2])} exceeded {seconds}s; stopping owned process group', file=sys.stderr)
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    code = 124
except KeyboardInterrupt:
    os.killpg(process.pid, signal.SIGTERM)
    process.wait()
    code = 130
sys.exit(code if code >= 0 else 128-code)
