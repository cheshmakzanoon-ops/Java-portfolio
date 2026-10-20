#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
cd "$root"
exec 9>"$runtime/lifecycle.lock"
flock -w 30 9 || { echo 'RouteWeaver lifecycle is busy' >&2; exit 1; }
status=0
python3 "$project/scripts/lifecycle.py" status || status=$?
if [[ $status == 0 ]]; then
  echo 'Stop the owned RouteWeaver service before rebuilding its runtime artifacts' >&2
  exit 1
elif [[ $status != 1 ]]; then
  exit "$status"
fi
./mvnw -f "$project/pom.xml" -Dmaven.repo.local="$root/.work/A10/m2" -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply
before=$(fingerprint)
./mvnw -f "$project/pom.xml" -Dmaven.repo.local="$root/.work/A10/m2" -B -ntp "$@" verify
after=$(fingerprint)
if [[ "$before" != "$after" ]]; then
  echo 'Build inputs changed during verification; rerun build.sh' >&2
  exit 1
fi
python3 "$project/scripts/runtime-artifacts.py" write --source "$after"
printf '%s\n' "$after" > "$runtime/build-stamp"
