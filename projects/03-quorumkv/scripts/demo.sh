#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
DEMO=$(mktemp -d "$ROOT/.work/A03/demo-XXXXXXXX")
run_tool demo "$DEMO" "$PORT"
printf 'Demo evidence: %s\n' "$DEMO/demo-result.json"
