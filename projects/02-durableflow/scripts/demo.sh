#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
curl --max-time 5 -fsS "http://127.0.0.1:${PORT:-18200}/health" >/dev/null || { echo 'Run up durableflow first' >&2; exit 1; }
status=$(curl --max-time 5 -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT:-18200}/v1/executions/00000000-0000-0000-0000-000000000000")
[[ "$status" == 401 ]] || { echo "Expected unauthenticated 401, observed $status" >&2; exit 1; }
status=$(curl --max-time 5 -sS -u "viewer:${DEMO_PASSWORD:-disposable-local-only}" -H 'Content-Type: application/json' --data-binary "@$PROJECT/demo/order.json" -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT:-18200}/v1/definitions")
[[ "$status" == 403 ]] || { echo "Unexpected viewer deployment status: $status" >&2; exit 1; }
timeout 180 bash -c 'source "$1/scripts/common.sh"; run_main ScenarioMain' _ "$PROJECT"
