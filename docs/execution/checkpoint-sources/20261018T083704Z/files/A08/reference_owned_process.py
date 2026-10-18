"""Linux owned-session runner. No process launches or global changes at import time."""
from dataclasses import dataclass
from pathlib import Path
import ctypes
import os
import signal
import subprocess
import time


@dataclass(frozen=True)
class Identity:
    pid: int
    start: int
    parent: int
    session: int
    state: str


def identity(pid):
    try:
        fields = (Path('/proc') / str(pid) / 'stat').read_text().rpartition(') ')[2].split()
        return Identity(pid, int(fields[19]), int(fields[1]), int(fields[3]), fields[0])
    except (OSError, ValueError, IndexError):
        return None


class Cancellation:
    def __init__(self):
        self.signal_number = 0
        self.previous = {}

    def install(self):
        for number in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            self.previous[number] = signal.signal(number, self._received)

    def _received(self, number, frame):
        # Avoid raising in Popen's fork/registration gap or while writing the manifest.
        if not self.signal_number:
            self.signal_number = number

    def restore(self):
        for number, handler in self.previous.items():
            signal.signal(number, handler)


class OwnedProcess:
    def __init__(self, command, log_path, timeout_seconds, cancellation, on_started=None):
        if timeout_seconds <= 0:
            raise ValueError('positive bounded timeout required')
        self.command = command
        self.log_path = Path(log_path)
        self.timeout = timeout_seconds
        self.cancellation = cancellation
        self.on_started = on_started
        self.process = None
        self.owned = {}
        self.result = {'timeoutSeconds': timeout_seconds, 'outcome': 'NOT_STARTED'}

    def discover(self):
        if self.process is None:
            return
        records = {}
        for path in Path('/proc').iterdir():
            if path.name.isdigit():
                item = identity(int(path.name))
                if item is not None:
                    records[item.pid] = item
        # A fresh session covers ordinary nested descendants, including new groups.
        # Subreaper adoption and parent closure also retain children that call setsid.
        changed = True
        while changed:
            changed = False
            for pid, item in records.items():
                previous = self.owned.get(pid)
                if previous is not None and previous.start == item.start:
                    continue
                parent = records.get(item.parent)
                parent_owned = self.owned.get(item.parent)
                descended = parent is not None and parent_owned is not None and parent.start == parent_owned.start
                adopted = item.parent == os.getpid() and pid != self.process.pid
                if item.session == self.process.pid or descended or adopted:
                    self.owned[pid] = item
                    changed = True

    def live(self):
        result = []
        for old in self.owned.values():
            current = identity(old.pid)
            if current is not None and current.start == old.start and current.state != 'Z':
                result.append(current)
        return result

    def reap(self):
        # Popen retains ownership of its direct child's wait status.
        if self.process is not None:
            self.process.poll()
        for old in self.owned.values():
            if self.process is not None and old.pid == self.process.pid:
                continue
            current = identity(old.pid)
            if current is not None and current.start != old.start:
                continue
            try:
                os.waitpid(old.pid, os.WNOHANG)
            except ChildProcessError:
                pass

    def signal_owned(self, number):
        for old in self.owned.values():
            current = identity(old.pid)
            if current is None or current.start != old.start:
                continue
            try:
                # A pidfd closes the check-to-signal PID reuse race. Linux/HotSpot25 is
                # the supported reference boundary; do not silently fall back to raw kill.
                fd = os.pidfd_open(old.pid)
            except ProcessLookupError:
                continue
            try:
                checked = identity(old.pid)
                if checked is not None and checked.start == old.start:
                    signal.pidfd_send_signal(fd, number)
            except ProcessLookupError:
                pass
            finally:
                os.close(fd)

    def children_remain(self):
        # In this dedicated subreaper process, ECHILD proves that no live descendant
        # can fork after an apparently empty /proc census. WNOWAIT leaves Popen's
        # direct-child exit status untouched and exposes not-yet-censused adoptees.
        try:
            os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            return True
        except ChildProcessError:
            return False

    def cleanup(self):
        if self.process is None:
            self.result['cleanupComplete'] = True
            return
        began = time.monotonic()
        # Reserve one final KILL budget for late adoption after a parent terminates.
        for number, offset in ((signal.SIGTERM, 3), (signal.SIGKILL, 6), (signal.SIGKILL, 7)):
            deadline = began + offset
            while True:
                # Discovery must precede every emptiness decision: TERM can make
                # the last known parent fork a child and exit between censuses.
                self.discover()
                self.reap()
                self.signal_owned(number)
                self.reap()
                if not self.live() and not self.children_remain():
                    break
                remaining_budget = deadline - time.monotonic()
                if remaining_budget <= 0:
                    break
                time.sleep(min(0.05, remaining_budget))
            if not self.live() and not self.children_remain():
                break
        # Do not introduce a final unsignaled discovery after all kill budgets.
        # The last census above already received KILL and reaping attempts.
        self.reap()
        remaining = self.live()
        self.result['ownedProcessIdentities'] = [
            {'pid': item.pid, 'startTimeTicks': item.start, 'session': item.session}
            for item in self.owned.values()]
        self.result['remainingOwnedProcesses'] = [item.pid for item in remaining]
        self.result['unreapedOwnedChildrenRemain'] = self.children_remain()
        self.result['cleanupComplete'] = not remaining and not self.result['unreapedOwnedChildrenRemain']
        if self.process.poll() is not None:
            self.process.wait()
        if not self.result['cleanupComplete']:
            raise RuntimeError('Owned process cleanup incomplete: ' + repr(self.result))

    def run(self):
        began = time.monotonic()
        try:
            if not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
                raise RuntimeError('Linux pidfd signaling is required')
            libc = ctypes.CDLL(None, use_errno=True)
            if libc.prctl(36, 1, 0, 0, 0) != 0:  # PR_SET_CHILD_SUBREAPER
                raise OSError(ctypes.get_errno(), 'Unable to become child subreaper')
            with self.log_path.open('xb') as output:
                self.process = subprocess.Popen(self.command, stdout=output,
                                                stderr=subprocess.STDOUT, start_new_session=True)
                leader = identity(self.process.pid)
                if leader is None or leader.session != self.process.pid:
                    raise RuntimeError('Unable to capture owned session leader identity')
                self.owned[leader.pid] = leader
                self.result.update({'pid': leader.pid, 'pidStartTimeTicks': leader.start,
                                    'sessionId': leader.session, 'outcome': 'RUNNING'})
                if self.on_started is not None:
                    self.on_started(dict(self.result))
                while True:
                    self.discover()
                    if self.cancellation.signal_number:
                        self.result.update({'outcome': 'CANCELLED',
                                            'exitCode': 128 + self.cancellation.signal_number})
                        break
                    if time.monotonic() - began >= self.timeout:
                        self.result.update({'outcome': 'TIMEOUT', 'exitCode': 124})
                        break
                    try:
                        code = self.process.wait(timeout=0.5)
                        self.result.update({'outcome': 'EXITED', 'exitCode': code})
                        break
                    except subprocess.TimeoutExpired:
                        pass
            return self.result
        except BaseException as error:
            self.result.update({'outcome': 'EXCEPTION', 'exception': type(error).__name__ + ': ' + str(error)})
            raise
        finally:
            try:
                self.cleanup()
            finally:
                self.result['elapsedSeconds'] = time.monotonic() - began
