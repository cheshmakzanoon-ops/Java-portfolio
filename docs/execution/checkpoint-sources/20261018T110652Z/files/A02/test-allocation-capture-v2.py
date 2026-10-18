#!/usr/bin/env python3
"""Tiny fake producer/compressor controls; no JFR tool, Java, Docker or workload."""
import gzip
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SCRIPT = ROOT / 'projects/02-durableflow/scripts/capture-allocation-samples.sh'
PAYLOAD = b'{"recording":{"events":[{"type":"jdk.ObjectAllocationSample","values":{"weight":64}}]}}\n'


class CaptureControls(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=HERE, prefix='synthetic-capture-control-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.binary = self.base / 'bin'
        self.binary.mkdir()
        self.recording = self.base / 'SYNTHETIC-NOT-JFR.jfr'
        self.recording.write_bytes(b'SYNTHETIC, never passed to a real JFR tool')
        self.environment = dict(os.environ, PATH=str(self.binary) + os.pathsep + os.environ['PATH'])
        self.write_program('jfr', 'import sys\nsys.stdout.buffer.write(' + repr(PAYLOAD) + ')\n')

    def write_program(self, name, body):
        path = self.binary / name
        path.write_text('#!' + sys.executable + '\n' + body)
        path.chmod(0o700)

    def run_capture(self):
        return subprocess.run(['bash', str(SCRIPT), str(self.recording), str(self.base)],
                              env=self.environment, capture_output=True, timeout=5)

    def test_success_preserves_exact_decoded_json_and_keeps_jfr(self):
        result = self.run_capture()
        self.assertEqual(0, result.returncode, result.stderr)
        canonical = self.base / 'allocation-samples.json.gz'
        self.assertEqual(PAYLOAD, gzip.decompress(canonical.read_bytes()))
        self.assertFalse((self.base / 'allocation-samples.json').exists())
        self.assertFalse((self.base / 'allocation-samples.json.gz.partial').exists())
        self.assertTrue(self.recording.is_file())

    def test_producer_failure_is_not_hidden_by_successful_gzip(self):
        self.write_program('jfr', 'import sys\nsys.stdout.buffer.write(' + repr(PAYLOAD) + ')\nsys.exit(74)\n')
        result = self.run_capture()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.base / 'allocation-samples.json.gz').exists())
        partial = self.base / 'allocation-samples.json.gz.partial'
        self.assertEqual(PAYLOAD, gzip.decompress(partial.read_bytes()))
        self.assertTrue(self.recording.is_file())

    def test_compressor_failure_retains_partial_and_never_publishes(self):
        self.write_program('gzip', 'import sys\nsys.stdin.buffer.read()\nsys.stdout.buffer.write(b"FAILED-COMPRESSOR-PREFIX")\nsys.exit(9)\n')
        result = self.run_capture()
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.base / 'allocation-samples.json.gz').exists())
        self.assertEqual(b'FAILED-COMPRESSOR-PREFIX', (self.base / 'allocation-samples.json.gz.partial').read_bytes())

    def test_existing_canonical_or_partial_is_never_overwritten(self):
        for name in ('allocation-samples.json.gz', 'allocation-samples.json.gz.partial'):
            with self.subTest(name=name):
                path = self.base / name
                path.write_bytes(b'PRIOR-SYNTHETIC-EVIDENCE')
                result = self.run_capture()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(b'PRIOR-SYNTHETIC-EVIDENCE', path.read_bytes())
                path.unlink()

    def test_concurrent_canonical_creation_cannot_be_overwritten_or_hide_partial(self):
        self.write_program('jfr', 'import pathlib,sys\n'
                           'pathlib.Path(sys.argv[-1]).with_name("allocation-samples.json.gz").write_bytes(b"CONCURRENT-EVIDENCE")\n'
                           'sys.stdout.buffer.write(' + repr(PAYLOAD) + ')\n')
        result = self.run_capture()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b'CONCURRENT-EVIDENCE', (self.base / 'allocation-samples.json.gz').read_bytes())
        self.assertEqual(PAYLOAD, gzip.decompress((self.base / 'allocation-samples.json.gz.partial').read_bytes()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
