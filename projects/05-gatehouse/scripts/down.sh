#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
for service in decision-service demo-resource-server; do stop_service "$service" "$PROJECT_DIR/$service/target/gatehouse-$service-1.0.0-SNAPSHOT-exec.jar"; done
compose down
