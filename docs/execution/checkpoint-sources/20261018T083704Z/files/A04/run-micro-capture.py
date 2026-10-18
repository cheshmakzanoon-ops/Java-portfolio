#!/usr/bin/env python3
"""Separate root-only capture verification. Never runs Maven, dependencies or durable workloads."""
from pathlib import Path
import datetime,fcntl,hashlib,importlib.util,json,math,os,re,selectors,signal,subprocess,sys,time
ROOT=Path(__file__).resolve().parents[2];WORK=ROOT/'.work/A04'
OLD_RUNNER=WORK/'run-quiet.py';OLD_RUNNER_SHA='3be9d084510d2156cb8d2c8d1cba1fda0628b807b6d529604e2a5a1da3a3baa8'
def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for chunk in iter(lambda:stream.read(1048576),b''):h.update(chunk)
 return h.hexdigest()
if sha(OLD_RUNNER)!=OLD_RUNNER_SHA:raise RuntimeError('Frozen quiet12 supervision module changed')
spec=importlib.util.spec_from_file_location('pulse_frozen_supervision',OLD_RUNNER);base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)

def validate_numeric(result):
 if (len(result)!=1 or not result[0]['benchmark'].endswith('EngineBenchmark.freshBatch')):raise RuntimeError('Unexpected benchmark')
 r=result[0]
 if (r['mode'],r['threads'],r['forks'],r['warmupIterations'],r['measurementIterations'],r['primaryMetric']['scoreUnit'])!=('thrpt',1,3,2,3,'ops/s'):raise RuntimeError('Changed benchmark protocol')
 for metric in [r['primaryMetric'],*[v for k,v in r['secondaryMetrics'].items() if k.startswith('gc.')]]:
  rows=metric.get('rawData',[])
  if len(rows)!=3 or any(len(row)!=3 for row in rows):raise RuntimeError('Missing3fork x3numeric samples')
  if any(not isinstance(n,(int,float)) or isinstance(n,bool) or not math.isfinite(n) or n<0 for row in rows for n in row):raise RuntimeError('Nonnumeric/nonfinite/negative sample')
 if 'gc.alloc.rate.norm' not in r['secondaryMetrics'] or r['primaryMetric']['score']<=0:raise RuntimeError('Missing GC allocation/throughput')
 if any(n<=0 for row in r['secondaryMetrics']['gc.alloc.rate.norm']['rawData'] for n in row):raise RuntimeError('Missing positive allocation samples')
 primary=r['primaryMetric']; samples=[n for row in primary['rawData'] for n in row]
 if any(n<=0 for n in samples) or not math.isclose(sum(samples)/len(samples),primary['score'],rel_tol=1e-10):raise RuntimeError('Primary score/sample arithmetic mismatch')
 return r

def pid_recordings(directory):
 files=sorted(Path(directory).rglob('*.jfr'))
 if len(files)!=3:raise RuntimeError('Expected exactly3PID-named recordings per variant')
 result={}
 for path in files:
  match=re.fullmatch(r'profile-([1-9][0-9]*)\.jfr',path.name)
  if not match:raise RuntimeError('Recording lacks expanded PID: '+path.name)
  pid=int(match.group(1))
  if pid in result:raise RuntimeError('Repeated recording PID')
  with path.open('rb') as stream:
   if path.stat().st_size<1024 or stream.read(4)!=b'FLR\0':raise RuntimeError('Empty/non-JFR recording')
  result[pid]=path
 return result

