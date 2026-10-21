#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
exec 9>"$runtime/lifecycle.lock"
flock -w 30 9 || { echo 'Switchback lifecycle is busy' >&2; exit 1; }
status=0
python3 "$project/scripts/lifecycle.py" status || status=$?
if [[ $status == 0 ]]; then
  echo 'Stop the owned service before polling diagnosis' >&2
  exit 1
elif [[ $status != 1 ]]; then
  exit "$status"
fi
docker compose -f "$project/infra/compose.yaml" up -d --wait --wait-timeout 90
export SWITCHBACK_DB_URL="jdbc:postgresql://127.0.0.1:${SWITCHBACK_DB_PORT:-19001}/switchback"
timeout 180 "$JAVA_HOME/bin/java" -Xmx768m -jar "$project/switchback-verification/target/switchback-verification-1.0.0-SNAPSHOT-driver.jar" poll-diagnostic | tee "$runtime/poll-diagnostic-result.json"
