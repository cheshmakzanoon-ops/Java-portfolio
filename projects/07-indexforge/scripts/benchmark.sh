#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
mkdir -p "$project_dir/.runtime"
run_data=$(mktemp -d "$project_dir/.runtime/benchmark-XXXXXX")
run_output="$project_dir/evidence/performance/$(basename "$run_data")"
export INDEXFORGE_TIMEOUT="${INDEXFORGE_TIMEOUT:-1800}"
run_cli benchmark "$run_data" "$run_output" "${INDEXFORGE_DOCUMENTS:-100000}" "${INDEXFORGE_SECONDS:-30}"

python3 - "$run_output/summary.json" "${INDEXFORGE_DOCUMENTS:-100000}" "${INDEXFORGE_SECONDS:-30}" <<'PY_GATE'
import json,sys
from pathlib import Path
path=Path(sys.argv[1]);documents=int(sys.argv[2]);seconds=int(sys.argv[3]);report=json.loads(path.read_text())
runs=report['runs'];eligible=documents==100000 and seconds>=30
valid=report['documents']==documents and report['heapMaxBytes']<=2147483648 and len(runs)==3 and all(r['requests']==seconds*30 and r['offeredRps']==30 and r['errors']==0 for r in runs)
target=eligible and valid and all(r['p95Ms']<200 for r in runs)
report['scriptGate']={'workloadAndErrorsPass':valid,'targetEligible':eligible,'p95TargetPass':target if eligible else None}
path.write_text(json.dumps(report,indent=2)+'\n')
if not valid:raise SystemExit('Benchmark workload/heap/request/error assertions failed; raw evidence preserved.')
if eligible and not target:raise SystemExit('Reference p95 target failed; raw evidence preserved.')
print('Benchmark workload assertions passed; '+('reference target passed.' if eligible else 'exploratory size/duration does not establish the reference target.'))
PY_GATE
