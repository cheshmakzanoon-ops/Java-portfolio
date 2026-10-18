#!/usr/bin/env python3
"""Publish a small summary only after all nine prescribed runs actually completed."""
import datetime,hashlib,json,pathlib,statistics,sys
project=pathlib.Path(__file__).resolve().parents[1]
base=project/'target/benchmark';groups={};raw=[];expected_hashes=set();source_revisions=set();environment=json.loads((base/'environment.json').read_text())
for writers in [1,8,32]:
 runs=[]
 for round_ in [1,2,3]:
  d=base/f'r{round_}-w{writers}';r=json.loads((d/'results.json').read_text())
  assert datetime.datetime.fromisoformat(r['timestamp'].replace('Z','+00:00'))>=datetime.datetime.fromisoformat(environment['capturedAtUtc']), 'run predates this environment capture'
  assert r['sourceRevision']==environment['sourceRevision'], 'run/environment source mismatch'
  source_revisions.add(r['sourceRevision'])
  assert r['exitCode']==0 and r['journals']==100000 and r['tenants']==20 and r['accounts']==10000 and r['accountsTouched']==10000 and r['tenantsTouched']==20
  assert all(v=='PASS' for v in r['assertions'].values())
  validated=json.loads((d/'validated-postconditions.json').read_text());assert validated['exitCode']==0 and validated['completedInputGroups']==100000 and validated['failedInputGroups']==0
  assert r['completedInputGroups']==100000 and r['failedInputGroups']==0 and r['clientRetryExhaustions']==0
  # Completed original, deliberate duplicate and reversal operations each invoke one store call;
  # actual extra client calls are measured by the driver, independent of internal SQL retries.
  r['actualClientCallAttempts']=101100+r['clientRetryAttempts']
  process=json.loads((d/'process.json').read_text());assert process['exitCode']==0
  r['wholeProcessCpuSeconds']=process['userCpuSeconds']+process['systemCpuSeconds'];r['peakProcessRssBytes']=process['peakProcessRssBytes']
  profile=json.loads((d/'profile-summary.json').read_text());assert profile['eventCounts']['cpuLoad']>0
  expected_hashes.add(hashlib.sha256((d/'expected-balances.csv').read_bytes()).hexdigest())
  runs.append(r);raw.append({'results':str(d/'results.json'),'latencies':str(d/'latencies.csv'),'postconditions':str(d/'validated-postconditions.json'),'process':str(d/'process.json'),'jfr':str(d/'workload.jfr'),'profile':str(d/'profile-summary.json'),'sourceRevision':r['sourceRevision'],'timestamp':r['timestamp']})
 groups[str(writers)]={}
 for key in ['throughputJournalsPerSecond','p50Millis','p95Millis','p99Millis','transactionRetries','temporaryClientResults','clientRetryAttempts','clientRetryExhaustions','actualClientCallAttempts','shadowReplaySeconds','peakSampledHeapBytes','wholeProcessCpuSeconds','peakProcessRssBytes']:
  values=[r[key] for r in runs];groups[str(writers)][key]={'runs':values,'mean':statistics.mean(values),'sampleStdev':statistics.stdev(values),'min':min(values),'max':max(values)}
assert len(source_revisions)==1,'mixed source revisions'
assert len(expected_hashes)==1,'independent expected balances changed between identical seeds'
obj={'schemaVersion':1,'project':'clearledger','sourceRevision':next(iter(source_revisions)),'recordedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'workload':'marketplace-v1','seed':20261017,'command':environment['launcherCommand'],'exitCode':0,'originalJournalsPerRun':100000,'successfulInputGroupsPerRun':100000,'failedInputGroupsPerRun':0,'plannedLogicalCallsPerRun':101100,'reversalsPerRun':100,'deliberateDuplicateRequestsPerRun':1000,'rounds':3,'writers':[1,8,32],'expectedBalanceFileSha256':expected_hashes.pop(),'environment':str(base/'environment.json'),'measurements':groups,'rawArtifacts':raw,'limitations':['actual client-call attempts = planned originals + deliberate duplicates + reversals + measured additional client calls; internal transaction retries are a separate counter, not an asserted SQL statement count','closed-loop JDBC boundary; not HTTP and not open-loop overload latency','fresh durable datasets without separated warmup','PostgreSQL and client share host','all runs include JFR profile recording overhead','lock waiters and Java heap are sampled; process RSS is a separate whole-child getrusage maximum','reference-host observations are not production capacity guarantees']}
output=project/'evidence/performance-current.json';output.write_text(json.dumps(obj,indent=2)+'\n');print(output)
