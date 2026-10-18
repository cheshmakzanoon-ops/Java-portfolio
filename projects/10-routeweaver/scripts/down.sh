#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
if [[ -f "$runtime/service.pid" ]]; then
  pid=$(cat "$runtime/service.pid")
  if [[ -r "/proc/$pid/cmdline" ]] && tr '\0' ' ' < "/proc/$pid/cmdline" | rg -q 'routeweaver-service'; then
    kill "$pid"
  fi
  rm -f "$runtime/service.pid" "$runtime/running-source"
fi
docker compose -f "$project/infra/compose.yaml" down --timeout 20
