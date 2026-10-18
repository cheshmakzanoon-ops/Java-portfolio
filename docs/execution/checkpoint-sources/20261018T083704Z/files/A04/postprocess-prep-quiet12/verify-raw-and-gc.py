#!/usr/bin/env python3
"""Bounded sequential read-only verification/GC-text summary; never parses JFR or starts Java."""
from pathlib import Path
import datetime,hashlib,json,re,time
root=Path('/workspace/Java-portfolio'); scratch=root/'.work/A04/postprocess-prep-quiet12'
summary_path=root/'.work/A04/quiet-runs/20261018T070840Z-405639/summary.json'
summary=json.loads(summary_path.read_text());assert summary['exitCode']==0 and summary['status']=='MEASUREMENTS_PASSED'
started=datetime.datetime.now(datetime.timezone.utc).isoformat(); clock=time.monotonic(); inventory=[];gc=[]
expected_bytes=sum(record['bytes'] for phase in summary['phases'] for record in phase.get('artifacts',{}).values())
assert expected_bytes<128*1024**2,expected_bytes
for phase in summary['phases']:
 for name,expected in phase.get('artifacts',{}).items():
  path=Path(name); assert path.resolve().is_relative_to(root/'.work/A04/runtime')
  assert path.stat().st_size==expected['bytes']
  h=hashlib.sha256()
  with path.open('rb') as stream:
   for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
  assert h.hexdigest()==expected['sha256'],name
  inventory.append({'phase':phase['name'],'path':str(path.relative_to(root)),**expected,'verified':True})
  if path.name=='gc.log':
   durations=[]; kinds={};last_uptime=0
   with path.open() as stream:
    for line in stream:
     uptime=re.search(r'\]\[([0-9.]+)s\]',line)
     if uptime:last_uptime=max(last_uptime,float(uptime.group(1)))
     match=re.search(r'\[gc\s*\]\s+GC\(\d+\) (Pause .*) ([0-9.]+)ms\s*$',line)
     if match:
      durations.append(float(match.group(2)));kind=' '.join(match.group(1).split()[:2]);kinds[kind]=kinds.get(kind,0)+1
   gc.append({'phase':phase['name'],'path':str(path.relative_to(root)),'completedPauseSummaryLines':len(durations),
              'sumPauseMillis':sum(durations),'maxPauseMillis':max(durations),'pauseKinds':kinds,'lastRecordedJvmUptimeSeconds':last_uptime,
              'boundary':'GC text covers full JVM lifetime, including preparation/validation/recovery; pause summary lines differ from drain-scoped MXBean collection-count/time semantics. Not a complete CPU cost or causal attribution.'})
value={'classification':'post-terminal-read-only-raw-verification','started':started,'ended':datetime.datetime.now(datetime.timezone.utc).isoformat(),
       'wallSeconds':time.monotonic()-clock,'sourceRevision':summary['sourceRevision'],'reservation':summary['reservation'],
       'rawFilesVerified':len(inventory),'rawBytesVerified':expected_bytes,'artifacts':inventory,'gcTextSummaries':gc,
       'postprocessingActivity':'CPU-light small JSON assembly and sequential SHA/GC text reads during root functional/ThreadLens stress window; all measurement phases already ended. No Java/JFR parser/Maven/Docker/new benchmarks.','jfrBoundary':'Only hashes/file sizes verified. JFR semantic parse remains pending root grant. One retained profile per engine; console repeats same path for all three forks, so no preserved per-fork JFR claim.'}
(scratch/'raw-verification.json').write_text(json.dumps(value,indent=2)+'\n'); print(json.dumps({k:v for k,v in value.items() if k not in ['artifacts','gcTextSummaries']},indent=2));print(json.dumps(gc,indent=2))
