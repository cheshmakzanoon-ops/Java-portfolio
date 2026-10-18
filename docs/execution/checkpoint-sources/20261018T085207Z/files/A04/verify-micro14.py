#!/usr/bin/env python3
"""Read-only Python verification of already completed root quiet14; no JVMs/tools launched."""
from pathlib import Path
import datetime,hashlib,json,math,re,statistics,time
ROOT=Path('/workspace/Java-portfolio');WORK=ROOT/'.work/A04';out=WORK/'capture-fix-20261018T083044Z/quiet14-review';out.mkdir(exist_ok=False)
summary_path=WORK/'micro-capture-runs/20261018T084151Z-706053/summary.json';outer_path=ROOT/'.work/coordinator/quiet-reference-14-pulsestream-micro/reservation.json';s=json.loads(summary_path.read_text());outer=json.loads(outer_path.read_text());start=time.monotonic()
def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for chunk in iter(lambda:stream.read(1048576),b''):h.update(chunk)
 return h.hexdigest()
assert outer['exitCode']==s['exitCode']==0 and outer['result']=='PASS' and s['status']=='CAPTURE_VERIFIED'
assert outer['remainingJavaProcesses']==outer['remainingApplicationContainers']==outer['otherJavaProcessesAtReservation']==outer['otherApplicationContainersAtReservation']==[]
assert len(outer['lockPaths'])==3 and len(s['phases'])==2 and s['verifiedForkRecordings']==6
assert s['sourceRevision']=='working-tree-sha256:bf8294af855673e08feb586894e30e1ed22f77ff065af8c2714c8ffd0e2e6ace'
assert s['teardown']=={'attempted':False,'reason':'No dependencies started/adopted'}
assert s['latestContainerInventory']['containers']==[]
input_bytes=0
for name,digest in s['preflight']['inputHashes'].items():assert sha(name)==digest,name;input_bytes+=Path(name).stat().st_size
assert s['preflight']['preparation']['methodCount']==15 and s['preflight']['preparation']['rootCheckerExitCode']==0
artifacts=[];variants=[];pids=set();commands=s['officialJfrCommands'];assert len(commands)==12
for record in commands:
 assert record['exitCode']==0 and record['terminationReason'] is None and record['wallSeconds']<=45 and record['combinedOutputBytes']<=1048576
 assert record['command'][:3]==['/workspace/.tools/jdk-25/bin/jfr','-J-Xmx256m','-J-XX:ActiveProcessorCount=1']
assert sum(record['wallSeconds'] for record in commands)<=180
for phase in s['phases']:
 assert phase['exitCode']==0 and len(phase['artifactDirectories'])==1
 directory=Path(phase['artifactDirectories'][0]);j=json.loads((directory/'jmh.json').read_text())[0]
 assert (j['forks'],j['warmupIterations'],j['measurementIterations'],j['threads'],j['mode'],j['primaryMetric']['scoreUnit'])==(3,2,3,1,'thrpt','ops/s')
 for name,metric in {'primary':j['primaryMetric'],**j['secondaryMetrics']}.items():
  rows=metric['rawData'];assert len(rows)==3 and all(len(row)==3 for row in rows),name
  assert all(isinstance(v,(int,float)) and math.isfinite(v) and v>=0 for row in rows for v in row),name
 forkmeans=[statistics.mean(row) for row in j['primaryMetric']['rawData']];assert math.isclose(statistics.mean(forkmeans),j['primaryMetric']['score'],rel_tol=1e-12)
 assert 'gc.alloc.rate.norm' in j['secondaryMetrics']
 files=sorted(directory.rglob('*.jfr'));assert len(files)==3 and len(phase['result']['pidRecordingProof'])==3
 proof=[]
 for entry in phase['result']['pidRecordingProof']:
  path=Path(entry['path']);pid=entry['pid'];assert re.fullmatch(r'profile-[1-9][0-9]*\.jfr',path.name) and path.name==f'profile-{pid}.jfr'
  assert path in files and pid not in pids and int(entry['observedStartTicks'])>0;pids.add(pid);assert sha(path)==entry['sha256']
  name=directory.name+'-'+str(pid);text=(summary_path.parent/(name+'-summary.stdout')).read_text();identity=json.loads((summary_path.parent/(name+'-identity.stdout')).read_text());values=[v['values'] for v in identity['recording']['events'] if v['type']=='jdk.JVMInformation'];assert values==entry['officialJvmInformation']
  assert values and all(int(v['pid'])==pid and 'org.openjdk.jmh.runner.ForkedMain' in v['javaArguments'] and 'settings=profile' in v['jvmArguments'] and 'dumponexit=true' in v['jvmArguments'] for v in values)
  events={m.group(1):int(m.group(2)) for m in re.finditer(r'^\s+(jdk\.\w+)\s+(\d+)\s+\d+\s*$',text,re.M)}
  assert events['jdk.ExecutionSample']>0 and events['jdk.ObjectAllocationSample']>0
  duration=int(re.search(r'^ Duration: (\d+) s',text,re.M).group(1));assert duration==entry['durationSecondsRounded'] and duration>=3
  proof.append({**entry,'recordingDurationSecondsRounded':duration,'selectedEventCounts':{key:events[key] for key in ['jdk.ExecutionSample','jdk.ObjectAllocationSample','jdk.GarbageCollection','jdk.CPULoad','jdk.DataLoss']}})
 for name,record in phase['artifacts'].items():
  assert Path(name).stat().st_size==record['bytes'] and sha(name)==record['sha256'];artifacts.append({'phase':phase['name'],'path':str(Path(name).relative_to(ROOT)),**record})
 variants.append({'phase':phase['name'],'directory':str(directory.relative_to(ROOT)),'jmh':j,'forkMeans':forkmeans,'sampleCVAcrossForkMeansPercent':100*statistics.stdev(forkmeans)/statistics.mean(forkmeans),'recordings':proof})
