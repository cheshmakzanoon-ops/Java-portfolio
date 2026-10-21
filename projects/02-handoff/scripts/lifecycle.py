#!/usr/bin/env python3
"""Owned Linux runtime processes and rollback of startup acquisitions; volumes are retained."""
import argparse
from contextlib import contextmanager
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request


class LifecycleError(RuntimeError):
    pass


def process(pid):
    # Procfs fields are separate reads: a live process may become a zombie between
    # stat and cmdline. Never interpret that empty, exiting snapshot as a new owner.
    deadline = time.monotonic() + 0.1
    while True:
        try:
            before = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
            if before[0] in ("Z", "X"):
                return None
            arguments = [os.fsdecode(value) for value in
                         Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")[:-1]]
            try:
                executable = str(Path(f"/proc/{pid}/exe").resolve(strict=True))
            except PermissionError as restricted:
                if restricted.errno not in (errno.EACCES, errno.EPERM):
                    raise
                # Some managed procfs mounts restrict executable dereference after
                # reparenting. Boot/start and exact saved argv still fence this PID.
                executable = None
            after = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
            if after[0] in ("Z", "X"):
                return None
            if before[19] == after[19] and arguments:
                return {"startTicks": int(after[19]), "arguments": arguments,
                        "executable": executable}
            if time.monotonic() >= deadline:
                raise LifecycleError("cannot establish a consistent live process snapshot")
            time.sleep(0.005)
        except (FileNotFoundError, ProcessLookupError):
            return None


class Runtime:
    roles = ("api", "provider", "scheduler-1", "scheduler-2", "worker-1", "worker-2", "worker-3")

    def __init__(self, project, docker=None, readiness_timeout=120):
        self.project = Path(project).resolve()
        self.runtime = self.project / "target/runtime"
        self.runtime.mkdir(parents=True, exist_ok=True)
        self.java = str(Path(shutil.which("java") or "java").resolve())
        self.docker = docker or ["docker"]
        self.compose = self.docker + ["compose", "-f", str(self.project / "infra/compose.yaml")]
        self.readiness_timeout = readiness_timeout
        self.boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        self.children = {}

    @contextmanager
    def lock(self):
        with (self.runtime / "lifecycle.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            yield

    def command(self, name, executable=None):
        command = [executable or self.java,
                   "-Xmx256m" if name == "api" else "-Xmx128m",
                   f"-Dhandoff.process.name={name}",
                   f"-Xlog:gc*:file={self.runtime / (name + '-gc.log')}:time,uptime,level,tags"]
        if name == "api":
            artifact = self.project / "workflow-api/target/handoff-workflow-api-1.0.0-SNAPSHOT-exec.jar"
            return command + ["-jar", str(artifact), "--spring.profiles.active=dev"], artifact
        if name == "provider":
            arguments = ["provider"]
        elif re.fullmatch(r"scheduler-[1-9][0-9]*", name):
            arguments = ["scheduler"]
        elif re.fullmatch(r"worker-[1-9][0-9]*", name):
            arguments = ["worker", name]
        else:
            raise LifecycleError("unknown runtime role")
        artifact = self.project / "workflow-verification/target/handoff-workflow-verification-1.0.0-SNAPSHOT-exec.jar"
        return command + ["-Dloader.main=io.github.cheshmakzanoon.portfolio.handoff.RuntimeMain",
                          "-cp", str(artifact), "org.springframework.boot.loader.launch.PropertiesLauncher",
                          *arguments], artifact

    def paths(self, name):
        return self.runtime / (name + ".pid"), self.runtime / (name + ".process.json")

    def clear(self, name):
        for path in self.paths(name):
            path.unlink(missing_ok=True)

    def owned(self, name):
        pid_file, metadata = self.paths(name)
        if not pid_file.exists() and not metadata.exists():
            return None
        try:
            saved = json.loads(metadata.read_text()) if metadata.exists() else None
            pid = int(pid_file.read_text()) if pid_file.exists() else saved["pid"]
            if pid <= 1:
                raise ValueError("invalid PID")
            observed = process(pid)
            if observed is None:
                self.clear(name)
                return None
            if saved is None or not pid_file.exists():
                raise LifecycleError("live process has incomplete ownership records")
            arguments, artifact = self.command(name, saved.get("executable"))
            checks = {"schema": saved.get("schemaVersion") == 1, "pid": saved.get("pid") == pid,
                      "boot": saved.get("bootId") == self.boot,
                      "start": saved.get("startTicks") == observed["startTicks"],
                      "executable": saved.get("executable") == self.java
                          and observed["executable"] in (None, saved.get("executable")),
                      "artifact": saved.get("artifact") == str(artifact),
                      "arguments": saved.get("arguments") == arguments == observed["arguments"]}
            if not all(checks.values()):
                changed = ",".join(key for key, matches in checks.items() if not matches)
                raise LifecycleError("live process identity differs from its exact role/artifact record (" + changed + ")")
            return saved
        except (ValueError, TypeError, KeyError, OSError) as failure:
            raise LifecycleError("cannot establish live process ownership; inspect runtime records") from failure

    def existing(self):
        names = {path.name.removesuffix(".process.json") for path in self.runtime.glob("*.process.json")}
        names.update(path.stem for path in self.runtime.glob("*.pid"))
        return {name: record for name in sorted(names) if (record := self.owned(name)) is not None}

    def start_role(self, name):
        existing = self.owned(name)
        arguments, artifact = self.command(name)
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        if existing is not None:
            if existing.get("artifactSha256") != digest:
                raise LifecycleError("running artifact differs from disk; stop before rebuilding/restarting")
            return existing, False
        with (self.runtime / (name + ".log")).open("ab") as log:
            child = subprocess.Popen(arguments, stdin=subprocess.DEVNULL,
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            observed = process(child.pid)
            if observed is None:
                child.wait(timeout=5)
                raise LifecycleError(name + " exited during startup")
            if observed["executable"] != self.java or observed["arguments"] != arguments:
                raise LifecycleError("new process executable/arguments could not be verified")
            record = {"schemaVersion": 1, "pid": child.pid, "bootId": self.boot,
                      "startTicks": observed["startTicks"], "executable": observed["executable"],
                      "arguments": arguments, "artifact": str(artifact), "artifactSha256": digest}
            for destination, text in ((self.paths(name)[1], json.dumps(record) + "\n"),
                                      (self.paths(name)[0], str(child.pid) + "\n")):
                temporary = destination.with_suffix(destination.suffix + ".tmp")
                temporary.write_text(text)
                temporary.replace(destination)
            if self.owned(name) is None:
                raise LifecycleError(name + " exited before ownership was recorded")
            self.children[name] = child
            return record, True
        except BaseException:
            # This unreaped Popen child cannot have its PID recycled while cleanup runs.
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
            self.clear(name)
            raise

    def stop(self, records, grace=35):
        descriptors = {}
        try:
            # Validate all selected owners before signaling any of them.
            for name, saved in records.items():
                current = self.owned(name)
                if current is not None and current != saved:
                    raise LifecycleError("ownership changed before shutdown")
            for name, saved in records.items():
                if self.owned(name) is None:
                    continue
                if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
                    raise LifecycleError("safe runtime signaling requires Linux pidfd support")
                try:
                    descriptors[name] = os.pidfd_open(saved["pid"])
                except ProcessLookupError:
                    continue
                self.signal(name, saved, descriptors[name], signal.SIGTERM)
            deadline = time.monotonic() + grace
            while time.monotonic() < deadline and any(self.owned(name) for name in descriptors):
                time.sleep(0.05)
            for name, descriptor in descriptors.items():
                if self.owned(name) is not None:
                    self.signal(name, records[name], descriptor, signal.SIGKILL)
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and any(self.owned(name) for name in descriptors):
                time.sleep(0.05)
            if any(self.owned(name) for name in descriptors):
                raise LifecycleError("owned runtime did not exit within the shutdown bound")
            for name in records:
                self.clear(name)
                child = self.children.pop(name, None)
                if child is not None:
                    child.wait(timeout=5)
        finally:
            for descriptor in descriptors.values():
                os.close(descriptor)

    def signal(self, name, saved, descriptor, number):
        current = self.owned(name)
        if current is None:
            return
        if current != saved:
            raise LifecycleError("process identity changed after opening its pidfd")
        try:
            signal.pidfd_send_signal(descriptor, number)
        except ProcessLookupError:
            pass

    def containers(self, running=False):
        options = ["ps", "--quiet", "--status", "running"] if running else ["ps", "--all", "--quiet"]
        result = subprocess.run(self.compose + options, check=True, capture_output=True, text=True)
        return set(result.stdout.split())

    def rollback_containers(self, before_all, before_running):
        current_all, current_running = self.containers(), self.containers(True)
        failures = []
        for container in sorted(current_running - before_running):
            try:
                subprocess.run(self.docker + ["stop", "--time", "5", container], check=True)
            except subprocess.CalledProcessError as failure:
                failures.append(failure)
        for container in sorted(current_all - before_all):
            try:
                subprocess.run(self.docker + ["rm", "--force", container], check=True)
            except subprocess.CalledProcessError as failure:
                failures.append(failure)
        if failures:
            raise LifecycleError("one or more acquired containers could not be restored")

    def ready(self):
        deadline = time.monotonic() + self.readiness_timeout
        ports = {"api": int(os.environ.get("PORT", "18200")),
                 "provider": int(os.environ.get("PROVIDER_PORT", "18201"))}
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while time.monotonic() < deadline:
            if any(self.owned(name) is None for name in self.roles):
                raise LifecycleError("a runtime exited before readiness; inspect its role log")
            try:
                for role, port in ports.items():
                    with opener.open(f"http://127.0.0.1:{port}/health", timeout=1) as response:
                        body = response.read(1025)
                    if role == "api":
                        payload = json.loads(body)
                        if len(body) > 1024 or not isinstance(payload, dict) or payload.get("status") != "UP":
                            raise LifecycleError("API did not report readiness")
                    if role == "provider" and body != b"ok":
                        raise LifecycleError("provider did not report readiness")
                    owner = self.owned(role)
                    if owner is None or not listener_belongs_to(owner["pid"], port):
                        raise LifecycleError("readiness response belongs to a different process")
                return
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
                time.sleep(0.1)
        raise LifecycleError("runtime readiness exceeded its startup bound")

    def up(self):
        before_all, before_running = self.containers(), self.containers(True)
        acquired = {}
        try:
            subprocess.run(self.compose + ["up", "-d", "--no-recreate", "--wait", "--wait-timeout", "90"], check=True)
            for name in self.roles:
                record, created = self.start_role(name)
                if created:
                    acquired[name] = record
            self.ready()
        except BaseException:
            # Cleanup failures are diagnostics; preserve the original startup status.
            try:
                self.stop(acquired)
            except Exception as cleanup:
                print("Runtime rollback failed: " + str(cleanup), file=sys.stderr)
            try:
                self.rollback_containers(before_all, before_running)
            except Exception as cleanup:
                print("Container rollback failed: " + str(cleanup), file=sys.stderr)
            raise


def listener_belongs_to(pid, port):
    sockets = set()
    try:
        for descriptor in Path(f"/proc/{pid}/fd").iterdir():
            try:
                target = os.readlink(descriptor)
                if target.startswith("socket:["):
                    sockets.add(target[8:-1])
            except FileNotFoundError:
                pass
        for table in ("tcp", "tcp6"):
            for line in Path(f"/proc/net/{table}").read_text().splitlines()[1:]:
                columns = line.split()
                if columns[3] == "0A" and int(columns[1].split(":")[1], 16) == port and columns[9] in sockets:
                    return True
    except FileNotFoundError:
        pass
    except PermissionError as restricted:
        if restricted.errno not in (errno.EACCES, errno.EPERM):
            raise
        raise LifecycleError("listener ownership is hidden by restricted procfs; run down, then up") from restricted
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("running", "up", "down", "launch"))
    parser.add_argument("name", nargs="?")
    args = parser.parse_args()
    if args.operation == "launch" and args.name is None:
        parser.error("launch requires a runtime role")
    runtime = Runtime(Path(__file__).resolve().parents[1])
    try:
        with runtime.lock():
            if args.operation == "running":
                return 0 if runtime.existing() else 1
            if args.operation == "up":
                runtime.up()
                print("Handoff ready")
            elif args.operation == "down":
                runtime.stop(runtime.existing())
                subprocess.run(runtime.compose + ["stop"], check=True)
            else:
                runtime.start_role(args.name)
        return 0
    except subprocess.CalledProcessError as failure:
        print("Runtime command failed", file=sys.stderr)
        return failure.returncode if 0 < failure.returncode < 126 else 1
    except (LifecycleError, OSError, ValueError) as failure:
        print("Runtime refused: " + str(failure), file=sys.stderr)
        return 2 if args.operation == "running" else 1


if __name__ == "__main__":
    def interrupted(number, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
