#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
exec 9>"$runtime/lifecycle.lock"
flock -w 30 9 || { echo 'Switchback lifecycle is busy' >&2; exit 1; }
require_build
status=0
python3 "$project/scripts/lifecycle.py" status || status=$?
if [[ $status == 0 ]]; then
  if [[ $(cat "$runtime/running-source") == "$SWITCHBACK_SOURCE_REVISION" ]]; then
    health
    exit
  fi
  python3 "$project/scripts/lifecycle.py" stop
elif [[ $status != 1 ]]; then
  exit "$status"
fi
python3 "$project/scripts/lifecycle.py" free-port
docker compose -f "$project/infra/compose.yaml" up -d --wait --wait-timeout 90
# Explicit trusted disposable local profile; the service rejects non-loopback binding.
export SWITCHBACK_AUTH_MODE=dev
export SWITCHBACK_DB_URL="jdbc:postgresql://127.0.0.1:${SWITCHBACK_DB_PORT:-19001}/switchback"
python3 "$project/scripts/lifecycle.py" start --source "$SWITCHBACK_SOURCE_REVISION"
if ! health; then
  python3 "$project/scripts/lifecycle.py" stop
  exit 1
fi
printf 'Switchback ready on loopback port %s (trusted disposable development profile)\n' "${SWITCHBACK_PORT:-19000}"
