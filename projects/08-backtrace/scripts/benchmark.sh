#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
backtrace_prepare
out=${BACKTRACE_BENCHMARK_OUTPUT:-"$backtrace_project/target/benchmarks/$(date -u +%Y%m%dT%H%M%SZ)"}
backtrace_cli benchmark "$out" "$backtrace_project/agent/target/backtrace-agent-1.0.0-SNAPSHOT.jar" "$@"
