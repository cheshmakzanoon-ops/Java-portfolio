#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_current_build
[[ -f "$PULSE_RUNTIME/classpath.fingerprint" && "$(cat "$PULSE_RUNTIME/classpath.fingerprint")" == "$(fingerprint)" && -f "$PULSE_PROJECT/verification/target/runtime-classpath.txt" ]] || { echo 'Run scripts/prepare-benchmark.sh before reserving the quiet host' >&2; exit 1; }
PULSE_RUN_ID="engine-$(date +%s)${BASHPID}"
mkdir -p "$PULSE_RUNTIME/$PULSE_RUN_ID/jfr"
PULSE_CLASSPATH=$(cat "$PULSE_PROJECT/verification/target/runtime-classpath.txt")
python3 "$PULSE_PROJECT/scripts/capture-environment.py" "$PULSE_RUNTIME/$PULSE_RUN_ID/environment.json"
bounded 600 java -Xmx512m -cp "$PULSE_PROJECT/verification/target/classes:$PULSE_CLASSPATH" org.openjdk.jmh.Main '.*EngineBenchmark.*' -f 3 -wi 2 -i 3 -w 1s -r 1s -prof gc -jvmArgsAppend "-XX:StartFlightRecording=filename=$PULSE_RUNTIME/$PULSE_RUN_ID/jfr/profile-%p.jfr,settings=profile,dumponexit=true" -rf json -rff "$PULSE_RUNTIME/$PULSE_RUN_ID/jmh.json" 2>&1 | tee "$PULSE_RUNTIME/$PULSE_RUN_ID/console.log"
printf '%s\n' "$PULSE_RUN_ID" > "$PULSE_RUNTIME/last-engine-run"
echo "Observed engine JMH artifacts: $PULSE_RUNTIME/$PULSE_RUN_ID"
