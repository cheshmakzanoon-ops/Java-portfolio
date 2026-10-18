#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
mkdir -p "$BYTEGUARD_RUNTIME/demo"
echo "ByteGuard local CLI state prepared: $BYTEGUARD_RUNTIME (no server)"
