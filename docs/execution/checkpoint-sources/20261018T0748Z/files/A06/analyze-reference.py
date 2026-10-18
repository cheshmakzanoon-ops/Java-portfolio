from pathlib import Path
from collections import Counter
import csv,hashlib,json,re,statistics,subprocess
repo=Path('/workspace/Java-portfolio'); project=repo/'projects/06-matchcore'; batch=project/'.runtime/reference.2lkNWp'; out=project/'evidence/reference-20261018';out.mkdir(exist_ok=True)
raw=json.loads((batch/'reference-results.json').read_text())
expected='62fc0b0351864486ffa736bd64f444393e3dda894f93677c7ca95448dab1ce83'
assert subprocess.check_output(['python3',str(project/'scripts/source-fingerprint.py')],text=True).strip()==expected

def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()

def ident(p):return {'path':str(p.relative_to(repo)),'bytes':p.stat().st_size,'sha256':sha(p)}

def stats(v):return {'count':len(v),'minimum':min(v),'median':statistics.median(v),'maximum':max(v),'mean':statistics.mean(v),'sampleStddev':statistics.stdev(v),'sampleCoefficientOfVariationPercent':100*statistics.stdev(v)/statistics.mean(v) if statistics.mean(v) else None}

resource_rows=[];jfr_rows=[];integrity=[];environments=[];csvrows=[]
for item in raw['runs']:
 mode=item['mode'];rep=item['repetition'];d=Path(item['rawDirectory']);r=json.loads((d/'result.json').read_text());assert r==item['result']
 e=json.loads((d/'environment.json').read_text());environments.append(e)
 c=json.loads((d/'capture-summary.json').read_text());u=json.loads((d/'resource.json').read_text())
 assert e['sourceFingerprint']==expected and e['classification']=='quiet-baseline'
 assert e['mavenJvmsBeforeLaunch']==e['otherJavaJvmsBeforeLaunch']==0
 assert c['exitCode']==u['exitCode']==int((d/'exit-code.txt').read_text())==0
 assert r['commands']==1000000 and r['initializationCommands']==64 and r['accounts']==64 and r['duplicateInjections']==1000
 assert r['mix']=={'NEW':60,'CANCEL':25,'REPLACE':15} and r['openOrderCap']==100000
 assert sum(r['businessResultCounts'].values())==1000000
 assert r['activeTargetSelections']+r['emptyTargetSelections']==400000
 assert r['measuredForceCalls']==(1000000 if mode=='durable' else 0)
 files=[]
 for name,expected_file in c['rawFiles'].items():
  p=d/name;actual=ident(p);assert actual['sha256']==expected_file['sha256'] and actual['bytes']==expected_file['bytes'],p
  files.append(actual)
 # result.json is generated after capture-summary's manifest pass; bind it separately.
 files += [ident(d/'result.json'),ident(d/'capture-summary.json')]
 integrity.append({'mode':mode,'repetition':rep,'rawDirectory':str(d.relative_to(repo)),'files':files})
 resource_rows.append({'mode':mode,'repetition':rep,**u})
 pauses=[];pause_kinds=Counter();fullgc=[]
 for p in sorted(d.glob('gc.log*')):
  for line in p.read_text().splitlines():
   m=re.search(r'\[gc\s*\].*GC\(\d+\) (Pause .+?)\s+(\d+(?:\.\d+)?)ms\s*$',line)
   if m:
    pauses.append(float(m[2]));pause_kinds[m[1].split(')')[0]+')' if ')' in m[1] else m[1]]+=1
    if m[1].startswith('Pause Full'):fullgc.append(float(m[2]))
 jfr_rows.append({'mode':mode,'repetition':rep,'boundary':'Whole JVM; setup, measured loop, verification/digest and durable recovery included','jfrSampleCounts':c.get('jfrSampleCounts',{}),'sampledCpuTopFrames':c.get('sampledCpuTopFrames',[]),'allocationSampleWeightsByClass':c.get('allocationSampleWeightsByClass',[]),'gcLog':{'collector':'G1','completedPauseEvents':len(pauses),'sumPauseMillis':sum(pauses),'maximumPauseMillis':max(pauses) if pauses else None,'fullPauseCount':len(fullgc),'pauseKinds':dict(pause_kinds)}})
 csvrows.append({'mode':mode,'repetition':rep,**{k:r[k] for k in ['commands','elapsedNanos','commandsPerSecond','p50Nanos','p95Nanos','p99Nanos','allocationBytesPerCommand','usedHeapBytes','recoveryNanos','processCpuNanos','gcCollections','gcCollectionMillis','measuredForceCalls','forceFrequencyHz','peakObservedQueue','peakOpenOrders','finalOpenOrders']},'wholeProcessWallNanos':u['wallNanos'],'wholeProcessUserCpuSeconds':u['userCpuSeconds'],'wholeProcessSystemCpuSeconds':u['systemCpuSeconds'],'wholeProcessMaximumResidentSetBytes':u['maximumResidentSetBytes'],'wholeProcessMaximumGcPauseMillis':max(pauses) if pauses else None})
