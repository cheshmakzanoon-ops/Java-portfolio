#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
matchcore_build
run_dir="$(mktemp -d "$MATCHCORE_DATA/jmh.XXXXXX")"
python3 "$project_dir/scripts/capture-environment.py" "$run_dir" jmh 0
run_command=(java -Xmx512m -cp "$classpath" org.openjdk.jmh.Main 'MatchingBenchmark.*' -prof gc -rf json -rff "$run_dir/results.json")
printf '%q ' "${run_command[@]}" > "$run_dir/command.txt"
printf '\n' >> "$run_dir/command.txt"
status=0
python3 "$project_dir/scripts/capture-process.py" "$run_dir" "${run_command[@]}" || status=$?
printf '%s\n' "$status" > "$run_dir/exit-code.txt"
python3 "$project_dir/scripts/summarize-recording.py" "$run_dir" jmh
cat "$run_dir/stdout.txt"
exit "$status"
