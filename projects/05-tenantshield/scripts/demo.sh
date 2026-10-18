#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
require_current_build
require_running_build
wait_for_services
bounded 240 python3 "$PROJECT_DIR/demo/walkthrough.py" | tee "$PROJECT_DIR/evidence/demo-output.txt"
