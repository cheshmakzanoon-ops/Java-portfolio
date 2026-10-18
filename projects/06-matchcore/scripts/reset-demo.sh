#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
# Only known generated demo directories below this project's default runtime are removed.
for run_dir in "$project_dir"/.runtime/demo.*; do
 [[ -d "$run_dir" && ! -L "$run_dir" ]] && rm -rf -- "$run_dir"
done

exit 0
