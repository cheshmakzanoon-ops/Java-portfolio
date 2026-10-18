#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
PULSE_MODE=${1:-full}
[[ "$PULSE_MODE" == full || "$PULSE_MODE" == smoke ]] || { echo 'Usage: benchmark.sh [full|smoke]' >&2; exit 2; }
require_current_build
# Services/dependencies must be prepared before an isolated run; this command never rebuilds mid-measurement.
bounded 15 docker compose -f "$PULSE_PROJECT/infra/compose.yaml" exec -T postgres pg_isready -U pulse -d pulse >/dev/null
bounded 20 docker compose -f "$PULSE_PROJECT/infra/compose.yaml" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null
PULSE_RUN_ID="durable-$PULSE_MODE-$(date +%s)${BASHPID}"
PULSE_OUTPUT="$PULSE_RUNTIME/$PULSE_RUN_ID"
mkdir -p "$PULSE_OUTPUT"
python3 "$PULSE_PROJECT/scripts/capture-environment.py" "$PULSE_OUTPUT/environment.json"
PULSE_FINGERPRINT=$(fingerprint)
PULSE_DB_PASSWORD=${PULSE_DB_PASSWORD:-pulse-local} bounded 2100 java -Xmx512m -XX:StartFlightRecording="filename=$PULSE_OUTPUT/workload.jfr,settings=profile,dumponexit=true" -Xlog:"gc*,safepoint:file=$PULSE_OUTPUT/gc.log:time,uptime,level,tags" -Dpulse.sourceFingerprint="$PULSE_FINGERPRINT" -Dloader.main=io.github.cheshmakzanoon.portfolio.pulsestream.Workload -Dloader.path="$PULSE_VERIFY_JAR" -cp "$PULSE_SERVICE_JAR" org.springframework.boot.loader.launch.PropertiesLauncher "jdbc:postgresql://127.0.0.1:${PULSE_SQL_PORT:-18401}/pulse" "127.0.0.1:${PULSE_KAFKA_PORT:-18402}" "$PULSE_MODE" "$PULSE_OUTPUT/result.json" 2>&1 | tee "$PULSE_OUTPUT/console.log"
[[ "$PULSE_FINGERPRINT" == "$(fingerprint)" ]] || { echo 'Inputs changed during measurement; preserve raw run and repeat stable source' >&2; exit 1; }
printf '%s\n' "$PULSE_RUN_ID" > "$PULSE_RUNTIME/last-durable-run"
echo "Observed result and full-run JFR/GC: $PULSE_OUTPUT"
