#!/usr/bin/env bash
set -euo pipefail
PULSE_PROJECT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
PULSE_ROOT=$(cd -- "$PULSE_PROJECT/../.." && pwd)
PULSE_WORK="$PULSE_ROOT/.work/A04"
mkdir -p "$PULSE_WORK/runtime"
source "$PULSE_ROOT/scripts/cloud-env.sh"
compose() { docker compose -f "$PULSE_PROJECT/infra/compose.yaml" "$@"; }
build() { cd "$PULSE_ROOT"; ./mvnw -f "$PULSE_PROJECT/pom.xml" -Dmaven.repo.local="$PULSE_WORK/m2" -B -ntp verify; }
java_worker() {
  PULSE_DB_PASSWORD=${PULSE_DB_PASSWORD:-pulse-local} java -Xmx512m -Dloader.main=io.github.cheshmakzanoon.portfolio.pulsestream.ReplayWorker -Dloader.path="$PULSE_PROJECT/verification/target/pulsestream-verification-1.0.0-SNAPSHOT.jar" -cp "$PULSE_PROJECT/service/target/pulsestream-service-1.0.0-SNAPSHOT-exec.jar" org.springframework.boot.loader.launch.PropertiesLauncher "jdbc:postgresql://127.0.0.1:${PULSE_SQL_PORT:-18401}/pulse" pulse "127.0.0.1:${PULSE_KAFKA_PORT:-18402}" "$@"
}
create_topic() { compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic "$1" --partitions "${2:-2}" --replication-factor 1; }
