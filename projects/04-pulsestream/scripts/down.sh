#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
stop_service
bounded 90 docker compose -f "$PULSE_PROJECT/infra/compose.yaml" stop --timeout 30
