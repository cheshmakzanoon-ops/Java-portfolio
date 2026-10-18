#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
docker compose -f "$project/infra/compose.yaml" up -d --wait --wait-timeout 90
if [[ -f "$runtime/service.pid" ]] && kill -0 "$(cat "$runtime/service.pid")" 2>/dev/null; then
  pid=$(cat "$runtime/service.pid")
  if [[ ! -r "/proc/$pid/cmdline" ]] || ! tr '\0' ' ' < "/proc/$pid/cmdline" | rg -q 'routeweaver-service'; then
    echo "PID file identifies an unrelated process; refusing to signal it" >&2
    exit 1
  fi
  if [[ -f "$runtime/running-source" && $(cat "$runtime/running-source") == "$ROUTEWEAVER_SOURCE_REVISION" ]]; then health; exit; fi
  kill "$pid"
  for attempt in $(seq 1 20); do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid"; fi
fi
# Explicit trusted disposable development profile; service rejects a non-loopback binding.
export ROUTEWEAVER_AUTH_MODE=dev
export ROUTEWEAVER_DB_URL="jdbc:postgresql://127.0.0.1:${ROUTEWEAVER_DB_PORT:-19001}/routeweaver"
nohup "$JAVA_HOME/bin/java" -Xmx768m -Xlog:gc:file="$runtime/service-gc.log":time,uptime,level,tags -jar "$project/routeweaver-service/target/routeweaver-service-1.0.0-SNAPSHOT-exec.jar" > "$runtime/service.log" 2>&1 &
echo "$!" > "$runtime/service.pid"
printf '%s\n' "$ROUTEWEAVER_SOURCE_REVISION" > "$runtime/running-source"
health
printf 'RouteWeaver: http://127.0.0.1:%s (trusted loopback disposable dev profile)\n' "${ROUTEWEAVER_PORT:-19000}"
