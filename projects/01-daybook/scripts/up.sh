#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
exec 9>"$PROJECT/target/lifecycle.lock"
flock -x 9
python3 "$PROCESS_HELPER" validate "$API_RECORD" "$API_JAR"
before_all="$("${COMPOSE[@]}" ps -a -q)"
before_running="$("${COMPOSE[@]}" ps --status running -q)"
new_process=false
rollback_startup() {
 local primary=$? current current_running id
 trap - EXIT
 trap '' INT TERM
 if ((primary != 0)); then
  if [[ "$new_process" == true ]]; then
   stop_owned_api || echo 'Failed to stop newly started Daybook API; inspect its process record.' >&2
  fi
  if current="$("${COMPOSE[@]}" ps -a -q)"; then
   if ! current_running="$("${COMPOSE[@]}" ps --status running -q)"; then
    current_running=''
    echo 'Could not inspect running containers during startup rollback.' >&2
   fi
   while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    if ! python3 -c 'import sys;sys.exit(0 if sys.argv[1] in sys.argv[2].splitlines() else 1)' "$id" "$before_all"; then
     docker rm -f -- "$id" || echo "Failed to remove newly created container $id" >&2
    elif ! python3 -c 'import sys;sys.exit(0 if sys.argv[1] in sys.argv[2].splitlines() else 1)' "$id" "$before_running" \
     && python3 -c 'import sys;sys.exit(0 if sys.argv[1] in sys.argv[2].splitlines() else 1)' "$id" "$current_running"; then
     docker stop --time 30 -- "$id" || echo "Failed to restore stopped container $id" >&2
    fi
   done <<< "$current"
  else
   echo 'Could not inspect project containers during startup rollback.' >&2
  fi
 fi
 exit "$primary"
}
trap rollback_startup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if curl -fsS --max-time 2 http://127.0.0.1:18100/health >/dev/null 2>&1; then
 owned_api_running || { echo 'Port18100 belongs to an untracked API; refusing to reuse it.' >&2; exit 1; }
 prepare_classes
 current_api_running
 "${COMPOSE[@]}" up -d --no-recreate --wait --wait-timeout 90
 seed_demo
 echo 'Daybook current prepared API reused.'
 exit 0
fi
if owned_api_running; then echo 'Tracked API is alive but unhealthy; run down before starting again.' >&2; exit 1; fi
prepare_classes
"${COMPOSE[@]}" up -d --no-recreate --wait --wait-timeout 90
new_process=true
LEDGER_MIGRATE=true python3 "$PROCESS_HELPER" spawn "$API_RECORD" "$API_JAR" \
 --log "$PROJECT/target/api.log" -- java -Xmx256m -jar "$API_JAR" --spring.profiles.active=dev
for ((i=0;i<90;i++)); do
 if ! owned_api_running; then cat "$PROJECT/target/api.log" >&2; echo 'Daybook API exited before readiness.' >&2; exit 1; fi
 if curl -fsS --max-time 1 http://127.0.0.1:18100/health >/dev/null 2>&1; then seed_demo; echo 'Daybook ready: http://127.0.0.1:18100 (disposable dev JWT profile)'; exit 0; fi
 sleep 1
done
cat "$PROJECT/target/api.log" >&2
exit 1
