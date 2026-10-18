#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
health
timeout 180 "$JAVA_HOME/bin/java" -Xmx768m -jar "$project/routeweaver-verification/target/routeweaver-verification-1.0.0-SNAPSHOT-driver.jar" demo "http://127.0.0.1:${ROUTEWEAVER_PORT:-19000}" | tee "$runtime/demo-result.json"
