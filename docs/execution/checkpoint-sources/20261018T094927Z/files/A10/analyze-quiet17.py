#!/usr/bin/env python3
"""Offline analysis of completed quiet17 raw files. Starts no application or measurement."""
from pathlib import Path
import collections
import datetime as dt
import hashlib
import json
import math
import re
import statistics

ROOT=Path('/workspace/Java-portfolio')
PROJECT=ROOT/'projects/10-routeweaver'
RAW=ROOT/'.work/A10/quiet/20261018T091915Z-6b912c'
MEASUREMENT=RAW/'measurement'
OUT=PROJECT/'evidence/performance-v1'
OUT.mkdir(exist_ok=False)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
relative=lambda p:str(p.relative_to(ROOT))
def write(name,obj):
    (OUT/name).write_text(json.dumps(obj,indent=2,allow_nan=False)+'\n')
def descriptive(values):
    return {'n':len(values),'minimum':min(values),'maximum':max(values),'mean':statistics.mean(values),'sampleStandardDeviation':statistics.stdev(values) if len(values)>1 else None,'values':values}
def score_key(s):
    return (s['hard'],s['unassignedPriority'],s['operatingCost']+s['disruption'])
manifest=json.loads((RAW/'raw-manifest.json').read_text())
for name,entry in manifest.items():
    f=RAW/name
    assert f.stat().st_size==entry['bytes'] and sha(f)==entry['sha256'],name
reservation_path=ROOT/'docs/execution/performance-reservations/017-routeweaver.json'
receipt=json.loads(reservation_path.read_text())
assert receipt['result']=='PASS' and receipt['independentStopped'] is True
assert receipt['reservation']['otherJavaProcessesAtReservation']==receipt['reservation']['remainingJavaProcesses']==[]
assert receipt['reservation']['otherApplicationContainersAtReservation']==receipt['reservation']['remainingApplicationContainers']==[]
ops=json.loads((RAW/'operations.json').read_text())
assert ops['status']=='PASS' and all(op['exitCode']==0 for op in ops['operations'])
source=receipt['sourceFingerprint'];assert source=='d5964723baa5e51619d4d85316f02ae3ccc370726ad7eb3b41617a200ced88d0'
assert ops['sourceRevision']=='working-tree-sha256:'+source
rows=json.loads((MEASUREMENT/'corpus-raw.json').read_text())
assert len(rows)==30 and {(r['instance'],r['run']) for r in rows}=={(i,r) for i in range(10) for r in range(3)}
log=(RAW/'before-down/service.log').read_text()
sessions=[]
for line in log.splitlines():
    if 'Solving started:' in line:
        sessions.append({'startedAtUtc':line.split()[0],'initialBestScore':re.search(r'best score \(([^)]+)\)',line).group(1)})
    elif 'Local Search phase (1) ended:' in line:
        sessions[-1].update(localSearchEndAtUtc=line.split()[0],localSearchSteps=int(re.search(r'step total \((\d+)\)',line).group(1)))
    elif 'Solving ended:' in line:
        sessions[-1].update(endedAtUtc=line.split()[0],finalBestScore=re.search(r'best score \(([^)]+)\)',line).group(1))