assert len(pids)==6
outputs=[]
for record in commands:
 for channel in ['stdout','stderr']:
  path=summary_path.parent/(record['name']+'.'+channel);outputs.append({'command':record['name'],'channel':channel,'path':str(path.relative_to(ROOT)),'bytes':path.stat().st_size,'sha256':sha(path)})
report={'schemaVersion':1,'classification':'separate-per-fork-capture-verification-pending-independent-review','sourceRevision':s['sourceRevision'],'originalStudySource':s['preflight']['originalStudySource'],'quiet14GitCheckpoint':outer['sourceRevision'],'reservation':s['reservation'],'terminalStatus':s['status'],'exitCode':0,'started':s['started'],'ended':s['ended'],'outerElapsedSeconds':outer['elapsedSeconds'],'summary':str(summary_path.relative_to(ROOT)),'summarySha256':sha(summary_path),'outerReservation':str(outer_path.relative_to(ROOT)),'outerReservationSha256':sha(outer_path),'allThreeLocks':outer['lockPaths'],'initialAndRemainingForeignJavaAppsEmpty':True,'dependencyStartup':False,'cleanup':s['teardown'],'preflightInputHashesVerified':len(s['preflight']['inputHashes']),'preflightBytesVerified':input_bytes,'rawArtifacts':artifacts,'rawArtifactBytes':sum(v['bytes'] for v in artifacts),'rawArtifactCount':len(artifacts),'officialCommands':commands,'officialOutputInventory':outputs,'officialCommandCount':12,'officialAnalyzerSeconds':sum(v['wallSeconds'] for v in commands),'uniqueForkPids':sorted(pids),'variants':variants,'verificationWallSeconds':time.monotonic()-start,'analysisMethod':'Python-only postprocessing: bounded streamingSHA,smallJSON and retained official-summary/identity text; no new Java/Maven/Docker/benchmark.','scope':'Capture-specific sourcebf8,distinct from quiet12source2f7. Launch-enabled recording includes JMHwarmup/measurement/shutdown after recorder initialization; initial JVMbootstrap can precede recording. Different profiling boundary; no oldrowreplacement or comparable-profiler causal claim. Prior3million durable results notrerun orrelabeled.','preparationByteProof':'Actual15method supported preparation passed;44classfiles+4mainresources+6JARbytes and129runtimehashes exactly equal oldstudy, with originalbaseline manifest unchanged.'}
(out/'capture-verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in ['variants','rawArtifacts','officialCommands','officialOutputInventory','allThreeLocks','scope']},indent=2))
for v in variants:print(v['phase'],'mean',v['jmh']['primaryMetric']['score'],'forkCV',v['sampleCVAcrossForkMeansPercent'],'pids',[p['pid'] for p in v['recordings']],'durations',[p['durationSecondsRounded'] for p in v['recordings']])