assert len(raw['runs'])==6
assert len({x['result']['finalDigest'] for x in raw['runs']})==1
assert len({json.dumps(x['result']['businessResultCounts'],sort_keys=True) for x in raw['runs']})==1
j=Path(raw['jmhDirectory']);jmh=json.loads((j/'results.json').read_text());assert len(jmh)==1 and jmh[0]['forks']==3
assert int((j/'exit-code.txt').read_text())==0
je=json.loads((j/'environment.json').read_text());assert je['sourceFingerprint']==expected and je['mavenJvmsBeforeLaunch']==je['otherJavaJvmsBeforeLaunch']==0
jm=json.loads((j/'capture-summary.json').read_text());jf=[]
for name,ex in jm['rawFiles'].items():
 act=ident(j/name);assert act['sha256']==ex['sha256'] and act['bytes']==ex['bytes'];jf.append(act)
integrity.append({'mode':'jmh','rawDirectory':str(j.relative_to(repo)),'files':jf+[ident(j/'capture-summary.json')]})
summary={}
for mode in ['core','durable']:
 rows=[i['result'] for i in raw['runs'] if i['mode']==mode]
 summary[mode]={k:stats([r[k] for r in rows]) for k in ['elapsedNanos','commandsPerSecond','p50Nanos','p95Nanos','p99Nanos','allocationBytesPerCommand','usedHeapBytes','recoveryNanos','processCpuNanos','gcCollections','gcCollectionMillis']}
 summary[mode]['wholeProcessMaximumResidentSetBytes']=stats([r['maximumResidentSetBytes'] for r in resource_rows if r['mode']==mode])
record={'schemaVersion':1,'project':'matchcore','classification':'quiet-reference','sourceFingerprint':expected,'reservation':json.loads((repo/'.work/coordinator/quiet-reference-3-matchcore/reservation.json').read_text()),'rawBatch':str(batch.relative_to(repo)),'sourceEvidence':[ident(project/'evidence/prebaseline-verification.json'),ident(project/'evidence/runtime-artifact-cache.json')],'protocol':{'generatorVersion':'v2-live-identity-shuffled-block','seed':42006006,'commandsPerRun':1000000,'independentJvmRunsPerMode':3,'accounts':64,'mixPercent':{'NEW':60,'CANCEL':25,'REPLACE':15},'shuffleBlockSize':100,'duplicateInjectionsPerRun':1000,'duplicateInterval':1000,'initializationsOutsideLoop':64,'priceTicksUniformInclusive':[9900,10100],'quantityLotsUniformInclusive':[1,10],'timeInForce':'GTC','postOnly':False,'configuredOpenOrderCap':100000,'observedPeakOpenOrders':27,'finalOpenOrders':6,'activeTargetSelections':387308,'emptyTargetSelections':12692,'clientConcurrency':1,'batchSize':1,'systemExcludedWarmupCommands':0,'systemHeapFlags':['-Xms512m','-Xmx3g'],'jfr':'profile; maxsize256m; dumponexit','gc':'G1; unified GC+safepoint logs','retainedRunCommandCap':1100000},'allBusinessResultCounts':raw['runs'][0]['result']['businessResultCounts'],'allRunFinalDigest':raw['runs'][0]['result']['finalDigest'],'environment':environments[0],'runEnvironments':environments,'summary':summary,'resources':resource_rows,'samplingAndGc':jfr_rows,'integrity':integrity,'rawBatchResult':ident(batch/'reference-results.json'),'jmh':{'directory':str(j.relative_to(repo)),'environment':je,'resource':json.loads((j/'resource.json').read_text()),'rawResultFile':'jmh-results.json','forkPrimaryMeans':[statistics.mean(v) for v in jmh[0]['primaryMetric']['rawData']]},'limits':['Three repetitions with one fixed seed; no statistical population/performance threshold claim.','Core is memory-only; durable loop includes queue/force/publication and is not HTTP throughput.','Shallow observed book:27peakorders, not100000-depth stress.','Closed-loop one outstanding command; coordinated omission and no overload latency claim.','No excluded system-driver warmup beyond64INITsetup; JIT/GC effects remain in measured commands.','Acknowledgement percentiles are per-run samples; summaries are medians/ranges of percentiles, not pooled percentiles.','ThreadMXBean allocation deltas include driver/writer/background live threads and can miss terminated threads.','Used heap is a point-in-time loop-end observation; maxRSS covers the whole JVM and native memory.','Recovery is warm JVM/hot OS page cache Gateway constructor, excluding digest verification/close.','JFR CPU/allocation samples and GC-log summaries cover setup/loop/verification/recovery; no exact force latency or matching-only allocation attribution.','Overlay storage underlying device/cache/power-loss behavior is unknown; process-crash tests are not physical power-loss tests.','Host has four quota cores despite five visible processors; no unrelated Java/apps at reservation or launch, but cloud-neighbor interference is unobservable.','Clean-checkout and global release/remoteCI remain coordinator work.']}
(out/'reference-results.json').write_text(json.dumps({'schemaVersion':1,'sourceFingerprint':expected,'runs':raw['runs']},indent=2)+'\n')
(out/'jmh-results.json').write_bytes((j/'results.json').read_bytes())
(out/'measurement-envelope.json').write_text(json.dumps(record,indent=2)+'\n')
with (out/'system-runs.csv').open('w',newline='') as f:
 w=csv.DictWriter(f,fieldnames=list(csvrows[0]));w.writeheader();w.writerows(csvrows)
print(json.dumps({'summary':summary,'gc':[{k:r[k] for k in ['mode','repetition','jfrSampleCounts','gcLog']} for r in jfr_rows],'jmhForkMeans':record['jmh']['forkPrimaryMeans'],'copiedBytes':sum(p.stat().st_size for p in out.iterdir())},indent=2))
