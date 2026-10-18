#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
threadlens_prepare
export THREADLENS_CP="$threadlens_classpath"
export THREADLENS_PROJECT="$threadlens_project"
python3 - <<'PY'
import hashlib,json,os,random,subprocess,time
from pathlib import Path
p=Path(os.environ['THREADLENS_PROJECT']);out=p/'target/regression-trials';out.mkdir(parents=True,exist_ok=True)
cp=os.environ['THREADLENS_CP'];rng=random.Random(80842);results=[]
for kind in ['cpu','allocation','contention']:
 for pair in range(7):
  reports={};measurements={};order=['baseline','regressed'];rng.shuffle(order)
  for variant in order:
   run=out/f'{kind}-{pair}-{variant}';run.mkdir(parents=True,exist_ok=True)
   cmd=['java','-Xms256m','-Xmx256m','-XX:ActiveProcessorCount=4','-cp',cp,'io.github.cheshmakzanoon.portfolio.threadlens.workloads.WorkloadMain',kind,str(variant=='regressed').lower(),'1','4','jfr',str(run/'measurement.json'),str(run/'recording.jfr')]
   with open(run/'process.log','w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=40)
   subprocess.run(['java','-Xmx512m','-cp',cp,'io.github.cheshmakzanoon.portfolio.threadlens.cli.ThreadLensCli','analyze',str(run/'recording.jfr'),str(run/'report')],check=True,timeout=30,stdout=subprocess.DEVNULL)
   reports[variant]=json.load(open(run/'report/report.json'));measurements[variant]=json.load(open(run/'measurement.json'))
  r=reports['regressed']['profile'];b=reports['baseline']['profile']
  if kind=='cpu':
   top=sorted(r['methods'],key=lambda m:(-r['methods'][m]['self'],m))[:3];found=any('plantedCpuHotspot' in m for m in top);details={'topThreeObservedSelfMethods':top,'regressedSampleCount':r['sampleCount']}
  elif kind=='allocation':
   byte_keys=lambda profile:[k for k in profile['allocations'] if k in ['[B','byte[]']]
   weighted=lambda profile:sum(profile['allocations'][k]['observedWeightedBytes'] for k in byte_keys(profile))
   base,candidate=weighted(b),weighted(r);found=candidate>base and candidate>0;details={'baselineObservedWeightedByteArrays':base,'candidateObservedWeightedByteArrays':candidate}
  else:
   total=lambda profile:sum(v['totalNanos'] for k,v in profile['monitors'].items() if 'PlantedLock' in k)
   base,candidate=total(b),total(r);found=candidate>base and candidate>0;details={'baselineObservedPlantedMonitorNanos':base,'candidateObservedPlantedMonitorNanos':candidate}
  results.append({'workload':kind,'pair':pair,'randomizedOrder':order,'identified':found,'details':details,'measurements':measurements})
  (out/'trials.json').write_text(json.dumps(results,indent=2))
summary={kind:{'identified':sum(r['identified'] for r in results if r['workload']==kind),'trials':7} for kind in ['cpu','allocation','contention']}
summary['passed']=all(summary[k]['identified']>=6 for k in ['cpu','allocation','contention']);summary['scope']='P08-A2 planted-cause study; 1s warmup +4s measure, not overhead acceptance'
(out/'summary.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary,indent=2));assert summary['passed'],'planted cause identification failed; preserve trials and diagnose'
PY
