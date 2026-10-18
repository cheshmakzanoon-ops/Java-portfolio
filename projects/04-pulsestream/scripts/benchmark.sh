#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
PULSE_MODE=${1:-full}
[[ "$PULSE_MODE" == full || "$PULSE_MODE" == smoke ]] || { echo 'Usage: benchmark.sh [full|smoke]' >&2; exit 2; }
build
compose up -d --wait --wait-timeout 180
PULSE_RESULT="$PULSE_WORK/runtime/durable-$PULSE_MODE-$(date +%s).json"
PULSE_DB_PASSWORD=${PULSE_DB_PASSWORD:-pulse-local} java -Xmx512m -Dloader.main=io.github.cheshmakzanoon.portfolio.pulsestream.Workload -Dloader.path="$PULSE_PROJECT/verification/target/pulsestream-verification-1.0.0-SNAPSHOT.jar" -cp "$PULSE_PROJECT/service/target/pulsestream-service-1.0.0-SNAPSHOT-exec.jar" org.springframework.boot.loader.launch.PropertiesLauncher "jdbc:postgresql://127.0.0.1:${PULSE_SQL_PORT:-18401}/pulse" "127.0.0.1:${PULSE_KAFKA_PORT:-18402}" "$PULSE_MODE" "$PULSE_RESULT"
echo "Observed result: $PULSE_RESULT"
