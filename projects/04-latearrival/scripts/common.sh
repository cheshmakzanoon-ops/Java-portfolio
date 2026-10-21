#!/usr/bin/env bash
set -euo pipefail
umask 077
PULSE_PROJECT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
PULSE_ROOT=$(cd -- "$PULSE_PROJECT/../.." && pwd)
PULSE_WORK="$PULSE_ROOT/.work/A04"
PULSE_RUNTIME="$PULSE_WORK/runtime"
PULSE_SERVICE_JAR="$PULSE_PROJECT/service/target/latearrival-service-1.0.0-SNAPSHOT-exec.jar"
PULSE_PROCESS_HELPER="$PULSE_PROJECT/scripts/runtime-process.py"
PULSE_PROCESS_RECORD="$PULSE_RUNTIME/service.pid"
PULSE_VERIFY_JAR="$PULSE_PROJECT/verification/target/latearrival-verification-1.0.0-SNAPSHOT.jar"
mkdir -p "$PULSE_RUNTIME"
if [[ -x /workspace/.tools/jdk-25/bin/java ]]; then source "$PULSE_ROOT/scripts/cloud-env.sh"; fi
compose() { docker compose -f "$PULSE_PROJECT/infra/compose.yaml" "$@"; }
bounded() { python3 "$PULSE_PROJECT/scripts/run-bounded.py" "$@"; }
fingerprint() { python3 "$PULSE_PROJECT/scripts/fingerprint.py"; }
artifacts_valid() {
  [[ -f "$PULSE_RUNTIME/artifacts.sha256.json" && -f "$PULSE_SERVICE_JAR" && -f "$PULSE_VERIFY_JAR" ]] || return 1
  python3 - "$PULSE_RUNTIME/artifacts.sha256.json" <<'PY'
import hashlib,json,sys
from pathlib import Path
for name,digest in json.loads(Path(sys.argv[1]).read_text()).items():
 p=Path(name)
 if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:sys.exit(1)
PY
}
build() {
  local expected
  python3 "$PULSE_PROCESS_HELPER" validate "$PULSE_PROCESS_RECORD" "$PULSE_SERVICE_JAR"
  if owned_service_running; then echo "Stop the owned service before replacing its verified artifacts" >&2; return 1; fi
  expected=$(fingerprint)
  (cd "$PULSE_ROOT"; ./mvnw -f "$PULSE_PROJECT/pom.xml" -Dmaven.repo.local="$PULSE_WORK/m2" -B -ntp verify)
  [[ "$expected" == "$(fingerprint)" ]] || { echo 'Executable inputs changed during verification; rerun once stable' >&2; return 1; }
  python3 - "$PULSE_RUNTIME/artifacts.sha256.json" "$PULSE_SERVICE_JAR" "$PULSE_VERIFY_JAR" <<'PY'
import hashlib,json,sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({name:hashlib.sha256(Path(name).read_bytes()).hexdigest() for name in sys.argv[2:]},indent=2)+'\n')
PY
  printf '%s\n' "$expected" > "$PULSE_RUNTIME/build.fingerprint"
}
ensure_build() {
  if [[ ! -f "$PULSE_RUNTIME/build.fingerprint" ]] || [[ "$(cat "$PULSE_RUNTIME/build.fingerprint")" != "$(fingerprint)" ]] || ! artifacts_valid; then build; fi
}
require_current_build() {
  [[ -f "$PULSE_RUNTIME/build.fingerprint" ]] && [[ "$(cat "$PULSE_RUNTIME/build.fingerprint")" == "$(fingerprint)" ]] && artifacts_valid || { echo 'Current sources/artifacts have no matching verified build; run scripts/up.sh or scripts/prepare-benchmark.sh' >&2; return 1; }
}
owned_service_running() {
  python3 "$PULSE_PROCESS_HELPER" alive "$PULSE_PROCESS_RECORD" "$PULSE_SERVICE_JAR"
}
current_service_running() {
  python3 "$PULSE_PROCESS_HELPER" current "$PULSE_PROCESS_RECORD" "$PULSE_SERVICE_JAR"
}
stop_service() {
  python3 "$PULSE_PROCESS_HELPER" stop "$PULSE_PROCESS_RECORD" "$PULSE_SERVICE_JAR" || return $?
  rm -f "$PULSE_RUNTIME/services.fingerprint"
}
wait_for_service() {
  for ((i=0;i<90;i++)); do
    if ! owned_service_running; then echo "Owned LateArrival service exited before readiness; inspect $PULSE_RUNTIME/service.log" >&2; return 1; fi
    if curl --max-time 2 --fail --silent "http://127.0.0.1:${PULSE_HTTP_PORT:-18400}/health" >/dev/null; then return 0; fi
    sleep 1
  done
  echo "Owned LateArrival service failed health; inspect $PULSE_RUNTIME/service.log" >&2
  return 1
}
require_running_build() {
  require_current_build
  [[ -f "$PULSE_RUNTIME/services.fingerprint" ]] && [[ "$(cat "$PULSE_RUNTIME/services.fingerprint")" == "$(fingerprint)" ]] && current_service_running || { echo 'Running service is absent or stale; run scripts/up.sh' >&2; return 1; }
  wait_for_service
}
java_worker() {
  PULSE_DB_PASSWORD=${PULSE_DB_PASSWORD:-pulse-local} bounded 90 java -Xmx512m -Dloader.main=io.github.cheshmakzanoon.portfolio.latearrival.ReplayWorker -Dloader.path="$PULSE_VERIFY_JAR" -cp "$PULSE_SERVICE_JAR" org.springframework.boot.loader.launch.PropertiesLauncher "jdbc:postgresql://127.0.0.1:${PULSE_SQL_PORT:-18401}/pulse" pulse "127.0.0.1:${PULSE_KAFKA_PORT:-18402}" "$@"
}
create_topic() { bounded 40 docker compose -f "$PULSE_PROJECT/infra/compose.yaml" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic "$1" --partitions "${2:-2}" --replication-factor 1; }
