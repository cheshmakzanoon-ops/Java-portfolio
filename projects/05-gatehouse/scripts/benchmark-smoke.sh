#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
require_current_build
require_running_build
wait_for_services
export GATEHOUSE_SOURCE_FINGERPRINT="$(fingerprint)"
export GATEHOUSE_MIGRATION_URL=jdbc:postgresql://127.0.0.1:18503/gatehouse GATEHOUSE_MIGRATION_USER=migration GATEHOUSE_MIGRATION_PASSWORD=local-migration-only
CP="$(classpath)"
bounded 1800 java -Xmx512m -cp "$CP" io.github.cheshmakzanoon.portfolio.gatehouse.Workload seed "$RUNTIME_DIR/seed.json"
unset GATEHOUSE_MIGRATION_USER GATEHOUSE_MIGRATION_PASSWORD GATEHOUSE_MIGRATION_URL
bounded 300 java -Xmx256m -cp "$CP" io.github.cheshmakzanoon.portfolio.gatehouse.Workload http "$RUNTIME_DIR/http-smoke.json" 1000
