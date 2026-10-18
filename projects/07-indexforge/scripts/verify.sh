#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
cd "$repo_dir"
./mvnw -f "$project_dir/pom.xml" -Dmaven.repo.local="$repo_dir/.work/A07/m2" -B -ntp -Pintegration verify
mkdir -p "$project_dir/.runtime"
source_fingerprint > "$project_dir/.runtime/tested-source"
