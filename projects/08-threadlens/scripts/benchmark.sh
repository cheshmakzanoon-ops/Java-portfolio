#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
threadlens_prepare
out=${THREADLENS_BENCHMARK_OUTPUT:-"$threadlens_project/target/benchmarks/$(date -u +%Y%m%dT%H%M%SZ)"}
threadlens_cli benchmark "$out" "$threadlens_project/agent/target/threadlens-agent-1.0.0-SNAPSHOT.jar" "$@"
