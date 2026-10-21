#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
[[ "$data" == "$project_dir/.runtime/demo" ]]
rm -rf -- "$data"
for run in "$project_dir"/.runtime/demo-run-*; do [[ ! -d "$run" ]] || rm -rf -- "$run"; done
mkdir -p "$data/sources"
echo 'Removed only Stacks seeded CLI demonstration data.'
