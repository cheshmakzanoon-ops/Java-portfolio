#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
mkdir -p "$FAULTLINE_RUNTIME/demo"
echo "Faultline local CLI state prepared: $FAULTLINE_RUNTIME (no server)"
