#!/usr/bin/env python3
"""Root-granted bounded official JFR semantic pass; separate from sealed quiet12 packet."""
from pathlib import Path
import datetime,json,os,selectors,signal,subprocess,time,hashlib
root=Path('/workspace/Java-portfolio');work=root/'.work/A04/postprocess-prep-quiet12'
output=work/('jfr-semantic-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'));output.mkdir(exist_ok=False)
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
started=time.monotonic();commands=[];meta={'classification':'root-authorized-background-semantic-analysis-during-functional-stress13','started':now(),'maxConcurrentAnalyzers':1,'maxHeapMiB':256,'activeProcessorCount':1,'maxSecondsPerCommand':45,'maxTotalSeconds':180,'maxCombinedOutputBytesPerCommand':1048576,'frozen19FilePacketChanged':False,'commands':commands}
def save(): (output/'activity.json').write_text(json.dumps(meta,indent=2)+'\n')
def run(name,args):
 remaining=180-(time.monotonic()-started)
 if remaining<=0:raise RuntimeError('Total180second analysis deadline exhausted; no more JVMs')
 command=['/workspace/.tools/jdk-25/bin/jfr','-J-Xmx256m','-J-XX:ActiveProcessorCount=1',*args]
 record={'name':name,'command':command,'started':now(),'allowedSeconds':min(45,remaining)};commands.append(record);save();begin=time.monotonic()
 child=subprocess.Popen(command,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
 sel=selectors.DefaultSelector();sel.register(child.stdout,selectors.EVENT_READ,'stdout');sel.register(child.stderr,selectors.EVENT_READ,'stderr');handles={k:(output/(name+'.'+k)).open('wb') for k in ['stdout','stderr']};size=0;reason=None
 try:
  while sel.get_map():
   if time.monotonic()-begin>record['allowedSeconds']:reason='TIMEOUT';break
   for key,_ in sel.select(.1):
    data=os.read(key.fileobj.fileno(),16384)
    if not data:sel.unregister(key.fileobj);continue
    accepted=data[:max(0,1048576-size)];handles[key.data].write(accepted);size+=len(accepted)
    if len(accepted)!=len(data):reason='OUTPUT_LIMIT';break
   if reason:break
  if reason:
   os.killpg(child.pid,signal.SIGTERM)
   try:child.wait(timeout=1)
   except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL)
  child.wait(timeout=max(.1,record['allowedSeconds']-(time.monotonic()-begin)))
 except BaseException:
  if child.poll() is None:
   os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=2)
  raise
 finally:
  for handle in handles.values():handle.close()
  sel.close()
  record.update(ended=now(),wallSeconds=time.monotonic()-begin,exitCode=child.returncode,terminationReason=reason,combinedOutputBytes=size)
  record['outputs']={k:{'path':str((output/(name+'.'+k)).relative_to(root)),'sha256':hashlib.sha256((output/(name+'.'+k)).read_bytes()).hexdigest()} for k in handles};save()
 return record
try:
 help_result=run('help-view',['--help','view'])
 if help_result['exitCode']!=0:raise RuntimeError('Actual JFR help failed; preserve nonzero, no broad fallback')
 summary=json.loads((root/'.work/A04/quiet-runs/20261018T070840Z-405639/summary.json').read_text())
 for phase in summary['phases']:
  recordings=[p for p in phase.get('artifacts',{}) if p.endswith('.jfr')]
  if not recordings:continue
  assert len(recordings)==1
  recording=recordings[0]
  for view in ['summary','hot-methods','allocation-by-site']:
   args=['summary',recording] if view=='summary' else ['view','--width','180','--cell-height','3',view,recording]
   run(phase['name']+'-'+view,args)
 meta['status']='COMPLETED' if all(c['exitCode']==0 and not c['terminationReason'] for c in commands) else 'COMPLETED_WITH_NONZERO'
except BaseException as exc:meta.update(status='FAILED',error=type(exc).__name__+': '+str(exc))
finally:
 meta.update(ended=now(),wallSeconds=time.monotonic()-started);save();print(json.dumps({'activity':str(output/'activity.json'),'status':meta['status'],'commands':len(commands),'wallSeconds':meta['wallSeconds']}))
