#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
# Compile, verify and resolve the reactor classpath once, before the quiet reservation.
PULSE_EXPECTED=$(fingerprint)
if [[ ! -f "$PULSE_PROJECT/verification/target/runtime-classpath.txt" || ! -f "$PULSE_RUNTIME/classpath.fingerprint" || "$(cat "$PULSE_RUNTIME/classpath.fingerprint")" != "$PULSE_EXPECTED" ]] || ! require_current_build; then
  (cd "$PULSE_ROOT"; ./mvnw -f "$PULSE_PROJECT/pom.xml" -Dmaven.repo.local="$PULSE_WORK/m2" -B -ntp -Pbenchmarks verify org.apache.maven.plugins:maven-dependency-plugin:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt)
  [[ "$PULSE_EXPECTED" == "$(fingerprint)" ]] || { echo 'Inputs changed during benchmark preparation; repeat stable verification' >&2; exit 1; }
  printf '%s\n' "$PULSE_EXPECTED" > "$PULSE_RUNTIME/classpath.fingerprint"
  printf '%s\n' "$PULSE_EXPECTED" > "$PULSE_RUNTIME/build.fingerprint"
  python3 - "$PULSE_RUNTIME/artifacts.sha256.json" "$PULSE_SERVICE_JAR" "$PULSE_VERIFY_JAR" <<'PYHASH'
import hashlib,json,sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({p:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in sys.argv[2:]},indent=2)+'\n')
PYHASH
fi
[[ -s "$PULSE_PROJECT/verification/target/classes/META-INF/BenchmarkList" ]] || { echo 'JMH annotation processor produced no benchmark metadata' >&2; exit 1; }
grep -q 'EngineBenchmark' "$PULSE_PROJECT/verification/target/classes/META-INF/BenchmarkList" || { echo 'Engine benchmark absent from generated metadata' >&2; exit 1; }
python3 "$PULSE_PROJECT/scripts/capture-environment.py" "$PULSE_RUNTIME/environment.json"
echo 'Verified runtime and benchmark classpath prepared; measurements require the coordinator quiet grant.'
