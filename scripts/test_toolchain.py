"""Exercise offline bootstrap integrity and managed activation in isolated shells."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


BOOTSTRAP = Path(__file__).with_name('bootstrap-cloud.sh')
CLOUD_ENV = Path(__file__).with_name('cloud-env.sh')
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


class ToolchainActivationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.checkout = self.root / 'checkout'
        scripts = self.checkout / 'scripts'
        scripts.mkdir(parents=True)
        self.activation = scripts / CLOUD_ENV.name
        shutil.copyfile(CLOUD_ENV, self.activation)
        self.environment = dict(os.environ)
        for name in ('PORTFOLIO_TOOLS_DIR', 'HTTPS_PROXY', 'MAVEN_ARGS'):
            self.environment.pop(name, None)
        self.environment['JAVA_HOME'] = '/previous/runtime'

    def activate(self, **overrides):
        environment = dict(self.environment, **overrides)
        return subprocess.run(
            ['bash', '--noprofile', '--norc', '-c', '''
set -euo pipefail
source "$1"
python3 - <<'PY'
import json
import os
names = ('JAVA_HOME', 'PATH', 'MAVEN_USER_HOME', 'MAVEN_OPTS', 'MAVEN_ARGS')
print(json.dumps({name: os.environ.get(name) for name in names}))
PY
''', 'activation-test', str(self.activation)],
            cwd=self.checkout, env=environment, capture_output=True, text=True, timeout=20)

    def activated_environment(self, **overrides):
        result = self.activate(**overrides)
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def test_unset_and_empty_tools_directory_preserve_managed_default(self):
        for override in ({}, {'PORTFOLIO_TOOLS_DIR': ''}):
            with self.subTest(override=override):
                active = self.activated_environment(**override)
                self.assertEqual('/workspace/.tools/jdk-25', active['JAVA_HOME'])
                self.assertEqual('/workspace/.tools/jdk-25/bin:' + self.environment['PATH'],
                                 active['PATH'])

    def test_explicit_tools_directory_selects_bootstrap_runtime_and_preserves_path(self):
        tools = self.root / 'tools with spaces'
        active = self.activated_environment(PORTFOLIO_TOOLS_DIR=str(tools))
        expected = str(tools / 'jdk-25')
        self.assertEqual(expected, active['JAVA_HOME'])
        self.assertEqual(expected + '/bin:' + self.environment['PATH'], active['PATH'])
        self.assertEqual(str(self.checkout / '.work/wrapper'), active['MAVEN_USER_HOME'])
        self.assertEqual('-Xmx512m -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts',
                         active['MAVEN_OPTS'])
        self.assertIsNone(active['MAVEN_ARGS'])

    def test_relative_tools_directory_has_the_same_working_directory_as_bootstrap(self):
        active = self.activated_environment(PORTFOLIO_TOOLS_DIR='local-tools')
        self.assertEqual('local-tools/jdk-25', active['JAVA_HOME'])
        self.assertEqual('local-tools/jdk-25/bin:' + self.environment['PATH'], active['PATH'])

    def test_proxy_settings_still_use_verified_central_and_system_trust(self):
        tools = self.root / 'custom-tools'
        active = self.activated_environment(
            PORTFOLIO_TOOLS_DIR=str(tools), HTTPS_PROXY='http://proxy.example:18080')
        settings = self.checkout / '.work/settings.xml'
        self.assertEqual(str(tools / 'jdk-25'), active['JAVA_HOME'])
        self.assertEqual('-s ' + str(settings), active['MAVEN_ARGS'])
        document = ET.parse(settings).getroot()
        self.assertEqual('https://repo.maven.apache.org/maven2',
                         document.findtext('./mirrors/mirror/url'))
        self.assertEqual('proxy.example', document.findtext('./proxies/proxy/host'))
        self.assertEqual('18080', document.findtext('./proxies/proxy/port'))
        self.assertEqual('localhost|127.0.0.1|[::1]',
                         document.findtext('./proxies/proxy/nonProxyHosts'))
        self.assertIn('-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts',
                      active['MAVEN_OPTS'])

    def test_authenticated_proxy_is_still_rejected_without_copying_credentials(self):
        result = self.activate(
            PORTFOLIO_TOOLS_DIR=str(self.root / 'custom-tools'),
            HTTPS_PROXY='http://private-user:private-password@proxy.example:18080')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Authenticated proxy requires a secure Maven credential binding',
                      result.stderr)
        self.assertNotIn('private-user', result.stdout + result.stderr)
        self.assertNotIn('private-password', result.stdout + result.stderr)
        self.assertFalse((self.checkout / '.work/settings.xml').exists())


if __name__ == '__main__':
    unittest.main()
