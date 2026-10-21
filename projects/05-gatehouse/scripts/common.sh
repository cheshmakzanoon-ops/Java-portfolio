#!/usr/bin/env bash
set -euo pipefail
umask 077
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$(cd -- "$PROJECT_DIR/../.." && pwd)"
RUNTIME_DIR="$REPO_DIR/.work/A05/runtime"
M2_DIR="${GATEHOUSE_M2:-$REPO_DIR/.work/A05/m2}"
mkdir -p "$RUNTIME_DIR"
if [[ -x /workspace/.tools/jdk-25/bin/java && -f "$REPO_DIR/scripts/cloud-env.sh" ]]; then source "$REPO_DIR/scripts/cloud-env.sh"; fi
compose() { docker compose -f "$PROJECT_DIR/infra/compose.yaml" "$@"; }
bounded() { python3 "$PROJECT_DIR/scripts/run-bounded.py" "$@"; }
fingerprint() { python3 "$PROJECT_DIR/scripts/fingerprint.py"; }
classpath() { require_current_build; python3 "$PROJECT_DIR/scripts/extract-runtime.py" "$PROJECT_DIR/verification/target/gatehouse-verification-1.0.0-SNAPSHOT-exec.jar" "$RUNTIME_DIR"; }
require_current_build() { python3 "$PROJECT_DIR/scripts/build-cache.py" verify || { echo 'Verified artifacts are absent, stale or modified; run scripts/prepare-runtime.sh outside the quiet window' >&2; return 1; }; }
require_running_build() { [[ -f "$RUNTIME_DIR/services.fingerprint" ]] && [[ "$(cat "$RUNTIME_DIR/services.fingerprint")" == "$(fingerprint)" ]] || { echo 'Running services must match current executable inputs; rerun scripts/up.sh' >&2; return 1; }; }
owned_process_running() {
  local pid="$1" jar="$2" state
  state="$(ps -p "$pid" -o stat=)" || return 1
  [[ "$state" != *Z* ]] && ps -p "$pid" -o args= | grep -Fq -- "$jar"
}
service_pid() { local name="$1" pid jar; jar="$PROJECT_DIR/$name/target/gatehouse-$name-1.0.0-SNAPSHOT-exec.jar"; [[ -f "$RUNTIME_DIR/$name.pid" ]] || { echo 'Owned service process is not running; run scripts/up.sh' >&2; return 1; }; pid="$(cat "$RUNTIME_DIR/$name.pid")"; [[ "$pid" =~ ^[0-9]+$ ]] && owned_process_running "$pid" "$jar" || { echo 'Owned service process is not running; run scripts/up.sh' >&2; return 1; }; echo "$pid"; }
wait_for_services() {
  local deadline=$((SECONDS + 90))
  while ((SECONDS < deadline)); do
    if curl --max-time 2 --fail --silent http://127.0.0.1:18500/actuator/health >/dev/null && [[ "$(curl --max-time 2 --silent --output /dev/null --write-out '%{http_code}' http://127.0.0.1:18501/v1/tenants/harbor/documents/doc-7)" == 401 ]] && service_pid decision-service >/dev/null 2>&1 && service_pid demo-resource-server >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Owned HTTP services did not become ready within 90 seconds; run scripts/up.sh and inspect $RUNTIME_DIR/*.log" >&2
  return 1
}
stop_process() {
  local pid="$1" jar="$2"
  if [[ "$pid" =~ ^[0-9]+$ ]] && owned_process_running "$pid" "$jar"; then
    kill "$pid"
    for _ in $(seq 1 30); do if ! owned_process_running "$pid" "$jar"; then break; fi; sleep 1; done
    if owned_process_running "$pid" "$jar"; then kill -KILL "$pid"; fi
  fi
}
stop_service() {
  local name="$1" jar="$2" pid
  if [[ -f "$RUNTIME_DIR/$name.pid" ]]; then
    pid="$(cat "$RUNTIME_DIR/$name.pid")"
    stop_process "$pid" "$jar"
    rm -f "$RUNTIME_DIR/$name.pid"
  fi
}
