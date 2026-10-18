#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
# Use an isolated new run so replaying the demonstration never reuses altered seeded state.
mkdir -p "$project_dir/.runtime"
run_data=$(mktemp -d "$project_dir/.runtime/demo-run-XXXXXX")
run_cli demo "$run_data" "${INDEXFORGE_DOCUMENTS:-20000}"
