#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_dir="$(cd "$project_dir/../.." && pwd)"
if [[ -x /workspace/.tools/jdk-25/bin/java ]]; then source "$repo_dir/scripts/cloud-env.sh"; fi
export MATCHCORE_DATA="${MATCHCORE_DATA:-$project_dir/.runtime}"
mkdir -p "$MATCHCORE_DATA"
matchcore_classpath() {
classpath=""
for module in matching-core journal gateway simulator benchmarks; do
  classpath="$classpath:$project_dir/$module/target/classes"
done
if [[ -f "$project_dir/benchmarks/target/runtime-classpath.txt" ]]; then
 classpath="$classpath:$(cat "$project_dir/benchmarks/target/runtime-classpath.txt")"
fi
}
matchcore_classpath
matchcore_build() {
 local before after stamp="$project_dir/benchmarks/target/verified-source.sha256"
 before="$(python3 "$project_dir/scripts/source-fingerprint.py")"
 if [[ ! -f "$project_dir/benchmarks/target/runtime-classpath.txt" || ! -f "$stamp" || "$(cat "$stamp" 2>/dev/null || true)" != "$before" ]]; then
  "$repo_dir/mvnw" -f "$project_dir/pom.xml" -Dmaven.repo.local="$repo_dir/.work/A06/m2" -B -ntp verify
  after="$(python3 "$project_dir/scripts/source-fingerprint.py")"
  if [[ "$before" != "$after" ]]; then printf 'Source changed during verification; rerun command.\n' >&2; return 1; fi
  printf '%s\n' "$after" > "$stamp"
 fi
 matchcore_classpath
}
