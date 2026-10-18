#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
source scripts/cloud-env.sh
project="$PWD/projects/06-matchcore"
expected="$(cat "$PWD/.work/A06/reference-source.sha256")"
assert_prepared() {
 actual="$(python3 "$project/scripts/source-fingerprint.py")"
 [[ "$actual" == "$expected" ]] || { echo 'Reference source changed; stop and reverify before quiet measurement.' >&2; exit 1; }
 [[ "$(cat "$project/benchmarks/target/verified-source.sha256")" == "$expected" ]] || { echo 'Prepared build stamp missing or stale; no Maven allowed in reference window.' >&2; exit 1; }
 python3 "$project/scripts/runtime-artifacts.py" check
}
assert_prepared
export MATCHCORE_PREPARED=1
batch="$(mktemp -d "$project/.runtime/reference.XXXXXX")"
printf '%s\n' "$batch" > .work/A06/reference-batch.txt
export MATCHCORE_MEASUREMENT_CLASS=quiet-baseline
export MATCHCORE_ISOLATION_NOTE='Coordinator reserved quiet host; no concurrent builds, stress workloads or services. JVM count guard is supplementary.'
export MATCHCORE_COMMANDS=1000000
for repetition in 1 2 3; do
 for mode in core durable; do
  assert_prepared
  export MATCHCORE_DATA="$batch/$mode-$repetition"
  mkdir -p "$MATCHCORE_DATA"
  "$project/scripts/benchmark.sh" "$mode" > "$batch/$mode-$repetition.out" 2> "$batch/$mode-$repetition.err"
 done
done
assert_prepared
export MATCHCORE_DATA="$batch/jmh"
mkdir -p "$MATCHCORE_DATA"
"$project/scripts/jmh.sh" > "$batch/jmh.out" 2> "$batch/jmh.err"
python3 - "$batch" <<'PY'
from pathlib import Path
import json,sys
p=Path(sys.argv[1]); runs=[]
for repetition in (1,2,3):
 for mode in ('core','durable'):
  dirs=list((p/f'{mode}-{repetition}').glob(f'benchmark.{mode}.*'))
  assert len(dirs)==1,dirs
  d=dirs[0]; r=json.loads((d/'result.json').read_text()); e=json.loads((d/'environment.json').read_text())
  assert int((d/'exit-code.txt').read_text())==0
  assert e['classification']=='quiet-baseline' and e['mavenJvmsBeforeLaunch']==e['otherJavaJvmsBeforeLaunch']==0
  assert r['commands']==1000000 and r['accounts']==64 and r['duplicateInjections']==1000 and r['openOrderCap']==100000
  assert r['mix']=={'NEW':60,'CANCEL':25,'REPLACE':15}
  assert sum(r['businessResultCounts'].values())==1000000
  assert r['activeTargetSelections']+r['emptyTargetSelections']==400000
  assert r['measuredForceCalls']==(1000000 if mode=='durable' else 0)
  runs.append({'mode':mode,'repetition':repetition,'rawDirectory':str(d),'result':r})
assert len({r['result']['finalDigest'] for r in runs})==1
assert len({json.dumps(r['result']['businessResultCounts'],sort_keys=True) for r in runs})==1
jmh=list((p/'jmh').glob('jmh.*')); assert len(jmh)==1
assert int((jmh[0]/'exit-code.txt').read_text())==0
j=json.loads((jmh[0]/'results.json').read_text()); assert j and all(r['forks']==3 for r in j)
(p/'reference-results.json').write_text(json.dumps({'schemaVersion':1,'runs':runs,'jmhDirectory':str(jmh[0])},indent=2)+'\n')
print(p)
PY
