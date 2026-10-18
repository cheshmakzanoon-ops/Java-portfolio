#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
if [[ -f "$PULSE_WORK/runtime/service.pid" ]]; then
  PULSE_PID=$(cat "$PULSE_WORK/runtime/service.pid")
  if kill -0 "$PULSE_PID" 2>/dev/null && [[ -r "/proc/$PULSE_PID/cmdline" ]] && tr '\0' ' ' < "/proc/$PULSE_PID/cmdline" | grep -q 'pulsestream-service-1.0.0-SNAPSHOT-exec.jar'; then kill "$PULSE_PID"; fi
  rm -f "$PULSE_WORK/runtime/service.pid"
fi
compose stop
