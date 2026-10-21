#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
export MAJORITY_RECORD=true
OPS=100000
RUNS=3
if [[ ${1:-} == --smoke ]]; then OPS=100; RUNS=1; fi
OUT=$(mktemp -d "$ROOT/.work/A03/benchmark-XXXXXXXX")
run_tool benchmark "$OUT" "$PORT" "$OPS" "$RUNS"
printf 'Benchmark raw output: %s\n' "$OUT"
