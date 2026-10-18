#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
rm -rf -- "$BYTEGUARD_RUNTIME/demo"
mkdir -p "$BYTEGUARD_RUNTIME/demo"
echo 'ByteGuard seeded demo data reset.'
