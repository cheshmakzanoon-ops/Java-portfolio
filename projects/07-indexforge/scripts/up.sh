#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
run_cli up "$data"
[[ -d "$data/sources" ]]
echo 'IndexForge CLI workspace prepared. HTTP is optional: see docs/OPERATIONS.md.'
