#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
build
compose up -d --wait --wait-timeout 180
create_topic pulse-live
create_topic pulse-live-results
if [[ -f "$PULSE_WORK/runtime/service.pid" ]] && kill -0 "$(cat "$PULSE_WORK/runtime/service.pid")" 2>/dev/null; then
  echo 'PulseStream service already running'; exit 0
fi
PULSE_DB_PASSWORD=${PULSE_DB_PASSWORD:-pulse-local} java -Xmx512m -jar "$PULSE_PROJECT/service/target/pulsestream-service-1.0.0-SNAPSHOT-exec.jar" --pulse.db.url="jdbc:postgresql://127.0.0.1:${PULSE_SQL_PORT:-18401}/pulse" --pulse.db.user=pulse --pulse.bootstrap="127.0.0.1:${PULSE_KAFKA_PORT:-18402}" --pulse.run=live --pulse.topic=pulse-live --server.port="${PULSE_HTTP_PORT:-18400}" > "$PULSE_WORK/runtime/service.log" 2>&1 &
echo "$!" > "$PULSE_WORK/runtime/service.pid"
for ((i=0;i<60;i++)); do
  if curl --fail --silent "http://127.0.0.1:${PULSE_HTTP_PORT:-18400}/health" >/dev/null; then echo 'PulseStream healthy'; exit 0; fi
  sleep 1
done
cat "$PULSE_WORK/runtime/service.log" >&2
exit 1
