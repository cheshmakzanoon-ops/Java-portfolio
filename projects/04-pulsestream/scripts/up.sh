#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
ensure_build
bounded 200 docker compose -f "$PULSE_PROJECT/infra/compose.yaml" up -d --wait --wait-timeout 180
create_topic pulse-live
create_topic pulse-live-results
if owned_service_running; then
  if [[ -f "$PULSE_RUNTIME/services.fingerprint" ]] && [[ "$(cat "$PULSE_RUNTIME/services.fingerprint")" == "$(fingerprint)" ]]; then wait_for_service; echo 'PulseStream current service healthy'; exit 0; fi
  stop_service
fi
PULSE_DB_PASSWORD=${PULSE_DB_PASSWORD:-pulse-local} java -Xmx512m -jar "$PULSE_SERVICE_JAR" --pulse.db.url="jdbc:postgresql://127.0.0.1:${PULSE_SQL_PORT:-18401}/pulse" --pulse.db.user=pulse --pulse.bootstrap="127.0.0.1:${PULSE_KAFKA_PORT:-18402}" --pulse.run=live --pulse.topic=pulse-live --server.port="${PULSE_HTTP_PORT:-18400}" > "$PULSE_RUNTIME/service.log" 2>&1 &
echo "$!" > "$PULSE_RUNTIME/service.pid"
printf '%s\n' "$(fingerprint)" > "$PULSE_RUNTIME/services.fingerprint"
wait_for_service
echo 'PulseStream healthy'
