#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
exec 9>"$PULSE_RUNTIME/lifecycle.lock"
flock -x 9
primary=0
remember_signal() {
  ((primary != 0)) || primary=$1
  trap '' INT TERM
}
trap 'remember_signal 130' INT
trap 'remember_signal 143' TERM
stop_service || { result=$?; ((primary != 0)) || primary=$result; }
bounded 90 docker compose -f "$PULSE_PROJECT/infra/compose.yaml" stop --timeout 30 || { result=$?; ((primary != 0)) || primary=$result; }
exit "$primary"
