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
        self.temporary = tempfile.TemporaryDirectory(prefix='latearrival-lifecycle-')
        self.root = Path(self.temporary.name)
        self.project = self.root / 'projects/04-latearrival'
        self.scripts = self.project / 'scripts'
        self.scripts.mkdir(parents=True)
        for name in ('up.sh', 'down.sh', 'common.sh', 'runtime-process.py', 'run-bounded.py'):
            shutil.copy2(SCRIPTS / name, self.scripts / name)
        (self.scripts / 'fingerprint.py').write_text("import os\nprint(os.environ.get('TEST_FINGERPRINT','fixture-current'))\n")
        (self.root / 'scripts').mkdir()
        (self.root / 'scripts/cloud-env.sh').write_text('# fixture keeps the selected executable path\n')
        (self.project / 'target').mkdir()
        self.jar = self.project / 'service/target/latearrival-service-1.0.0-SNAPSHOT-exec.jar'
        self.jar.parent.mkdir(parents=True)
        self.jar.write_bytes(b'local executable artifact fixture')
        self.runtime = self.root / '.work/A04/runtime'
        self.runtime.mkdir(parents=True)
        self.record = self.runtime / 'service.pid'
        verification = self.project / 'verification/target/latearrival-verification-1.0.0-SNAPSHOT.jar'
        verification.parent.mkdir(parents=True)
        verification.write_bytes(b'local verifier artifact fixture')
        (self.runtime / 'build.fingerprint').write_text('fixture-current\n')
        (self.runtime / 'artifacts.sha256.json').write_text(json.dumps({
            str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in (self.jar,verification)}))
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
 elif a[:1]==['exec']:sys.exit(int(os.environ.get('TEST_TOPIC_EXIT','0')))
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
        self.helper('spawn', '--log', self.runtime / 'service.log', '--', self.bin / 'java',
                    '-jar', self.jar)
        deadline = time.monotonic() + 3
        while not (self.root / 'started').exists() and time.monotonic() < deadline:
            time.sleep(.01)
        assert (self.root / 'started').exists()
        (self.runtime / 'services.fingerprint').write_text('fixture-current\n')
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

    def test_topic_creation_failure_restores_dependencies_before_process_start(self):
        self.fixture.env['TEST_TOPIC_EXIT'] = '43'
        result = self.fixture.script('up.sh')
        self.assertEqual(43, result.returncode, result.stderr)
        self.assertFalse((self.fixture.root / 'started').exists())
        self.assertFalse(self.fixture.record.exists())
        self.assertRestoredContainers()

    def test_failed_reuse_preserves_a_preexisting_owned_service(self):
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

    def test_changed_sources_refuse_rebuilding_under_a_preexisting_service(self):
        record=self.fixture.spawn()
        self.fixture.env['TEST_FINGERPRINT']='changed-sources'
        result=self.fixture.script('up.sh')
        self.assertEqual(1,result.returncode,result.stderr)
        self.assertIn('matching verified build',result.stderr)
        self.assertTrue(alive(record['pid']))
        self.assertRestoredContainers()

    def test_direct_build_refuses_replacing_artifacts_under_a_running_service(self):
        record=self.fixture.spawn()
        original=self.fixture.jar.read_bytes()
        result=subprocess.run(['bash','-c','source "$1"; build','bash',str(self.fixture.scripts/'common.sh')],cwd=self.fixture.root,env=self.fixture.env,capture_output=True,text=True,timeout=10)
        self.assertEqual(1,result.returncode,result.stderr)
        self.assertIn('Stop the owned service',result.stderr)
        self.assertEqual(original,self.fixture.jar.read_bytes())
        self.assertTrue(alive(record['pid']))


def await_file(path):
    deadline = time.monotonic() + 5
    while not path.exists():
        if time.monotonic() >= deadline:
            raise AssertionError(f'fixture startup deadline: {path.name}')
        time.sleep(.01)


