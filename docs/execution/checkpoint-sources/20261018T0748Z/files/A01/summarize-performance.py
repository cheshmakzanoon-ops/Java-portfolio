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
  source_revisions.add(r['sourceRevision'])
  assert r['exitCode']==0 and r['journals']==100000 and r['tenants']==20 and r['accounts']==10000 and r['accountsTouched']==10000 and r['tenantsTouched']==20
  assert all(v=='PASS' for v in r['assertions'].values())
  process=json.loads((d/'process.json').read_text());assert process['exitCode']==0
  r['wholeProcessCpuSeconds']=process['userCpuSeconds']+process['systemCpuSeconds'];r['peakProcessRssBytes']=process['peakProcessRssBytes']
  profile=json.loads((d/'profile-summary.json').read_text());assert profile['eventCounts']['cpuLoad']>0
  expected_hashes.add(hashlib.sha256((d/'expected-balances.csv').read_bytes()).hexdigest())
  runs.append(r);raw.append({'results':str(d/'results.json'),'latencies':str(d/'latencies.csv'),'jfr':str(d/'workload.jfr'),'profile':str(d/'profile-summary.json'),'sourceRevision':r['sourceRevision'],'timestamp':r['timestamp']})
 groups[str(writers)]={}
 for key in ['throughputJournalsPerSecond','p50Millis','p95Millis','p99Millis','transactionRetries','shadowReplaySeconds','peakSampledHeapBytes','wholeProcessCpuSeconds','peakProcessRssBytes']:
  values=[r[key] for r in runs];groups[str(writers)][key]={'runs':values,'mean':statistics.mean(values),'sampleStdev':statistics.stdev(values),'min':min(values),'max':max(values)}
assert len(source_revisions)==1,'mixed source revisions'
assert len(expected_hashes)==1,'independent expected balances changed between identical seeds'
obj={'schemaVersion':1,'project':'clearledger','recordedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'workload':'marketplace-v1','seed':20261017,'command':'projects/01-clearledger/scripts/benchmark.sh 100000 3','exitCode':0,'originalJournalsPerRun':100000,'successfulInputGroupsPerRun':100000,'failedInputGroupsPerRun':0,'sqlPostingOrReversalCallsPerRun':101100,'reversalsPerRun':100,'duplicateRetriesPerRun':1000,'rounds':3,'writers':[1,8,32],'expectedBalanceFileSha256':expected_hashes.pop(),'environment':str(base/'environment.json'),'measurements':groups,'rawArtifacts':raw,'limitations':['closed-loop JDBC boundary; not HTTP and not open-loop overload latency','fresh durable datasets without separated warmup','PostgreSQL and client share host','all runs include JFR profile recording overhead','lock waiters and heap are sampled; no inferred exact wait duration or RSS','reference-host observations are not production capacity guarantees']}
output=project/'evidence/performance-current.json';output.write_text(json.dumps(obj,indent=2)+'\n');print(output)