assert len(sessions)==27 and sum(s['localSearchSteps']==100000 for s in sessions)==6
compact=[];cursor=0
for r in rows:
    assert r['source']==r['serviceSource']==source
    assert (r['solverBudgetMillis'],r['solverStepLimit'],r['solverSeed'],r['seed'])==(30000,100000,42,4200+r['instance'])
    assert r['baseline']['hard']==r['optimized']['hard']==0 and score_key(r['optimized'])<=score_key(r['baseline'])
    trace=r['scoreTrace'];assert trace and trace[0]['score']==r['baseline'] and trace[-1]['score']==r['optimized']
    assert all(x['score']['hard']==0 for x in trace)
    assert all(b['elapsedNanos']>=a['elapsedNanos'] and score_key(b['score'])<=score_key(a['score']) for a,b in zip(trace,trace[1:]))
    if 'operatingCostImprovementFraction' in r:
        assert r['sameAssignedDemand']
        expected=(r['baseline']['operatingCost']-r['optimized']['operatingCost'])/r['baseline']['operatingCost']
        assert math.isclose(expected,r['operatingCostImprovementFraction'])
    result={k:v for k,v in r.items() if k!='scoreTrace'}
    result['traceAudit']={'points':len(trace),'allHardZero':True,'lexicographicallyNondegrading':True,'elapsedTimesNondecreasing':True,'baselineAndFinalEndpointsMatch':True,'firstElapsedNanos':trace[0]['elapsedNanos'],'lastElapsedNanos':trace[-1]['elapsedNanos']}
    if r['instance']==8:
        assert r['baselineNanos']>=30000*1e6 and r['baseline']==r['optimized']
        result['observedTermination']='baseline consumed cooperative wall budget; no Timefold session started'
    else:
        session=sessions[cursor];cursor+=1
        assert dt.datetime.fromisoformat(session['endedAtUtc'])<=dt.datetime.fromisoformat(r['timestamp'])
        result['timefoldSession']=session
        result['observedTermination']='Timefold Local Search step cap100000 reached' if session['localSearchSteps']==100000 else 'wall budget boundary; cooperative completion'
    if r['instance']==9:
        assert session['initialBestScore']=='0hard/0medium/-96106soft' and session['finalBestScore']=='0hard/0medium/-90120soft'
        result['pipelineAttribution']={'baselineOperatingScore':101101,'afterOriginalMovesOperatingScore':96106,'afterTimefoldOperatingScore':90120,'interpretation':'Total10.8614158% gain belongs to the combined pipeline; both original moves and Timefold contribute.'}
    compact.append(result)
assert cursor==27
instances=[]
for index in range(10):
    rs=[r for r in compact if r['instance']==index]
    gains=[r['operatingCostImprovementFraction'] for r in rs if 'operatingCostImprovementFraction' in r]
    instances.append({'instance':index,'difficulty':rs[0]['difficulty'],'jobs':rs[0]['jobs'],'vehicles':rs[0]['vehicles'],'seed':4200+index,'runs':[{'run':r['run'],'baseline':r['baseline'],'optimized':r['optimized'],'unassigned':r['unassigned'],'sameAssignedDemand':r['sameAssignedDemand'],'operatingCostImprovementFraction':r.get('operatingCostImprovementFraction'),'durableSolveSeconds':r['durableSolveNanos']/1e9,'baselineSeconds':r['baselineNanos']/1e9,'queueLatencyMilliseconds':r['queueLatencyNanos']/1e6,'observedTermination':r['observedTermination']} for r in rs],'durableSolveSeconds':descriptive([r['durableSolveNanos']/1e9 for r in rs]),'operatingCostImprovementFraction':descriptive(gains) if gains else None,'variabilityCaveat':'Three outcomes are descriptive run variability, not a tail-latency estimate. Run0 is retained separately; no excluded system warmup.'})
write('system-runs.json',{'schemaVersion':1,'sourceFingerprint':source,'rows':compact,'instances':instances,'traceAudit':{'points':sum(len(r['scoreTrace']) for r in rows),'rows':30,'nondegradationFailures':0,'hardViolationPoints':0,'endpointMismatches':0,'timeOrderFailures':0}})
jmh=[]
for r in json.loads((MEASUREMENT/'validation-jmh.json').read_text()):
    assert r['forks']==3 and r['warmupIterations']==r['measurementIterations']==3 and r['warmupTime']==r['measurementTime']=='1 s'
    raw=r['primaryMetric']['rawData'];assert len(raw)==3 and all(len(f)==3 for f in raw)
    jmh.append({'benchmark':r['benchmark'],'jobs':int(r['params']['jobs']),'mode':r['mode'],'threads':r['threads'],'forks':r['forks'],'warmupIterations':r['warmupIterations'],'measurementIterations':r['measurementIterations'],'iterationTime':'1 s','jmhVersion':r['jmhVersion'],'jdkVersion':r['jdkVersion'],'jvmArgs':r['jvmArgs'],'primaryMetric':r['primaryMetric'],'forkMeans':descriptive([statistics.mean(f) for f in raw]),'allocationBytesPerOperation':r['secondaryMetrics']['gc.alloc.rate.norm'],'gcProfilerSecondaryMetrics':r['secondaryMetrics'],'gcProfilerSampleCountsByFork':{name:[len(f) for f in metric['rawData']] for name,metric in r['secondaryMetrics'].items()}})
