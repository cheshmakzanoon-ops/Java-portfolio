#!/usr/bin/env python3
"""Controlled source/artifact receipt regressions; no Maven or analyzed code executes."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent


class BuildCacheTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="byteguard-build-cache-")
        self.root = Path(self.directory.name)
        self.project = self.root / "projects/09-byteguard"
        scripts = self.project / "scripts"
        scripts.mkdir(parents=True)
        shutil.copyfile(SCRIPTS / "common.sh", scripts / "common.sh")
        for module in ("analysis-core", "report", "cli", "evaluation"):
            (self.project / module / "src").mkdir(parents=True)
            (self.project / module / "pom.xml").write_text("<project/>\n")
        self.source = self.project / "analysis-core/src/Owned.java"
        self.source.write_text("class Owned {}\n")
        (self.project / "pom.xml").write_text("<project/>\n")
        (self.root / "pom.xml").write_text("<project/>\n")
        wrapper = self.root / "mvnw"
        wrapper.write_text('''#!/usr/bin/env python3
import os,sys
from pathlib import Path
root=Path(__file__).parent; project=root/'projects/09-byteguard'
counter=root/'build-count'; counter.write_text(str(int(counter.read_text())+1) if counter.exists() else '1')
if os.environ.get('BUILD_MODE')=='failure':sys.exit(37)
target=project/'cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar';target.parent.mkdir(parents=True,exist_ok=True)
target.write_bytes(b'verified owned artifact fixture')
if os.environ.get('BUILD_MODE')=='drift':
  source=project/'analysis-core/src/Owned.java';source.write_text(source.read_text()+'// concurrent source edit\\n')
''')
        wrapper.chmod(0o755)
        self.runtime = self.project / ".runtime"
        self.runtime.mkdir()
        self.receipt = self.runtime / "build-fingerprint"
        self.artifact = self.project / "cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar"

    def tearDown(self):
        self.directory.cleanup()

    def build(self, **environment):
        return subprocess.run(["bash", "-c", 'source "$1"; byteguard_build', "_",
                               str(self.project / "scripts/common.sh")],
                              env={**os.environ, **environment}, capture_output=True, text=True, timeout=20)

    def assertNoTemporaryReceipt(self):
        self.assertEqual([], list(self.runtime.glob(".build-fingerprint.*")))

    def test_success_stamps_exact_source_and_artifact_and_cached_run_does_not_build(self):
        first = self.build()
        self.assertEqual(0, first.returncode, first.stderr)
        fields = self.receipt.read_text().splitlines()
        self.assertEqual(2, len(fields))
        self.assertRegex(fields[0], r"^[0-9a-f]{64}$")
        self.assertEqual(hashlib.sha256(self.artifact.read_bytes()).hexdigest(), fields[1])
        second = self.build()
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertIn("verified source cache", second.stderr)
        self.assertEqual("1", (self.root / "build-count").read_text())
        self.assertNoTemporaryReceipt()

    def test_source_drift_cannot_replace_an_existing_receipt(self):
        original = "earlier-source\nearlier-artifact\n"
        self.receipt.write_text(original)
        result = self.build(BUILD_MODE="drift")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("source changed during verification", result.stderr)
        self.assertEqual(original, self.receipt.read_text())
        self.assertNoTemporaryReceipt()

    def test_failed_verification_preserves_receipt_and_exit_status(self):
        original = "earlier-source\nearlier-artifact\n"
        self.receipt.write_text(original)
        result = self.build(BUILD_MODE="failure")
        self.assertEqual(37, result.returncode, result.stderr)
        self.assertEqual(original, self.receipt.read_text())
        self.assertNoTemporaryReceipt()

    def test_failed_atomic_publication_preserves_old_receipt_and_cleans_temporary(self):
        original = "earlier-source\nearlier-artifact\n"
        self.receipt.write_text(original)
        commands = self.root / "bin"
        commands.mkdir()
        move = commands / "mv"
        move.write_text("#!/usr/bin/env bash\nexit 29\n")
        move.chmod(0o755)
        result = self.build(PATH=str(commands) + os.pathsep + os.environ["PATH"])
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("Unable to publish", result.stderr)
        self.assertEqual(original, self.receipt.read_text())
        self.assertNoTemporaryReceipt()

    def test_symbolic_receipt_is_replaced_without_truncating_its_previous_target(self):
        protected = self.root / "protected.txt"
        protected.write_text("preserved owned data\n")
        self.receipt.symlink_to(protected)
        result = self.build()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("preserved owned data\n", protected.read_text())
        self.assertFalse(self.receipt.is_symlink())
        self.assertEqual(2, len(self.receipt.read_text().splitlines()))
        self.assertNoTemporaryReceipt()


if __name__ == "__main__":
    unittest.main()
