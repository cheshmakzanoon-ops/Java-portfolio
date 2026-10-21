#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
rm -rf -- "$FAULTLINE_RUNTIME/demo"
mkdir -p "$FAULTLINE_RUNTIME/demo"
echo 'Faultline seeded demo data reset.'
