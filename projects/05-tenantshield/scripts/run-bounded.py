"""Bound a controlled child command and its process group; propagate its actual exit status."""
import os
import signal
import subprocess
import sys

seconds = int(sys.argv[1])
if seconds < 1 or len(sys.argv) < 3:
    raise SystemExit("usage: run-bounded.py seconds command [arguments...]")
child = subprocess.Popen(sys.argv[2:], start_new_session=True)


def stop_owned_group():
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        child.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    # A child may exit before its own forks; clean up the entire group we created.
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    child.wait(timeout=5)


def terminated(signum, frame):
    raise SystemExit(128 + signum)


signal.signal(signal.SIGTERM, terminated)
try:
    outcome = child.wait(timeout=seconds)
except subprocess.TimeoutExpired:
    print(f"Command exceeded {seconds}s deadline", file=sys.stderr)
    stop_owned_group()
    raise SystemExit(124)
except BaseException:
    stop_owned_group()
    raise
raise SystemExit(outcome)
