#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
matchcore_build
count="${MATCHCORE_COMMANDS:-1000000}"
mode="${1:-core}"
run_dir="$(mktemp -d "$MATCHCORE_DATA/benchmark.XXXXXX")"
exec java -Xmx3g -XX:StartFlightRecording=filename="$run_dir/workload.jfr",settings=profile -cp "$classpath" io.github.cheshmakzanoon.portfolio.matchcore.Workload "$mode" "$count" "$run_dir/commands.log"