@unittest.skipUnless(hasattr(os, "killpg") and Path("/proc").exists(), "POSIX process ownership test")
class ProcessLifecycleTest(unittest.TestCase):
    def launch(self, directory, parent_waits, parent_resists=False):
        pid_file = directory / "descendant.pid"
        ready = directory / "ready"
        descendant = (
            "import signal,time; from pathlib import Path; "
            "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
            f"Path({str(ready)!r}).touch(); time.sleep(120)"
        )
        parent = (
            "import subprocess,sys,time,signal; from pathlib import Path; "
            + (f"signal.signal(signal.SIGTERM,lambda *args: Path({str(directory / 'cleanup')!r}).touch()); "
               if parent_resists else "") +
            f"child=subprocess.Popen([sys.executable,'-c',{descendant!r}]); "
            f"Path({str(pid_file)!r}).write_text(str(child.pid)); "
            f"ready=Path({str(ready)!r}); "
            "\nwhile not ready.exists(): time.sleep(.01)\n"
            + ("time.sleep(120)" if parent_waits else "sys.exit(0)")
        )
        return pid_file, [sys.executable, "-c", parent]

    def assert_stopped(self, pid):
        deadline = time.monotonic() + 3
        while alive(pid) and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertFalse(alive(pid), "owned descendant survived controller completion")

    def cleanup(self, pid_file):
        if pid_file.exists():
            pid = int(pid_file.read_text())
            if alive(pid):
                os.kill(pid, signal.SIGKILL)

    def test_successful_parent_exit_cannot_leave_a_descendant(self):
        with tempfile.TemporaryDirectory() as temporary:
            pid_file, command = self.launch(Path(temporary), False)
            try:
                result = subprocess.run([sys.executable, str(SCRIPTS / "run-bounded.py"), "5", *command],
                                        timeout=12, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                self.cleanup(pid_file)

    def test_group_leader_identity_is_retained_until_the_last_cleanup_signal(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            pid_file, command = self.launch(directory, False)
            observations = directory / 'observed-leader-states'
            controller = (
                'import os,runpy,sys; from pathlib import Path; original=os.killpg\n'
                'def checked(pid,signum):\n'
                ' state=Path(f"/proc/{pid}/stat").read_text().rsplit(")",1)[1].split()[0]\n'
                f' with Path({str(observations)!r}).open("a") as log: log.write(state+"\\n")\n'
                ' original(pid,signum)\n'
                'os.killpg=checked\n'
                f'sys.argv={[str(SCRIPTS / "run-bounded.py"), "5", *command]!r}\n'
                f'runpy.run_path({str(SCRIPTS / "run-bounded.py")!r},run_name="__main__")'
            )
            try:
                result = subprocess.run([sys.executable, '-c', controller],
                                        timeout=12, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(['Z', 'Z'], observations.read_text().splitlines())
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                self.cleanup(pid_file)

    def test_deadline_kills_an_entire_group_including_term_resistant_descendant(self):
        with tempfile.TemporaryDirectory() as temporary:
            pid_file, command = self.launch(Path(temporary), True)
            try:
                result = subprocess.run([sys.executable, str(SCRIPTS / "run-bounded.py"), "1", *command],
                                        timeout=12, capture_output=True, text=True)
                self.assertEqual(result.returncode, 124, result.stderr)
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                self.cleanup(pid_file)

    def test_repeated_signals_cannot_interrupt_owned_group_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            pid_file, command = self.launch(directory, True, parent_resists=True)
            controller = subprocess.Popen([sys.executable, str(SCRIPTS / "run-bounded.py"), "30", *command],
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                await_file(directory / "ready")
                controller.terminate()
                # The child's handler proves the controller entered its bounded cleanup wait.
                await_file(directory / "cleanup")
                controller.terminate()
                _, errors = controller.communicate(timeout=12)
                self.assertEqual(controller.returncode, 143, errors)
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                if controller.poll() is None:
                    controller.kill()
                    controller.communicate()
                self.cleanup(pid_file)

    def test_signals_preserve_shell_status_and_release_owned_group(self):
        result = subprocess.run([sys.executable, str(SCRIPTS / "run-bounded.py"), "5", sys.executable,
                                 "-c", "import os,signal; os.kill(os.getpid(),signal.SIGTERM)"],
                                timeout=12, capture_output=True, text=True)
        self.assertEqual(result.returncode, 143, result.stderr)
        with tempfile.TemporaryDirectory() as temporary:
            pid_file, command = self.launch(Path(temporary), True)
            controller = subprocess.Popen([sys.executable, str(SCRIPTS / "run-bounded.py"), "30", *command],
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                await_file(Path(temporary) / "ready")
                controller.terminate()
                _, errors = controller.communicate(timeout=12)
                self.assertEqual(controller.returncode, 143, errors)
                self.assert_stopped(int(pid_file.read_text()))
            finally:
                if controller.poll() is None:
                    controller.kill()
                    controller.communicate()
                self.cleanup(pid_file)



if __name__ == '__main__':
    unittest.main()
