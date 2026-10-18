#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
source scripts/cloud-env.sh
export DURABLEFLOW_PREPARED=1
project="$PWD/projects/02-durableflow"
capture="$PWD/.work/A02/lifecycle-$(date -u +%Y%m%dT%H%M%S)"
mkdir -p "$capture"
printf '%s\n' "$capture" > .work/A02/latest-lifecycle-path
step=0
run() {
  local label=$1; shift
  step=$((step+1))
  local start finish result
  start=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf 'Running %s at %s\n' "$label" "$start"
  set +e
  "$@" > "$capture/$label.log" 2>&1
  result=$?
  set -e
  finish=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  python3 - "$capture/steps.jsonl" "$label" "$start" "$finish" "$result" "$@" <<'PY'
import sys,json
with open(sys.argv[1],'a') as f:f.write(json.dumps(dict(step=sys.argv[2],started=sys.argv[3],finished=sys.argv[4],exitCode=int(sys.argv[5]),command=sys.argv[6:]))+'\n')
PY
  printf '%s exit %s at %s\n' "$label" "$result" "$finish"
  return "$result"
}
archive() {
  mkdir -p "$capture/runtime-$step"
  cp -a "$project/target/runtime/." "$capture/runtime-$step/" 2>/dev/null || true
  cp "$project/target/scenario-process.log" "$capture/" 2>/dev/null || true
}
finish() {
  local result=$?
  trap - EXIT
  archive
  ./scripts/portfolio.sh down durableflow > "$capture/cleanup-down.log" 2>&1 || result=1
  python3 "$project/scripts/prepared-build.py" idle > "$capture/cleanup-idle.log" 2>&1 || result=1
  printf '%s\n' "$result" > "$capture/exit-code.txt"
  printf 'Finite lifecycle capture: %s (exit %s)\n' "$capture" "$result"
  exit "$result"
}
trap finish EXIT
run prepared-cache python3 "$project/scripts/prepared-build.py" verify
run initial-reset ./scripts/portfolio.sh reset-demo durableflow
run first-up ./scripts/portfolio.sh up durableflow
cp "$project"/target/runtime/*.pid "$capture/"
run repeated-up ./scripts/portfolio.sh up durableflow
python3 - "$project" "$capture" <<'PY'
import pathlib,sys
p,c=map(pathlib.Path,sys.argv[1:]);files=list(c.glob('*.pid'));assert len(files)==7,len(files)
for f in files:
 current=p/'target/runtime'/f.name;assert f.read_text()==current.read_text(),f.name
 pid=int(f.read_text());cmd=(pathlib.Path('/proc')/str(pid)/'cmdline').read_bytes();assert str(p).encode() in cmd,(f.name,pid)
print('Repeated up preserved all7 owned live process identities')
PY
run fault-demo-1 ./scripts/portfolio.sh demo durableflow
cp "$project/target/demo-result.json" "$capture/demo-1.json"
run smoke-100 "$project/scripts/benchmark.sh" --smoke
cp "$project/target/workload-100.json" "$capture/"
python3 - "$capture/workload-100.json" <<'PY'
import json,sys
v=json.load(open(sys.argv[1]));m=v['measurements'];assert v['exitCode']==0,v
for key,value in {'executions':100,'activities':800,'timerWaits':50,'independentProviderEffects':800,'expectedTransientFirstFailures':160}.items():assert m[key]==value,(key,m[key])
for key,value in {'completedActivities':800,'firedBusinessTimers':50,'acceptedUniqueSignals':100,'transientRejectedAttempts':160,'queuedAtEnd':0}.items():assert int(m[key])==value,(key,m[key])
print('Smoke100 independent provider/DB/timer/signal/rejection/queue assertions passed')
PY
archive
before=$(docker compose -f "$project/infra/compose.yaml" exec -T postgres psql -U durableflow -d durableflow -tAc 'SELECT count(*) FROM workflow_instance')
printf '%s\n' "$before" > "$capture/database-before-down-count.txt"
run first-down ./scripts/portfolio.sh down durableflow
run idle-after-down python3 "$project/scripts/prepared-build.py" idle
if curl --max-time 2 -fsS http://127.0.0.1:18200/health >/dev/null 2>&1; then echo 'API still available after down' >&2; exit 1; fi
if curl --max-time 2 -fsS http://127.0.0.1:18201/health >/dev/null 2>&1; then echo 'Provider still available after down' >&2; exit 1; fi
run persisted-up ./scripts/portfolio.sh up durableflow
after=$(docker compose -f "$project/infra/compose.yaml" exec -T postgres psql -U durableflow -d durableflow -tAc 'SELECT count(*) FROM workflow_instance')
[[ "$before" == 104 && "$after" == 104 ]] || { echo "Persistence count mismatch $before/$after" >&2; exit 1; }
printf '%s\n' "$after" > "$capture/database-after-up-count.txt"
run reset-demo ./scripts/portfolio.sh reset-demo durableflow
run reset-up ./scripts/portfolio.sh up durableflow
reset=$(docker compose -f "$project/infra/compose.yaml" exec -T postgres psql -U durableflow -d durableflow -tAc 'SELECT count(*) FROM workflow_instance')
[[ "$reset" == 0 ]] || { echo "Reset left workflow rows: $reset" >&2; exit 1; }
printf '%s\n' "$reset" > "$capture/database-after-reset-count.txt"
run fault-demo-2 ./scripts/portfolio.sh demo durableflow
cp "$project/target/demo-result.json" "$capture/demo-2.json"
run final-down ./scripts/portfolio.sh down durableflow
run final-idle python3 "$project/scripts/prepared-build.py" idle
