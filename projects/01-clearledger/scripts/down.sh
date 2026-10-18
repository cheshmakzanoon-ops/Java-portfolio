#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
if [[ -f "$PROJECT/target/api.pid" ]]; then
 pid="$(cat "$PROJECT/target/api.pid")"
 if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null && [[ "$(ps -p "$pid" -o command=)" == *"clearledger-api-1.0.0-SNAPSHOT-exec.jar"* ]]; then kill "$pid"; fi
 rm -f "$PROJECT/target/api.pid"
fi
"${COMPOSE[@]}" stop
