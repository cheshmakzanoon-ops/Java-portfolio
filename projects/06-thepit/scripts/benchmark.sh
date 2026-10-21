#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
thepit_build
count="${THEPIT_COMMANDS:-1000000}"
mode="${1:-core}"
[[ "$mode" == core || "$mode" == durable ]] || { echo 'Mode must be core or durable' >&2; exit 2; }
run_dir="$(mktemp -d "$THEPIT_DATA/benchmark.$mode.XXXXXX")"
python3 "$project_dir/scripts/capture-environment.py" "$run_dir" "$mode" "$count"
run_command=(java -Xms512m -Xmx3g
 -XX:StartFlightRecording=filename="$run_dir/workload.jfr",settings=profile,dumponexit=true,maxsize=256m
 -Xlog:gc*,safepoint:file="$run_dir/gc.log":time,uptime,level,tags:filecount=2,filesize=32m
 -cp "$classpath" io.github.cheshmakzanoon.portfolio.thepit.Workload "$mode" "$count" "$run_dir/commands.log")
printf '%q ' "${run_command[@]}" > "$run_dir/command.txt"
printf '\n' >> "$run_dir/command.txt"
status=0
python3 "$project_dir/scripts/capture-process.py" "$run_dir" "${run_command[@]}" || status=$?
printf '%s\n' "$status" > "$run_dir/exit-code.txt"
if [[ -f "$run_dir/workload.jfr" ]]; then
 jfr summary "$run_dir/workload.jfr" > "$run_dir/jfr-summary.txt"
 jfr print --json --events jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.GarbageCollection "$run_dir/workload.jfr" > "$run_dir/jfr-events.json"
fi
python3 "$project_dir/scripts/summarize-recording.py" "$run_dir" "$mode"
[[ ! -f "$run_dir/result.json" ]] || cat "$run_dir/result.json"
exit "$status"
