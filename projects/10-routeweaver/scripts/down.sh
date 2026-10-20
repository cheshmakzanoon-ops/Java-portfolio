#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
exec 9>"$runtime/lifecycle.lock"
flock -w 30 9 || { echo 'RouteWeaver lifecycle is busy' >&2; exit 1; }
python3 "$project/scripts/lifecycle.py" stop
docker compose -f "$project/infra/compose.yaml" down --timeout 20
