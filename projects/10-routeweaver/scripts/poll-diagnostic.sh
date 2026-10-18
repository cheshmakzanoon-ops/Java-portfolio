#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
if [[ -f "$runtime/service.pid" ]] && kill -0 "$(cat "$runtime/service.pid")" 2>/dev/null; then
  echo "Stop the owned service before polling diagnosis; it must not claim diagnostic jobs" >&2
  exit 1
fi
docker compose -f "$project/infra/compose.yaml" up -d --wait --wait-timeout 90
export ROUTEWEAVER_DB_URL="jdbc:postgresql://127.0.0.1:${ROUTEWEAVER_DB_PORT:-19001}/routeweaver"
timeout 180 "$JAVA_HOME/bin/java" -Xmx768m -jar "$project/routeweaver-verification/target/routeweaver-verification-1.0.0-SNAPSHOT-driver.jar" poll-diagnostic | tee "$runtime/poll-diagnostic-result.json"