class Micro(base.Runner):
 def __init__(self,ready,reservation):
  self.ready_path=Path(ready).resolve();self.ready={};self.reservation=reservation
  self.output=WORK/'micro-capture-runs'/(datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+str(os.getpid()));self.output.mkdir(parents=True,exist_ok=False)
  self.summary={'classification':'separate-startup-recording-capture-verification','reservation':reservation,'started':base.now(),'status':'RUNNING','sourceRevision':None,'phases':[],
   'boundaries':['Three forks/variant,2x1s warmup,3x1s measurement and GC profiler preserved. Explicit JFR starts at fork JVM startup and includes warmup/shutdown; different from quiet12 built-in-profiler boundary.','No durable workloads or old-study relabeling. New score rows are separate capture observations, not same-profiler continuation of quiet12.','Official JFR validation uses256MiB/oneCPU/max45s/command180s total/1MiB combined output, sequential after each variant.','All3root build locks required; no other JVM/apps/containers; descendant supervision overhead included, not independently quantified.']}
  self.started_dependencies=False;self.active=None;self.phase_processes={};self.all_owned_processes={};self.last_active=None;self.container_ids=set();self.container_processes={};self.inputs={};self.used_pids=set();self.analyzer_seconds=0
  self.env=os.environ.copy();self.env['JAVA_HOME']='/workspace/.tools/jdk-25';self.env['PATH']=self.env['JAVA_HOME']+'/bin:'+self.env.get('PATH','');self.save()
 def verify_inputs(self):
  if self.fingerprint()!=self.ready['sourceRevision']:raise RuntimeError('New capture source changed')
  for path,digest in self.inputs.items():
   if not Path(path).is_file() or sha(path)!=digest:raise RuntimeError('Capture input changed: '+path)
 def preflight(self):
  self.ready=json.loads(self.ready_path.read_text());self.summary['sourceRevision']=self.ready['sourceRevision'];self.save()
  self.quiet();self.disk(3*base.GIB)
  old=json.loads(Path(self.ready['priorInputs']).read_text())
  if self.ready['sourceRevision']==old['sourceRevision']:raise RuntimeError('Capture script change must have honest new fingerprint')
  for name in ['build.fingerprint','classpath.fingerprint']:
   if (base.RUNTIME/name).read_text().strip()!=self.ready['sourceRevision']:raise RuntimeError('Stale supported preparation receipt')
  expected={**old['mainClassAndResourceHashes'],**old['projectJarHashes'],**old['preparedRuntimeInputHashes']}
  if self.ready['unchangedCompiledAndRuntimeInputs']!=expected or len(old['preparedRuntimeInputHashes'])!=129 or old['preservedClassCount']!=44 or len(old['projectJarHashes'])!=6:raise RuntimeError('Missing exact44class/resource6JAR129input equivalence')
  preparation=self.ready['preparation']
  if preparation['methodCount']!=15 or preparation['rootCheckerExitCode']!=0 or preparation['positiveCutoff']<=0:raise RuntimeError('Missing actual15method supported preparation gate')
  self.inputs={**expected,**self.ready['guardHashes'],str(self.ready_path):sha(self.ready_path),str(OLD_RUNNER):OLD_RUNNER_SHA,str(Path(self.ready['priorInputs'])):sha(self.ready['priorInputs'])}
  self.verify_inputs()
  self.summary['preflight']={'passed':True,'inputHashes':self.inputs,'preparation':preparation,'originalStudySource':old['sourceRevision'],'freeDiskBytes':self.disk(3*base.GIB)};self.save()
 def official(self,name,args):
  self.quiet();remaining=180-self.analyzer_seconds
  if remaining<=0:raise RuntimeError('Official JFR total180s deadline exhausted')
  limit=min(45,remaining);cmd=['/workspace/.tools/jdk-25/bin/jfr','-J-Xmx256m','-J-XX:ActiveProcessorCount=1',*args]
  record={'name':name,'command':cmd,'started':base.now(),'limitSeconds':limit};self.summary.setdefault('officialJfrCommands',[]).append(record);self.save()
  start=time.monotonic();child=subprocess.Popen(cmd,cwd=ROOT,env=self.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
  select=selectors.DefaultSelector();select.register(child.stdout,selectors.EVENT_READ,'stdout');select.register(child.stderr,selectors.EVENT_READ,'stderr');streams={k:(self.output/(name+'.'+k)).open('wb') for k in ['stdout','stderr']};total=0;reason=None
  try:
   while select.get_map():
    if time.monotonic()-start>limit:reason='TIMEOUT';break
    for key,_ in select.select(.1):
     data=os.read(key.fileobj.fileno(),16384)
     if not data:select.unregister(key.fileobj);continue
     accepted=data[:max(0,1048576-total)];streams[key.data].write(accepted);total+=len(accepted)
     if len(accepted)!=len(data):reason='OUTPUT_LIMIT';break
    if reason:break
   if reason:raise RuntimeError('Official JFR '+reason)
   child.wait(timeout=max(.1,limit-(time.monotonic()-start)))
   if child.returncode:raise RuntimeError('Official JFR nonzero '+str(child.returncode))
  finally:
   if child.poll() is None:
    os.killpg(child.pid,signal.SIGTERM)
    try:child.wait(timeout=1)
    except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=2)
   for stream in streams.values():stream.close()
   select.close();elapsed=time.monotonic()-start;self.analyzer_seconds+=elapsed
   record.update(ended=base.now(),wallSeconds=elapsed,exitCode=child.returncode,terminationReason=reason,combinedOutputBytes=total);self.save()
  self.quiet()
  return (self.output/(name+'.stdout')).read_text()
 def validate_jmh(self,artifact):
  r=validate_numeric(json.loads((artifact/'jmh.json').read_text()));recordings=pid_recordings(artifact);proof=[]
  for pid,path in recordings.items():
   if pid not in self.phase_processes or pid in self.used_pids:raise RuntimeError('Recording PID not an observed unique phase descendant: '+str(pid))
   self.used_pids.add(pid)
   name=artifact.name+'-'+str(pid)
   text=self.official(name+'-summary',['summary',str(path)])
   duration=re.search(r'^ Duration: (\d+) s',text,re.M)
   for event in ['jdk.ExecutionSample','jdk.ObjectAllocationSample']:
    count=re.search(r'^\s+'+re.escape(event)+r'\s+(\d+)',text,re.M)
    if not count or int(count.group(1))<=0:raise RuntimeError('No expected sampled JFR events: '+event)
   if not duration or int(duration.group(1))<3:raise RuntimeError('Recording too short for measured interval')
   data=json.loads(self.official(name+'-identity',['print','--json','--events','jdk.JVMInformation',str(path)]))
   identities=[event['values'] for event in data['recording']['events'] if event['type']=='jdk.JVMInformation']
   if not identities or any(int(value['pid'])!=pid or 'org.openjdk.jmh.runner.ForkedMain' not in value['javaArguments'] or 'settings=profile' not in value['jvmArguments'] for value in identities):raise RuntimeError('Official JFR fork PID/argument identity mismatch')
   proof.append({'pid':pid,'observedStartTicks':self.phase_processes[pid],'path':str(path),'sha256':sha(path),'durationSecondsRounded':int(duration.group(1)),'officialJvmInformation':identities})
  return {'primaryMetric':r['primaryMetric'],'recordings':[str(p) for p in recordings.values()],'pidRecordingProof':proof,'captureValidation':'3x3numeric primary/GC;3expandedPID files;observed unique forks;official summaries/JVM identities'}
 def run(self):
  exit_code=1
  try:
   self.preflight()
   old=self.phase('engine-original',['bash',str(WORK/'run-engine-baseline.sh')],660,'engine-baseline-*')
   current=self.phase('engine-current',['bash',str(base.PROJECT/'scripts/benchmark-engine.sh')],660,'engine-[0-9]*')
   if len(self.used_pids)!=6:raise RuntimeError('Expected6unique actual fork recordings')
   self.summary.update(status='CAPTURE_VERIFIED',completedMeasurementDirectories=[str(old),str(current)],verifiedForkRecordings=6);exit_code=0
  except BaseException as exc:self.summary.update(status='FAILED',error=type(exc).__name__+': '+str(exc));exit_code=130 if isinstance(exc,(base.Interrupted,KeyboardInterrupt)) else 1
  finally:
   signal.signal(signal.SIGTERM,signal.SIG_IGN);signal.signal(signal.SIGINT,signal.SIG_IGN)
   cleanup=self.teardown();self.summary['teardown']=cleanup
   if cleanup.get('attempted') and not cleanup.get('success'):exit_code=1;self.summary['status']='FAILED_TEARDOWN'
   self.summary.update(ended=base.now(),exitCode=exit_code);self.save();self.emit('MICRO FINAL '+str(exit_code)+' '+str(self.output/'summary.json'))
  return exit_code

def main():
 reservation=os.environ.get('PULSE_MICRO_RESERVATION','')
 if not re.fullmatch('[A-Za-z0-9_-]{1,80}',reservation) or len(sys.argv)!=2:
  print('Require new root all-lock micro reservation and exact MICRO_READY.json argument',file=sys.stderr);return 2
 with (WORK/'quiet-runner.lock').open('a') as lock:
  try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  except BlockingIOError:return 2
  def interrupted(sig,frame):raise base.Interrupted(signal.Signals(sig).name)
  signal.signal(signal.SIGTERM,interrupted);signal.signal(signal.SIGINT,interrupted)
  return Micro(sys.argv[1],reservation).run()
if __name__=='__main__':sys.exit(main())
