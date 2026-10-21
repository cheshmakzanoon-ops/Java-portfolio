#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
case "$RUNTIME" in "$ROOT/.work/A03/runtime") ;; *) echo 'reset-demo only removes the default project demo runtime' >&2; exit 1;; esac
run_tool down "$RUNTIME" "$PORT"
rm -rf -- "$RUNTIME"
