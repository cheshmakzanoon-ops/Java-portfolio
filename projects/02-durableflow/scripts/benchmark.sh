#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
count=10000
if [[ ${1:-} == --smoke ]]; then count=100; elif [[ ${1:-} != '' ]]; then echo 'Usage: benchmark.sh [--smoke]' >&2; exit 2; fi
curl --max-time 5 -fsS "http://127.0.0.1:${PROVIDER_PORT:-18201}/health" >/dev/null
for n in 4 5 6 7 8; do launch "worker-$n" io.github.cheshmakzanoon.portfolio.durableflow.RuntimeMain worker "worker-$n"; done
"$PROJECT/scripts/record-environment.sh"
export RESULT_FILE="$PROJECT/target/workload-$count.json"
capture="$PROJECT/target/performance/$(date -u +%Y%m%dT%H%M%S)-$count-$$"
mkdir -p "$capture"
"$PROJECT/scripts/sample-resources.sh" & resource_monitor=$!
capture_evidence() {
  local outcome=$?
  trap - EXIT
  kill "$resource_monitor" 2>/dev/null || true
  wait "$resource_monitor" 2>/dev/null || true
  if [[ -n "${profile_pid:-}" ]]; then
    if ! jcmd "$profile_pid" JFR.stop name=durableflow > "$capture/jfr-stop.txt" 2>&1; then outcome=1; fi
    if [[ -s "$capture/worker-1.jfr" ]]; then
      jfr summary "$capture/worker-1.jfr" > "$capture/jfr-summary.txt" || outcome=1
      jfr print --json --events jdk.ObjectAllocationSample "$capture/worker-1.jfr" > "$capture/allocation-samples.json" || outcome=1
    else outcome=1
    fi
  fi
  printf '%s\n' "$outcome" > "$capture/exit-code.txt"
  if [[ -f "$RESULT_FILE" ]]; then
    python3 - "$RESULT_FILE" "$outcome" <<'PY3'
import json,sys,pathlib
p=pathlib.Path(sys.argv[1]);d=json.loads(p.read_text());d['exitCode']=int(sys.argv[2]);d['profilingCaptured']=int(sys.argv[2])==0;p.write_text(json.dumps(d,indent=2)+'\n')
PY3
  fi
  for artifact in "$RESULT_FILE" "$PROJECT/target/environment.json" "$PROJECT/target/resource-samples.jsonl"; do
    if [[ -f "$artifact" ]]; then cp "$artifact" "$capture/"; fi
  done
  mkdir -p "$capture/gc"
  find "$PROJECT/target/runtime" -maxdepth 1 -type f -name '*-gc.log*' -exec cp '{}' "$capture/gc/" \;
  echo "Run evidence: $capture (exit $outcome)"
  exit "$outcome"
}
trap capture_evidence EXIT
rm -f "$RESULT_FILE"
profile_pid=$(cat "$PROJECT/target/runtime/worker-1.pid")
jcmd "$profile_pid" JFR.start name=durableflow settings=profile "filename=$capture/worker-1.jfr" dumponexit=true > "$capture/jfr-start.txt"
timeout 2100 bash -c 'source "$1/scripts/common.sh"; run_main WorkloadMain "$2"' _ "$PROJECT" "$count"
python3 - "$RESULT_FILE" "$PROJECT/target/environment.json" "$count" <<'PY2'
import json,sys,pathlib
p=pathlib.Path(sys.argv[1]);d=json.loads(p.read_text());env=json.loads(pathlib.Path(sys.argv[2]).read_text());d.update(sourceRevision=env['sourceFingerprint'],environment='environment.json',command='projects/02-durableflow/scripts/benchmark.sh'+(' --smoke' if sys.argv[3]=='100' else ''),driverExitCode=0,assertions={'completedExecutions':int(sys.argv[3]),'failed':0},rawArtifact=p.name);p.write_text(json.dumps(d,indent=2)+'\n')
PY2