assert len(jmh)==6 and {(r['benchmark'].rsplit('.',1)[-1],r['jobs']) for r in jmh}=={(m,n) for m in ('fullReferenceValidation','immutableProblemImportValidation') for n in (100,250,500)}
write('jmh.json',{'schemaVersion':1,'sourceFingerprint':source,'rows':jmh,'interpretation':'Throughput operations/second on complete independently feasible fixture routes or full strict JSON/model/hash import. JMH-generated scorePercentiles are distribution descriptors of iteration throughput, not request latency percentiles.'})
samples=[json.loads(s) for s in (MEASUREMENT/'server-resources.jsonl').read_text().splitlines()]
assert len(samples)==811
span=(samples[-1]['monotonicNanos']-samples[0]['monotonicNanos'])/1e9
user=samples[-1]['userCpuSeconds']-samples[0]['userCpuSeconds'];system=samples[-1]['systemCpuSeconds']-samples[0]['systemCpuSeconds']
gc={}
for name in ('server-gc.log','gc.log'):
    pauses=[];types=collections.Counter();lines=(MEASUREMENT/name).read_text().splitlines()
    for line in lines:
        match=re.search(r' ([0-9.]+)ms$',line)
        if ' Pause 'in line and match:
            pauses.append(float(match.group(1)))
            kind=next((t for t in ('Young','Full','Remark','Cleanup') if 'Pause '+t in line),'Other');types[kind]+=1
    gc[name]={'scope':'entire preserved service log, including startup and JMH-idle period before system resource sampling' if name=='server-gc.log' else 'system corpus client only; excludes JMH fork GC profiler','collector':'G1','pauseCount':len(pauses),'pauseTypes':dict(types),'totalPauseMilliseconds':sum(pauses),'maximumPauseMilliseconds':max(pauses),'fullPauseCount':types['Full'],'rawSha256':sha(MEASUREMENT/name)}
