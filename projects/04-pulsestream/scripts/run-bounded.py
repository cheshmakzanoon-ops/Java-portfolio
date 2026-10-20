#!/usr/bin/env python3
"""Bound one owned process group and preserve the command, deadline, or cancellation outcome."""
import os
import signal
import subprocess
import sys
import time

if len(sys.argv) < 3:
    raise SystemExit("usage: run-bounded.py seconds command [arguments...]")
try:
    seconds = int(sys.argv[1])
except ValueError:
    raise SystemExit("seconds must be a positive integer")
if seconds < 1:
    raise SystemExit("seconds must be a positive integer")
requested_status = 0


def request_cancellation(signum, frame):
    global requested_status
    if not requested_status:
        requested_status = 128 + signum


# Defer cancellation across Popen's ownership-publication boundary.
for controlled_signal in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
    signal.signal(controlled_signal, request_cancellation)
child = subprocess.Popen(sys.argv[2:], start_new_session=True)
deadline = time.monotonic() + seconds


def observed_exit():
    # Keep the leader unreaped: its PID/PGID cannot be reused before the last signal.
    event = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    if event is None:
        return None
    return event.si_status if event.si_code == os.CLD_EXITED else 128 + event.si_status


try:
    while True:
        if requested_status:
            outcome = requested_status
            break
        result = observed_exit()
        if result is not None:
            outcome = requested_status or result
            break
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            print(f"Command exceeded {seconds}s deadline", file=sys.stderr)
            outcome = 124
            break
        time.sleep(min(.02, remaining))
finally:
    # Descendants can outlive a successfully exiting group leader.
    for controlled_signal in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(controlled_signal, signal.SIG_IGN)
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    cleanup_deadline = time.monotonic() + 5
    while observed_exit() is None and time.monotonic() < cleanup_deadline:
        time.sleep(.02)
    try:
        os.killpg(child.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    child.wait(timeout=5)
raise SystemExit(outcome)
