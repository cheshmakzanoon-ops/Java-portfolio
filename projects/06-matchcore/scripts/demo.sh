#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
matchcore_build
run_dir="$(mktemp -d "$MATCHCORE_DATA/demo.XXXXXX")"
timeout 120 java -Xmx512m -cp "$classpath" io.github.cheshmakzanoon.portfolio.matchcore.Demo "$run_dir/commands.log"