resources={'schemaVersion':1,'sourceFingerprint':source,'sampleCount':len(samples),'sampledSpanSeconds':span,'userCpuSecondsDelta':user,'systemCpuSecondsDelta':system,'totalCpuSecondsDelta':user+system,'averageCpuCoresOverSampledSpan':(user+system)/span,'quotaCores':4,'rssBytes':{'minimum':min(s['rssBytes'] for s in samples),'maximumSampled':max(s['rssBytes'] for s in samples),'maximumKernelHighWater':max(s['highWaterRssBytes'] for s in samples)},'threads':{'minimum':min(s['threads'] for s in samples),'maximum':max(s['threads'] for s in samples)},'gc':gc,'limits':['The sampler covers the service process during the system corpus; PostgreSQL, client and JMH fork memory are excluded.','Sampled RSS and kernel high-water include native/JIT/thread memory, not only Java heap.','CPU includes worker/JIT/GC/service tasks and is not attributed solely to solver work.','Server GC log scope exceeds resource sampling scope; pause fraction of sampled time is not reported.','No resource or request p95/p99 is inferred from three solver outcomes.']}
write('resources-gc.json',resources)
write('raw-manifest.json',{'schemaVersion':1,'originalManifestPath':relative(RAW/'raw-manifest.json'),'originalManifestSha256':sha(RAW/'raw-manifest.json'),'verifiedFiles':len(manifest),'rawBase':relative(RAW),'files':manifest})
(OUT/'environment.txt').write_text((MEASUREMENT/'environment.txt').read_text())
attachments={p.name:{'bytes':p.stat().st_size,'sha256':sha(p)} for p in sorted(OUT.iterdir()) if p.is_file()}
artifacts=json.loads((RAW/'artifacts-used.json').read_text())
packet={'schemaVersion':1,'packetVersion':1,'project':'routeweaver','analyzedAtUtc':dt.datetime.now(dt.timezone.utc).isoformat(),'result':'OBSERVED_FULL_QUIET_PASS_REVIEW_PENDING','sourceFingerprint':source,'gitRevisionAtMeasurement':receipt['reservation']['sourceRevision'],'rootPomSha256':artifacts['rootPomSha256'],'artifacts':artifacts['artifacts'],'reservation':receipt['reservation'],'rootReceipt':{'path':relative(reservation_path),'sha256':sha(reservation_path),'independentStopped':receipt['independentStopped'],'provenance':'Root public receipt records actual check-stopped tool observation; no separate raw stop-check log was persisted or invented.'},'rawBase':relative(RAW),'rawFiles':{relative(RAW/name):{'bytes':(RAW/name).stat().st_size,'sha256':sha(RAW/name)} for name in ('operations.json','validated-summary.json','raw-manifest.json','artifacts-used.json')},'attachments':attachments,'correctnessScope':{'methods':54,'ordinaryMethods':40,'postgresqlMethods':14,'selectedValidatorMutantsKilled':82,'selectedValidatorMutantsGenerated':82,'evidence':'../integration.json and ../mutation.json remain unchanged under their own actual test timestamp; workload does not broaden that mutation target.'},'summary':{'systemRows':30,'instances':10,'runsPerInstance':3,'feasibility':dict(collections.Counter(r['feasibility'] for r in rows)),'hardViolatingResults':0,'degradedSameInputResults':0,'tracePoints':sum(len(r['scoreTrace']) for r in rows),'nondegradingTraceRows':30,'jmhRows':6,'forksPerJmhRow':3,'primaryMeasurementsPerFork':3,'resourceSamples':len(samples),'timefoldSessions':27,'stepCapSessions':6,'baselineBudgetExhaustionRows':3,'durableSolveSeconds':{'minimum':min(r['durableSolveNanos']/1e9 for r in rows),'maximum':max(r['durableSolveNanos']/1e9 for r in rows)},'improvableFixture':{'instance':9,'sameAssignedDemandAllThree':True,'baselineOperatingScore':101101,'afterOriginalMovesOperatingScore':96106,'finalOperatingScoreEachRun':[90120,90120,90120],'improvementFractionEachRun':[r['operatingCostImprovementFraction'] for r in rows if r['instance']==9],'proposedTenPercentGoal':'met on this preregistered fixture in all three outcomes; not a general-routing or standalone Timefold/original-move guarantee'}},'configuration':{'solverSeed':42,'solverBudgetMillis':30000,'solverStepLimit':100000,'concurrency':1,'timefoldEnvironmentMode':'PHASE_ASSERT','timefoldMoveThreads':'NONE','algorithmVersion':rows[0]['algorithmVersion'],'objectiveVersion':rows[0]['objectiveVersion'],'objectiveWeights':{'meters':1,'seconds':1,'disruption':1000},'warmup':'No excluded system warmup; run0 retained. Fresh service; OS/database caches not flushed. JMH warmup3x1second per fork is separate.'},'thirdPartyCredit':[{'name':'Timefold Solver','version':'2.7.1','role':'Improvement engine, mutable planning API and solver infrastructure; original model/validator/moves/residual lifecycle surround it.','reference':'https://github.com/TimefoldAI/timefold-solver'},{'name':'OpenJDK JMH','version':'1.37','role':'Benchmark harness, fork/warmup/measurement statistics and GC profiler.','reference':'https://github.com/openjdk/jmh'},{'name':'Spring Boot/PostgreSQL/Jackson/OpenJDK Temurin','role':'HTTP/runtime, durable database, JSON mapping and JVM infrastructure; credited as libraries, not original product algorithms.'}],'limits':['All data is synthetic and directed; release1 has a shared declared depot and no cargo transfers.','Closed-loop concurrency1 and three runs per instance do not characterize overload/tail latency; no p95/p99 is invented.','The30-second budget is cooperative: full validation/JDBC operations complete, with observed maximum31.552836seconds.','Six early outcomes reached an actual100000-step Local Search cap; no run was padded to30seconds.','The500-job overconstrained baseline exhausted the budget and retained a partial valid incumbent in all three runs.','Combined pipeline improvement is credited to both original moves and Timefold; no standalone attribution or large-instance optimum is claimed.','JMH PHASE_ASSERT/service and synthetic fixtures bound portability; no production/customer-deployment claim.'],'remainingReleaseGates':['independent A03 packet review','root-owned fresh-checkout/global/CI/release certification']}
write('packet.json',packet)
print(json.dumps({'packet':relative(OUT/'packet.json'),'packetSha256':sha(OUT/'packet.json'),'files':attachments,'summary':packet['summary'],'resources':resources},indent=2))
