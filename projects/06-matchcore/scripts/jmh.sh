#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
matchcore_build
run_dir="$(mktemp -d "$MATCHCORE_DATA/jmh.XXXXXX")"
exec java -Xmx512m -cp "$classpath" org.openjdk.jmh.Main 'MatchingBenchmark.*' -prof gc -rf json -rff "$run_dir/results.json"
