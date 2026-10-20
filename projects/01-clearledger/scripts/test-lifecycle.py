#!/usr/bin/env python3
"""Exercise process identity and startup rollback with real children and a controlled Compose CLI."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPTS = Path(__file__).resolve().parent


def alive(pid):
    try:
        return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[0] != 'Z'
    except FileNotFoundError:
        return False


class Fixture:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='clearledger-lifecycle-')
        self.root = Path(self.temporary.name)
        self.project = self.root / 'projects/01-clearledger'
        self.scripts = self.project / 'scripts'
        self.scripts.mkdir(parents=True)
        for name in ('up.sh', 'down.sh', 'common.sh', 'runtime-process.py'):
            shutil.copy2(SCRIPTS / name, self.scripts / name)
        (self.scripts / 'runtime-cache.py').write_text('import sys\nsys.exit(0)\n')
        (self.root / 'scripts').mkdir()
        (self.root / 'scripts/cloud-env.sh').write_text('# fixture keeps the selected executable path\n')
        (self.project / 'target').mkdir()
        self.jar = self.project / 'ledger-api/target/clearledger-api-1.0.0-SNAPSHOT-exec.jar'
        self.jar.parent.mkdir(parents=True)
        self.jar.write_bytes(b'local executable artifact fixture')
        self.record = self.project / 'target/api.pid'
        self.state = self.root / 'compose.json'
        self.state.write_text(json.dumps({'containers': {'aaaa': True, 'bbbb': False},
                                         'volumes': ['retained-data']}))
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.executable('docker', '''import json,os,sys
from pathlib import Path
p=Path(os.environ['TEST_COMPOSE_STATE']);s=json.loads(p.read_text());a=sys.argv[1:]
def save():p.write_text(json.dumps(s))
if a[:1]==['compose']:
 a=a[3:] if a[1:2]==['-f'] else a[1:]
 if a[:1]==['ps']:
  running='--status' in a
  print('\\n'.join(k for k,v in s['containers'].items() if not running or v))
 elif a[:1]==['up']:
  assert '--no-recreate' in a
  s['containers']={k:True for k in s['containers']};s['containers']['cccc']=True;save()
  sys.exit(int(os.environ.get('TEST_UP_EXIT','0')))
 elif a[:1]==['exec']:sys.exit(int(os.environ.get('TEST_SEED_EXIT','0')))
 elif a[:1]==['stop']:
  s['containers']={k:False for k in s['containers']};save()
 else:raise AssertionError(a)
elif a[:1]==['rm']:
 if os.environ.get('TEST_REMOVE_BARRIER'):
  import time
  barrier=Path(os.environ['TEST_REMOVE_BARRIER']);barrier.write_text('entered')
  while not barrier.with_suffix('.release').exists():time.sleep(.005)
 if os.environ.get('TEST_REMOVE_EXIT'):sys.exit(int(os.environ['TEST_REMOVE_EXIT']))
 for k in a[1:]:s['containers'].pop(k,None)
 save()
elif a[:1]==['stop']:
 for k in a[1:]:
  if k in s['containers']:s['containers'][k]=False
 save()
else:raise AssertionError(a)
''')
        self.executable('java', '''import os,signal,time
from pathlib import Path
Path(os.environ['TEST_PROCESS_MARKER']).write_text(str(os.getpid()))
if os.environ.get('TEST_IGNORE_TERM')=='1':signal.signal(signal.SIGTERM,lambda *_:Path(os.environ['TEST_PROCESS_MARKER']+'.term').write_text('received'))
print('local process started',flush=True)
while True:time.sleep(.02)
''')
        self.executable('curl', '''import os,sys
from pathlib import Path
sys.exit(0 if Path(os.environ['TEST_RECORD']).exists() and os.environ.get('TEST_READINESS_FAIL')!='1' else 1)
''')
        self.executable('sleep', 'import time\ntime.sleep(.001)\n')
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        TEST_COMPOSE_STATE=str(self.state), TEST_RECORD=str(self.record),
                        TEST_PROCESS_MARKER=str(self.root / 'started'))

    def executable(self, name, source):
        path = self.bin / name
        path.write_text('#!' + sys.executable + '\n' + source)
        path.chmod(0o700)

    def helper(self, action, *extra, check=True):
        return subprocess.run([sys.executable, str(self.scripts / 'runtime-process.py'), action,
                               str(self.record), str(self.jar), *map(str, extra)],
                              env=self.env, capture_output=True, text=True, timeout=15, check=check)

    def spawn(self):
        self.helper('spawn', '--log', self.project / 'target/api.log', '--', self.bin / 'java',
                    '-jar', self.jar)
        deadline = time.monotonic() + 3
        while not (self.root / 'started').exists() and time.monotonic() < deadline:
            time.sleep(.01)
        assert (self.root / 'started').exists()
        return json.loads(self.record.read_text())

    def script(self, name):
        return subprocess.run(['bash', str(self.scripts / name)], cwd=self.root, env=self.env,
                              capture_output=True, text=True, timeout=60)

    def cleanup(self):
        self.helper('stop', '--timeout', '.1', '--kill-timeout', '2', check=False)
        self.temporary.cleanup()


class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()

    def tearDown(self):
        self.fixture.cleanup()

    def assertRestoredContainers(self):
        self.assertEqual({'containers': {'aaaa': True, 'bbbb': False}, 'volumes': ['retained-data']},
                         json.loads(self.fixture.state.read_text()))

    def test_compose_failure_preserves_primary_status_and_existing_service_states(self):
        self.fixture.env['TEST_UP_EXIT'] = '42'
        result = self.fixture.script('up.sh')
        self.assertEqual(42, result.returncode, result.stderr)
        self.assertFalse(self.fixture.record.exists())
        self.assertRestoredContainers()

    def test_failed_readiness_stops_only_the_new_process_and_restores_dependencies(self):
        self.fixture.env['TEST_READINESS_FAIL'] = '1'
        result = self.fixture.script('up.sh')
        self.assertEqual(1, result.returncode, result.stderr)
        pid = int((self.fixture.root / 'started').read_text())
        self.assertFalse(alive(pid))
        self.assertFalse(self.fixture.record.exists())
        self.assertRestoredContainers()

    def test_seed_failure_after_readiness_does_not_leak_a_started_process(self):
        self.fixture.env['TEST_SEED_EXIT'] = '43'
        result = self.fixture.script('up.sh')
        self.assertEqual(43, result.returncode, result.stderr)
        self.assertFalse(alive(int((self.fixture.root / 'started').read_text())))
        self.assertRestoredContainers()

    def test_failed_reuse_preserves_a_preexisting_owned_api(self):
        record = self.fixture.spawn()
        self.fixture.env['TEST_UP_EXIT'] = '42'
        result = self.fixture.script('up.sh')
        self.assertEqual(42, result.returncode, result.stderr)
        self.assertTrue(alive(record['pid']))
        self.assertEqual(record, json.loads(self.fixture.record.read_text()))
        self.assertRestoredContainers()

    def test_kernel_start_identity_mismatch_refuses_to_signal_a_matching_command(self):
        record = self.fixture.spawn()
        changed = dict(record, start_ticks=record['start_ticks'] + 1)
        self.fixture.record.write_text(json.dumps(changed))
        try:
            result = self.fixture.helper('stop', check=False)
            self.assertEqual(2, result.returncode)
            self.assertIn('PROCESS_IDENTITY_MISMATCH', result.stderr)
            self.assertTrue(alive(record['pid']))
        finally:
            self.fixture.record.write_text(json.dumps(record))

    def test_bounded_stop_escalates_and_retains_exact_artifact_provenance(self):
        self.fixture.env['TEST_IGNORE_TERM'] = '1'
        record = self.fixture.spawn()
        self.assertEqual(str(self.fixture.jar.resolve()), record['artifact'])
        self.assertEqual(hashlib.sha256(self.fixture.jar.read_bytes()).hexdigest(),
                         record['artifact_sha256'])
        self.assertTrue(record['boot_id'])
        self.fixture.helper('stop', '--timeout', '.1', '--kill-timeout', '2')
        self.assertFalse(alive(record['pid']))
        self.assertFalse(self.fixture.record.exists())

    def test_cleanup_failure_remains_visible_without_replacing_the_primary_status(self):
        self.fixture.env.update(TEST_UP_EXIT='42', TEST_REMOVE_EXIT='37')
        result = self.fixture.script('up.sh')
        self.assertEqual(42,result.returncode,result.stderr)
        self.assertIn('Failed to remove newly created container cccc',result.stderr)
        state=json.loads(self.fixture.state.read_text())
        self.assertTrue(state['containers']['aaaa'])
        self.assertFalse(state['containers']['bbbb'])
        self.assertTrue(state['containers']['cccc'])

    def test_same_artifact_basename_in_another_directory_does_not_authorize_stop(self):
        record = self.fixture.spawn()
        other=self.fixture.root/'other'/self.fixture.jar.name
        other.parent.mkdir();other.write_bytes(self.fixture.jar.read_bytes())
        result=subprocess.run([sys.executable,str(self.fixture.scripts/'runtime-process.py'),'stop',str(self.fixture.record),str(other)],capture_output=True,text=True,timeout=5)
        self.assertEqual(2,result.returncode)
        self.assertTrue(alive(record['pid']))

    def test_nonfinite_stop_budgets_are_rejected_before_signaling(self):
        record=self.fixture.spawn()
        for value in ('nan','inf','-inf','-1'):
            result=self.fixture.helper('stop','--timeout='+value,check=False)
            self.assertEqual(2,result.returncode)
            self.assertIn('finite and nonnegative',result.stderr)
            self.assertTrue(alive(record['pid']))

    def test_stale_artifact_refuses_reuse_but_keeps_authority_to_stop_the_recorded_process(self):
        record=self.fixture.spawn()
        self.fixture.jar.write_bytes(b'replacement artifact')
        result=self.fixture.helper('current',check=False)
        self.assertEqual(2,result.returncode)
        self.assertIn('STALE_PROCESS_ARTIFACT',result.stderr)
        self.assertTrue(alive(record['pid']))
        self.fixture.helper('stop')
        self.assertFalse(alive(record['pid']))

    def test_terminal_cancellation_rolls_back_the_new_process_and_dependencies(self):
        self.fixture.env['TEST_READINESS_FAIL']='1'
        process=subprocess.Popen(['bash',str(self.fixture.scripts/'up.sh')],cwd=self.fixture.root,env=self.fixture.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            deadline=time.monotonic()+10
            while not self.fixture.record.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.01)
            self.assertTrue(self.fixture.record.exists())
            record=json.loads(self.fixture.record.read_text())
            process.send_signal(signal.SIGTERM)
            _,error=process.communicate(timeout=10)
            self.assertEqual(143,process.returncode,error)
            self.assertFalse(alive(record['pid']))
            self.assertRestoredContainers()
        finally:
            if process.poll() is None:process.kill();process.communicate(timeout=5)

    def test_repeated_terminal_signals_do_not_interrupt_failure_cleanup(self):
        barrier=self.fixture.root/'remove-barrier'
        self.fixture.env.update(TEST_UP_EXIT='42',TEST_REMOVE_BARRIER=str(barrier))
        process=subprocess.Popen(['bash',str(self.fixture.scripts/'up.sh')],cwd=self.fixture.root,env=self.fixture.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            deadline=time.monotonic()+10
            while not barrier.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.01)
            self.assertTrue(barrier.exists())
            process.send_signal(signal.SIGTERM);process.send_signal(signal.SIGTERM)
            barrier.with_suffix('.release').touch()
            _,error=process.communicate(timeout=10)
            self.assertEqual(42,process.returncode,error)
            self.assertRestoredContainers()
        finally:
            barrier.with_suffix('.release').touch()
            if process.poll() is None:process.kill();process.communicate(timeout=5)

    def test_canceling_stop_preserves_cleanup_until_the_recorded_process_is_dead(self):
        self.fixture.env['TEST_IGNORE_TERM']='1'
        record=self.fixture.spawn()
        process=subprocess.Popen([sys.executable,str(self.fixture.scripts/'runtime-process.py'),'stop',str(self.fixture.record),str(self.fixture.jar),'--timeout','.3','--kill-timeout','2'],env=self.fixture.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            marker=Path(self.fixture.env['TEST_PROCESS_MARKER']+'.term')
            deadline=time.monotonic()+5
            while not marker.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.005)
            self.assertTrue(marker.exists())
            process.send_signal(signal.SIGTERM)
            _,error=process.communicate(timeout=5)
            self.assertEqual(143,process.returncode,error)
            self.assertFalse(alive(record['pid']))
            self.assertFalse(self.fixture.record.exists())
        finally:
            if process.poll() is None:process.kill();process.communicate(timeout=5)

    def restricted_helper(self, action, record, artifact, restricted_errno=13):
        controller = (
            'import errno,importlib.util,os,sys\n'
            f'spec=importlib.util.spec_from_file_location("runtime_process",{str(self.fixture.scripts / "runtime-process.py")!r})\n'
            'module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)\n'
            'original=os.readlink\n'
            'def restricted(path):\n'
            f' if str(path)=="/proc/{record["pid"]}/exe": raise PermissionError({restricted_errno},"controlled procfs observation")\n'
            ' return original(path)\n'
            'module.os.readlink=restricted\n'
            f'sys.argv=["runtime-process.py",{action!r},{str(self.fixture.record)!r},{str(artifact)!r}]\n'
            'try: sys.exit(module.main())\n'
            'except (module.OwnershipError,OSError) as failure: print(str(failure),file=sys.stderr);sys.exit(2)\n'
        )
        return subprocess.run([sys.executable, '-c', controller], env=self.fixture.env,
                              capture_output=True, text=True, timeout=10)

    def test_restricted_executable_observation_retains_exact_kernel_and_argument_authority(self):
        record = self.fixture.spawn()
        self.assertEqual(64, len(record['argv_sha256']))
        for number in (13, 1):
            result = self.restricted_helper('alive', record, self.fixture.jar, number)
            self.assertEqual(0, result.returncode, result.stderr)
        result = self.restricted_helper('alive', record, self.fixture.jar, 5)
        self.assertEqual(2, result.returncode, result.stderr)
        result = self.restricted_helper('stop', record, self.fixture.jar)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(alive(record['pid']))

    def test_restricted_executable_observation_does_not_accept_changed_complete_arguments(self):
        record = self.fixture.spawn()
        self.fixture.record.write_text(json.dumps(dict(record, argv_sha256='0' * 64)))
        try:
            result = self.restricted_helper('stop', record, self.fixture.jar)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertIn('PROCESS_IDENTITY_MISMATCH', result.stderr)
            self.assertTrue(alive(record['pid']))
        finally:
            self.fixture.record.write_text(json.dumps(record))

    def test_successful_down_waits_for_owned_exit_and_retains_volumes(self):
        result = self.fixture.script('up.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        record = json.loads(self.fixture.record.read_text())
        result = self.fixture.script('down.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(alive(record['pid']))
        self.assertFalse(self.fixture.record.exists())
        state = json.loads(self.fixture.state.read_text())
        self.assertFalse(any(state['containers'].values()))
        self.assertEqual(['retained-data'], state['volumes'])


if __name__ == '__main__':
    unittest.main()
