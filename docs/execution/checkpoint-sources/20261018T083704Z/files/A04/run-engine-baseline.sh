#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
source projects/04-pulsestream/scripts/common.sh
require_current_build
[[ -s "$PULSE_PROJECT/verification/target/classes/META-INF/BenchmarkList" ]]
[[ "$(cat "$PULSE_RUNTIME/classpath.fingerprint")" == "$(fingerprint)" ]]
PULSE_BASELINE="$PULSE_WORK/pre-optimization-engine"
python3 - "$PULSE_BASELINE" <<'PY'
import sys,json,hashlib
from pathlib import Path
root=Path(sys.argv[1]);manifest=json.loads((root/'manifest.json').read_text())
for file,digest in manifest['files'].items():
 assert hashlib.sha256((root/file).read_bytes()).hexdigest()==digest,file
PY
PULSE_RUN_ID="engine-baseline-$(date +%s)${BASHPID}"
PULSE_OUTPUT="$PULSE_RUNTIME/$PULSE_RUN_ID"
mkdir -p "$PULSE_OUTPUT/jfr"
PULSE_CLASSPATH=$(cat "$PULSE_PROJECT/verification/target/runtime-classpath.txt")
python3 "$PULSE_PROJECT/scripts/capture-environment.py" "$PULSE_OUTPUT/environment.json"
cp "$PULSE_BASELINE/manifest.json" "$PULSE_OUTPUT/baseline-engine-manifest.json"
# Standard Java/JMH classpath ordering loads these preserved original Engine classes first.
# Harness, dependencies, fork count, warmup, duration and profilers match benchmark-engine.sh.
printf '%s\n' "$PULSE_BASELINE/classes:$PULSE_PROJECT/verification/target/classes:$PULSE_CLASSPATH" > "$PULSE_OUTPUT/classpath.txt"
bounded 600 java -Xmx512m -cp "$PULSE_BASELINE/classes:$PULSE_PROJECT/verification/target/classes:$PULSE_CLASSPATH" org.openjdk.jmh.Main '.*EngineBenchmark.*' -f 3 -wi 2 -i 3 -w 1s -r 1s -prof gc -jvmArgsAppend "-XX:StartFlightRecording=filename=$PULSE_OUTPUT/jfr/profile-%p.jfr,settings=profile,dumponexit=true" -rf json -rff "$PULSE_OUTPUT/jmh.json" 2>&1 | tee "$PULSE_OUTPUT/console.log"
printf '%s\n' "$PULSE_RUN_ID" > "$PULSE_RUNTIME/last-baseline-engine-run"
echo "Observed original engine JMH: $PULSE_OUTPUT"
