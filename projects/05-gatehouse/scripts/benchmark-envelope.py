"""Attach provenance to actual JMH output; refuse empty measurements."""
import json, os, pathlib, sys, math, datetime
raw=pathlib.Path(sys.argv[1]);data=json.loads(raw.read_text())
assert len(data)==2 and all(d.get('forks')==3 and d.get('primaryMetric') for d in data), 'JMH did not complete both three-fork benchmarks'
assert all(math.isfinite(d['primaryMetric']['score']) and d['primaryMetric']['score']>0 and d['primaryMetric']['scoreUnit']=='us/op' for d in data), 'JMH primary measurements must be finite positive microseconds'
assert all('gc.alloc.rate.norm' in d.get('secondaryMetrics',{}) and math.isfinite(d['secondaryMetrics']['gc.alloc.rate.norm']['score']) for d in data), 'Real allocation profiler measurements are required'
envelope={'schemaVersion':1,'project':'gatehouse','sourceRevision':os.environ['GATEHOUSE_SOURCE_FINGERPRINT'],'environment':'environment.json','workload':'cold-200-rule-100-role-compilation-and-warm-indexed-evaluation-v1','command':'projects/05-gatehouse/scripts/benchmark.sh','exitCode':0,'assertions':{'passed':2,'failed':0},'rawArtifact':raw.name,'measurements':{d['benchmark'].rsplit('.',1)[-1]:d['primaryMetric'] for d in data},'limitations':['Component timing only; authenticated HTTP results are separate.']}
envelope['timestamp']=datetime.datetime.now(datetime.timezone.utc).isoformat()
pathlib.Path(sys.argv[2]).write_text(json.dumps(envelope,indent=2)+'\n')
