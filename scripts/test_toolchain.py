"""Exercise bootstrap failure boundaries without downloading or trusting a fake JDK."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


BOOTSTRAP = Path(__file__).with_name('bootstrap-cloud.sh')
ARCHIVE = 'OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz'


class ToolchainIntegrityTest(unittest.TestCase):
    def invoke(self, root):
        environment = dict(os.environ, PORTFOLIO_TOOLS_DIR=str(root))
        return subprocess.run(['bash', str(BOOTSTRAP)], env=environment,
                              capture_output=True, text=True)

    def test_corrupt_cached_archive_is_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'downloads').mkdir()
            archive = root / 'downloads' / ARCHIVE
            archive.write_bytes(b'unverified archive')
            result = self.invoke(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('FAILED', result.stdout + result.stderr)
            self.assertFalse((root / 'jdk-25').exists())
            self.assertEqual([], list(root.glob('jdk-25.staging.*')))

    def test_wrong_preinstalled_runtime_is_rejected_without_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binaries = root / 'jdk-25/bin'
            binaries.mkdir(parents=True)
            java = binaries / 'java'
            java.write_text('#!/bin/sh\nprintf "%s\\n" "openjdk 21"\n')
            java.chmod(0o755)
            javac = binaries / 'javac'
            javac.write_text('#!/bin/sh\nexit 0\n')
            javac.chmod(0o755)
            before = java.read_bytes()
            result = self.invoke(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('differs from pinned', result.stderr)
            self.assertEqual(before, java.read_bytes())


if __name__ == '__main__':
    unittest.main()
