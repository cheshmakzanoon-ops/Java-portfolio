#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
backtrace_prepare
mkdir -p "$backtrace_runtime"
for kind in cpu allocation contention virtual; do
  for variant in baseline regressed; do
    run="$backtrace_runtime/$kind-$variant"
    mkdir -p "$run"
    regression=false;[[ "$variant" != regressed ]] || regression=true
    timeout 30 java -Xms256m -Xmx256m -cp "$backtrace_classpath" io.github.cheshmakzanoon.portfolio.backtrace.workloads.WorkloadMain "$kind" "$regression" 1 4 jfr "$run/measurement.json" "$run/recording.jfr"
    backtrace_cli analyze "$run/recording.jfr" "$run/report"
  done
  backtrace_cli compare "$backtrace_runtime/$kind-baseline/report/report.json" "$backtrace_runtime/$kind-regressed/report/report.json" "$backtrace_runtime/$kind-differential.html"
done
python3 - "$backtrace_runtime" <<'PY'
import json,sys
from pathlib import Path
p=Path(sys.argv[1]); cpu=json.load(open(p/'cpu-regressed/report/report.json'))
assert cpu['sampleCount']>0
assert any('plantedCpuHotspot' in k for k in cpu['profile']['methods'])
a=json.load(open(p/'allocation-regressed/report/report.json'));assert a['profile']['allocations'],'allocation events absent'
c=json.load(open(p/'contention-regressed/report/report.json'));assert any('PlantedLock' in k for k in c['profile']['monitors']),'planted monitor cause absent'
for kind in ['cpu','allocation','contention','virtual']:assert (p/f'{kind}-differential.html').stat().st_size>1000
print('Demo assertions passed. Open offline report:',p/'cpu-differential.html')
PY
if [[ ${BACKTRACE_OPEN_REPORT:-0} == 1 ]]; then
  if command -v xdg-open >/dev/null; then xdg-open "$backtrace_runtime/cpu-differential.html" >/dev/null 2>&1 || true; elif command -v open >/dev/null; then open "$backtrace_runtime/cpu-differential.html"; fi
fi
