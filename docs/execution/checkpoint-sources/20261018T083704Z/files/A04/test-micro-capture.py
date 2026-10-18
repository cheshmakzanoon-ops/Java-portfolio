#!/usr/bin/env python3
"""Pure control definitions only; never launch Java, subprocesses, Docker or benchmarks."""
import importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch,Mock
spec=importlib.util.spec_from_file_location('pulse_micro',Path(__file__).with_name('run-micro-capture.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def result():
 return [{'benchmark':'x.EngineBenchmark.freshBatch','mode':'thrpt','threads':1,'forks':3,'warmupIterations':2,'measurementIterations':3,'primaryMetric':{'scoreUnit':'ops/s','score':2,'rawData':[[1,2,3]]*3},'secondaryMetrics':{'gc.alloc.rate.norm':{'rawData':[[1,2,3]]*3}}}]
class Controls(unittest.TestCase):
 def test_exact_three_fork_numeric_fixture(self):self.assertEqual(3,m.validate_numeric(result())['forks'])
 def test_two_forks_rejected(self):
  value=result();value[0]['forks']=2
  with self.assertRaisesRegex(RuntimeError,'protocol'):m.validate_numeric(value)
 def test_primary_mean_mismatch_rejected(self):
  value=result();value[0]['primaryMetric']['score']=999
  with self.assertRaisesRegex(RuntimeError,'arithmetic'):m.validate_numeric(value)
 def test_missing_gc_samples_rejected(self):
  value=result();value[0]['secondaryMetrics']['gc.alloc.rate.norm']['rawData']=[[1]*3]*2
  with self.assertRaisesRegex(RuntimeError,'samples'):m.validate_numeric(value)
 def test_nonfinite_gc_rejected(self):
  value=result();value[0]['secondaryMetrics']['gc.alloc.rate.norm']['rawData']=[[float('nan')]*3]*3
  with self.assertRaisesRegex(RuntimeError,'Nonnumeric'):m.validate_numeric(value)
 def test_zero_allocation_rejected(self):
  value=result();value[0]['secondaryMetrics']['gc.alloc.rate.norm']['rawData']=[[0]*3]*3
  with self.assertRaisesRegex(RuntimeError,'positive allocation'):m.validate_numeric(value)
 def test_exactly_three_pid_files_required(self):
  with tempfile.TemporaryDirectory() as temp:
   p=Path(temp)
   for pid in [11,12,13]:(p/f'profile-{pid}.jfr').write_bytes(b'FLR\0'+bytes(2048))
   self.assertEqual({11,12,13},set(m.pid_recordings(p)))
   (p/'profile-14.jfr').write_bytes(b'FLR\0'+bytes(2048))
   with self.assertRaisesRegex(RuntimeError,'exactly3'):m.pid_recordings(p)
 def test_unexpanded_pid_placeholder_rejected(self):
  with tempfile.TemporaryDirectory() as temp:
   p=Path(temp)
   for pid in ['11','12','%p']:(p/f'profile-{pid}.jfr').write_bytes(b'FLR\0'+bytes(2048))
   with self.assertRaisesRegex(RuntimeError,'expanded PID'):m.pid_recordings(p)
 def test_invalid_header_rejected(self):
  with tempfile.TemporaryDirectory() as temp:
   p=Path(temp)
   for pid in [11,12,13]:(p/f'profile-{pid}.jfr').write_bytes(b'fake'+bytes(2048))
   with self.assertRaisesRegex(RuntimeError,'non-JFR'):m.pid_recordings(p)
 def test_unobserved_fork_pid_rejected_before_analyzer(self):
  with tempfile.TemporaryDirectory() as temp:
   p=Path(temp);(p/'jmh.json').write_text(json.dumps(result()))
   for pid in [11,12,13]:(p/f'profile-{pid}.jfr').write_bytes(b'FLR\0'+bytes(2048))
   r=m.Micro.__new__(m.Micro);r.phase_processes={};r.used_pids=set();r.official=Mock()
   with self.assertRaisesRegex(RuntimeError,'observed unique'):r.validate_jmh(p)
   r.official.assert_not_called()
 def test_no_reservation_does_not_construct_runner(self):
  with patch.dict(m.os.environ,{},clear=True),patch.object(m.sys,'argv',['run-micro-capture.py','not-a-real-receipt']),patch.object(m,'Micro') as constructor:
   self.assertEqual(2,m.main());constructor.assert_not_called()
 def test_wrong_new_source_is_not_old_study_identity(self):
  r=m.Micro.__new__(m.Micro);r.ready={'sourceRevision':'new'};r.inputs={};r.fingerprint=Mock(return_value='old')
  with self.assertRaisesRegex(RuntimeError,'source changed'):r.verify_inputs()
 def test_changed_artifact_not_restamped(self):
  with tempfile.TemporaryDirectory() as temp:
   p=Path(temp)/'artifact';p.write_text('changed');r=m.Micro.__new__(m.Micro);r.ready={'sourceRevision':'new'};r.fingerprint=Mock(return_value='new');r.inputs={str(p):'not-the-current-hash'}
   with self.assertRaisesRegex(RuntimeError,'input changed'):r.verify_inputs()
if __name__=='__main__':unittest.main(verbosity=2)
