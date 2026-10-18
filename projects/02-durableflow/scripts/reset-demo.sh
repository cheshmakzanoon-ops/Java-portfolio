#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
"$PROJECT/scripts/down.sh"
docker compose -f "$PROJECT/infra/compose.yaml" down --volumes
rm -f "$PROJECT/target/runtime/provider-effects.log" "$PROJECT"/target/runtime/*-gc.log* "$PROJECT"/target/runtime/*.log
echo 'Removed only DurableFlow disposable demo database, provider effect log and current runtime logs; captured performance runs are retained.'
