"""Bound a controlled child command and its process group; propagate its actual exit status."""
import os
import signal
import subprocess
import sys

if len(sys.argv) < 3:
    raise SystemExit("usage: run-bounded.py seconds command [arguments...]")
try:
    seconds = int(sys.argv[1])
except ValueError:
    raise SystemExit("seconds must be a positive integer")
if seconds < 1:
    raise SystemExit("seconds must be a positive integer")
child = subprocess.Popen(sys.argv[2:], start_new_session=True)


def stop_owned_group():
    # Once cleanup owns the outcome, repeated terminal signals must not interrupt it.
    for controlled_signal in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(controlled_signal, signal.SIG_IGN)
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


for controlled_signal in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
    signal.signal(controlled_signal, terminated)
try:
    outcome = child.wait(timeout=seconds)
except subprocess.TimeoutExpired:
    print(f"Command exceeded {seconds}s deadline", file=sys.stderr)
    raise SystemExit(124)
finally:
    stop_owned_group()
raise SystemExit(outcome if outcome >= 0 else 128 - outcome)
