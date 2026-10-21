#!/usr/bin/env python3
"""Artifact tamper and real process-identity regressions; no Docker or Maven required."""
import importlib.util
import json
import os
import select
import socket
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


artifacts = module('artifacts', 'runtime-artifacts.py')
lifecycle = module('lifecycle', 'lifecycle.py')
SOURCE = '1' * 64


class RuntimeArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.project = Path(self.directory.name)
        for name in artifacts.ARTIFACTS:
            path = self.project / name
            path.parent.mkdir(parents=True)
            path.write_bytes(b'verified runtime bytes')
        artifacts.write(self.project, SOURCE)

    def test_tampered_bytes_fail_even_when_size_and_mtime_are_preserved(self):
        artifacts.check(self.project, SOURCE)
        path = self.project / artifacts.ARTIFACTS[0]
        original = path.stat()
        path.write_bytes(b'X' + path.read_bytes()[1:])
        os.utime(path, ns=(original.st_atime_ns, original.st_mtime_ns))
        with self.assertRaises(ValueError):
            artifacts.check(self.project, SOURCE)

    def test_source_and_receipt_paths_cannot_be_substituted(self):
        with self.assertRaises(ValueError):
            artifacts.check(self.project, '2' * 64)
        target = self.project / '.runtime/build-receipt.json'
        value = json.loads(target.read_text())
        value['artifacts']['../../outside.jar'] = value['artifacts'].pop(artifacts.ARTIFACTS[0])
        target.write_text(json.dumps(value))
        with self.assertRaises(ValueError):
            artifacts.check(self.project, SOURCE)

    def test_symbolic_link_artifact_and_parent_are_refused(self):
        path = self.project / artifacts.ARTIFACTS[0]
        outside = self.project / 'outside.jar'
        path.rename(outside)
        path.symlink_to(outside)
        with self.assertRaises(ValueError):
            artifacts.check(self.project, SOURCE)
        path.unlink()
        parent = path.parent
        parent.rmdir()
        parent.symlink_to(outside.parent, target_is_directory=True)
        with self.assertRaises(ValueError):
            artifacts.check(self.project, SOURCE)


class PortOwnershipTest(unittest.TestCase):
    def test_closed_connections_allow_restart_while_live_listener_is_refused(self):
        with socket.socket() as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(('127.0.0.1', 0))
            server.listen()
            port = server.getsockname()[1]
            with self.assertRaises(lifecycle.OwnershipError):
                lifecycle.free_port(port)
            with socket.create_connection(('127.0.0.1', port), timeout=2) as client:
                connection, _ = server.accept()
                with connection:
                    # The server closes first, leaving its endpoint in TIME_WAIT.
                    connection.shutdown(socket.SHUT_WR)
                    self.assertEqual(b'', client.recv(1))
        lifecycle.free_port(port)


class ProcessOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.runtime = Path(self.directory.name)
        self.jar = self.runtime / 'owned-service.jar'
        self.jar.write_bytes(b'identity fixture')
        self.executable = Path(sys.executable)
        self.child = subprocess.Popen([sys.executable, '-c',
                                       "import time; print('ready', flush=True); time.sleep(60)",
                                       '-jar', str(self.jar)], stdout=subprocess.PIPE)
        self.addCleanup(self.cleanup_child)
        self.assertTrue(select.select([self.child.stdout], [], [], 5)[0])
        self.assertEqual(b'ready\n', self.child.stdout.readline())
        observed = lifecycle.process(self.child.pid)
        self.metadata = {'pid': self.child.pid, 'startTicks': observed['startTicks'],
                         'jar': str(self.jar.resolve()), 'source': SOURCE}
        self.write_record()

    def write_record(self):
        (self.runtime / 'service.pid').write_text(str(self.child.pid))
        (self.runtime / 'service-process.json').write_text(json.dumps(self.metadata))

    def cleanup_child(self):
        if self.child.poll() is None:
            self.child.terminate()
        self.child.wait(timeout=5)
        self.child.stdout.close()

    def test_correct_identity_stops_real_owned_child_and_removes_records(self):
        self.assertEqual(self.child.pid, lifecycle.owned(self.runtime, self.jar, self.executable))
        lifecycle.stop(self.runtime, self.jar, self.executable, timeout=2)
        self.child.wait(timeout=5)
        self.assertFalse((self.runtime / 'service.pid').exists())

    def test_recycled_pid_identity_refuses_signal_and_preserves_live_child(self):
        self.metadata['startTicks'] += 1
        self.write_record()
        with self.assertRaises(lifecycle.OwnershipError):
            lifecycle.stop(self.runtime, self.jar, self.executable, timeout=0)
        self.assertIsNone(self.child.poll())
        self.assertTrue((self.runtime / 'service.pid').exists())

    def test_same_executable_with_other_jar_is_not_owned(self):
        with self.assertRaises(lifecycle.OwnershipError):
            lifecycle.owned(self.runtime, self.jar.with_name('unrelated.jar'), self.executable)
        self.assertIsNone(self.child.poll())

    def test_missing_live_metadata_is_an_error_not_stopped_status(self):
        (self.runtime / 'service-process.json').unlink()
        with self.assertRaises(lifecycle.OwnershipError):
            lifecycle.owned(self.runtime, self.jar, self.executable)
        self.assertIsNone(self.child.poll())


if __name__ == '__main__':
    unittest.main()
