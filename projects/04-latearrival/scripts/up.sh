#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
exec 9>"$PULSE_RUNTIME/lifecycle.lock"
flock -x 9
python3 "$PULSE_PROCESS_HELPER" validate "$PULSE_PROCESS_RECORD" "$PULSE_SERVICE_JAR"
before_all="$(compose ps -a -q)"
before_running="$(compose ps --status running -q)"
new_process=false
rollback_startup() {
  local primary=$? current current_running id
  trap - EXIT
  trap '' INT TERM
  if ((primary != 0)); then
    if [[ "$new_process" == true ]]; then
      stop_service || echo 'Failed to stop newly started LateArrival service; inspect its process record.' >&2
    fi
    if current="$(compose ps -a -q)"; then
      if ! current_running="$(compose ps --status running -q)"; then
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
if owned_service_running; then
  require_running_build
else
  if curl --max-time 2 --fail --silent "http://127.0.0.1:${PULSE_HTTP_PORT:-18400}/health" >/dev/null 2>&1; then
    echo 'LateArrival HTTP port belongs to an untracked service; refusing to reuse it.' >&2
    exit 1
  fi
  ensure_build
fi
bounded 200 docker compose -f "$PULSE_PROJECT/infra/compose.yaml" up -d --no-recreate --wait --wait-timeout 180
create_topic pulse-live
create_topic pulse-live-results
if owned_service_running; then
  wait_for_service
  echo 'LateArrival current service healthy'
  exit 0
fi
new_process=true
PULSE_DB_PASSWORD=${PULSE_DB_PASSWORD:-pulse-local} python3 "$PULSE_PROCESS_HELPER" spawn "$PULSE_PROCESS_RECORD" "$PULSE_SERVICE_JAR" \
  --log "$PULSE_RUNTIME/service.log" -- java -Xmx512m -jar "$PULSE_SERVICE_JAR" \
  --pulse.db.url="jdbc:postgresql://127.0.0.1:${PULSE_SQL_PORT:-18401}/pulse" --pulse.db.user=pulse \
  --pulse.bootstrap="127.0.0.1:${PULSE_KAFKA_PORT:-18402}" --pulse.run=live --pulse.topic=pulse-live \
  --server.port="${PULSE_HTTP_PORT:-18400}"
wait_for_service
printf '%s\n' "$(fingerprint)" > "$PULSE_RUNTIME/services.fingerprint"
echo 'LateArrival healthy'
