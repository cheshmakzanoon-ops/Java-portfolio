#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
thepit_build
run_dir="$(mktemp -d "$THEPIT_DATA/demo.XXXXXX")"
timeout 120 java -Xmx512m -cp "$classpath" io.github.cheshmakzanoon.portfolio.thepit.Demo "$run_dir/commands.log"
