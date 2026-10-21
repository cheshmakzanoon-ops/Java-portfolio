#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
# Prescribed workload. Run only when other builds/integration stacks are quiescent.
require_current_build
require_running_build
wait_for_services
export GATEHOUSE_SOURCE_FINGERPRINT="$(fingerprint)"
export GATEHOUSE_MIGRATION_URL=jdbc:postgresql://127.0.0.1:18503/gatehouse
export GATEHOUSE_MIGRATION_USER=migration
export GATEHOUSE_MIGRATION_PASSWORD=local-migration-only
CP="$(classpath)"
bounded 1800 java -Xmx512m -cp "$CP" io.github.cheshmakzanoon.portfolio.gatehouse.Workload seed "$RUNTIME_DIR/seed.json"
unset GATEHOUSE_MIGRATION_USER GATEHOUSE_MIGRATION_PASSWORD GATEHOUSE_MIGRATION_URL
rm -f "$PROJECT_DIR/evidence/jmh.json" "$PROJECT_DIR/evidence/jmh-envelope.json"
bounded 600 java -Xmx512m -cp "$CP" org.openjdk.jmh.Main '.*PolicyBenchmark.*' -rf json -rff "$PROJECT_DIR/evidence/jmh.json" -prof gc
python3 "$PROJECT_DIR/scripts/benchmark-envelope.py" "$PROJECT_DIR/evidence/jmh.json" "$PROJECT_DIR/evidence/jmh-envelope.json"
service_pid="$(service_pid decision-service)"
trap 'jcmd "$service_pid" JFR.stop name=gatehouse-benchmark >/dev/null 2>&1 || true' EXIT
for run in 1 2 3; do
  rm -f "$PROJECT_DIR/evidence/http-$run.json" "$PROJECT_DIR/evidence/http-server-$run.json"
  jcmd "$service_pid" JFR.start name=gatehouse-benchmark settings=profile filename="$RUNTIME_DIR/http-server-$run.jfr" dumponexit=true
  bounded 1100 java -Xmx512m -XX:StartFlightRecording=filename="$RUNTIME_DIR/http-$run.jfr",settings=profile,dumponexit=true -cp "$CP" io.github.cheshmakzanoon.portfolio.gatehouse.Workload http "$PROJECT_DIR/evidence/http-$run.json" 100000
  jcmd "$service_pid" JFR.stop name=gatehouse-benchmark
  java -Xmx256m -cp "$CP" io.github.cheshmakzanoon.portfolio.gatehouse.FlightSummary "$RUNTIME_DIR/http-server-$run.jfr" "$PROJECT_DIR/evidence/http-server-$run.json"
done
