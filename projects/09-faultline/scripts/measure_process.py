#!/usr/bin/env python3
"""Measure one owned process with wait4; never use cumulative child rusage."""
import argparse
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time


def measure(command, output, timeout_seconds=140.0):
    """Return wait4 usage; terminate the owned group and reap the child on stop.

    The child owns a new session/process group. SIGINT/SIGTERM request cleanup
    rather than interrupting Popen between fork and PID ownership assignment.
    Linux ru_maxrss is KiB; macOS reports bytes. This helper requires wait4.
    """
    if not hasattr(os, "wait4") or sys.platform not in ("linux", "darwin"):
        raise RuntimeError("per-process wait4 measurement requires Linux or macOS")
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise ValueError("timeout must be positive")
    cancellation = {"signal": None}
    previous = {}
    child = None
    status = None
    usage = None
    timed_out = False
    started = time.monotonic()

    def request_cancel(signum, _frame):
        cancellation["signal"] = signum

    def terminate_group():
        if child is not None:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def reap():
        while True:
            try:
                return os.wait4(child.pid, 0)
            except InterruptedError:
                continue

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.getsignal(signum)
            signal.signal(signum, request_cancel)
        child = subprocess.Popen(command, stdout=output, stderr=output,
                                 start_new_session=True, close_fds=True)
        deadline = started + timeout_seconds
        while True:
            waited, candidate_status, candidate_usage = os.wait4(child.pid, os.WNOHANG)
            if waited == child.pid:
                status, usage = candidate_status, candidate_usage
                break
            now = time.monotonic()
            timed_out = now >= deadline
            if timed_out or cancellation["signal"] is not None:
                terminate_group()
                _, status, usage = reap()
                break
            time.sleep(min(0.01, max(0.0, deadline - now)))
    finally:
        if child is not None:
            # Also remove stray descendants if a command exits before they do.
            terminate_group()
            if status is None:
                _, status, usage = reap()
            child.returncode = os.waitstatus_to_exitcode(status)
        for signum, handler in previous.items():
            signal.signal(signum, handler)
    elapsed = time.monotonic() - started
    code = os.waitstatus_to_exitcode(status)
    return {"elapsedSeconds": elapsed,
            "maxRssKiB": usage.ru_maxrss / 1024 if sys.platform == "darwin" else usage.ru_maxrss,
            "userSeconds": usage.ru_utime, "systemSeconds": usage.ru_stime,
            "exitCode": code, "terminationSignal": -code if code < 0 else None,
            "timedOut": timed_out, "cancelled": cancellation["signal"] is not None,
            "cancellationSignal": cancellation["signal"], "pid": child.pid,
            "measurement": "per-child os.wait4 rusage; monotonic wall time"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=float, default=140.0)
    parser.add_argument("--stats", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a child command is required after --")
    with args.log.open("wb") as output:
        result = measure(command, output, args.timeout)
    args.stats.write_text(json.dumps(result, indent=2) + "\n")
    if result["cancelled"]:
        return 128 + result["cancellationSignal"]
    if result["timedOut"]:
        return 124
    return result["exitCode"] if result["exitCode"] >= 0 else 128 - result["exitCode"]


if __name__ == "__main__":
    sys.exit(main())
