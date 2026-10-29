#!/usr/bin/env bash
# Replay a file of v1 events through the real event-time engine and explain each record.
# No Kafka, PostgreSQL or Docker. Usage: scripts/explain.sh events.jsonl [--partitions N] [--format json]
set -euo pipefail
PROJECT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ROOT=$(cd -- "$PROJECT/../.." && pwd)
if [[ -x /workspace/.tools/jdk-25/bin/java ]]; then source "$ROOT/scripts/cloud-env.sh"; fi
SERVICE_JAR="$PROJECT/service/target/latearrival-service-1.0.0-SNAPSHOT-exec.jar"
VERIFY_JAR="$PROJECT/verification/target/latearrival-verification-1.0.0-SNAPSHOT.jar"
if [[ ! -f $SERVICE_JAR || ! -f $VERIFY_JAR ]]; then
  echo "Build the project first: ./mvnw -f projects/04-latearrival/pom.xml -B -ntp verify" >&2
  exit 2
fi
exec java -Xmx256m \
  -Dloader.main=io.github.cheshmakzanoon.portfolio.latearrival.ExplainMain \
  -Dloader.path="$VERIFY_JAR" -cp "$SERVICE_JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
