#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ROOT=$(cd -- "$PROJECT/../.." && pwd)
if [[ -x /workspace/.tools/jdk-25/bin/java ]]; then source "$ROOT/scripts/cloud-env.sh"; fi
CACHE=${MAJORITY_M2:-$ROOT/.work/A03/m2}
RUNTIME=${MAJORITY_RUNTIME:-$ROOT/.work/A03/runtime}
PORT=${MAJORITY_PORT:-18300}
quorum_build() {
 local stamp="$PROJECT/raft-verification/target/verified-source.sha256" before after
 before=$(python3 "$PROJECT/scripts/source-fingerprint.py")
 if [[ ! -f "$PROJECT/raft-verification/target/runtime-classpath.txt" || ! -f "$stamp" || "$(cat "$stamp" 2>/dev/null || true)" != "$before" ]] || ! python3 "$PROJECT/scripts/runtime-artifacts.py" check >/dev/null 2>&1; then
  if [[ ${MAJORITY_PREPARED:-0} == 1 ]]; then echo "Prepared source/artifacts are absent or stale; verify before reserving the quiet host." >&2; return 1; fi
  "$ROOT/mvnw" -f "$PROJECT/pom.xml" -Dmaven.repo.local="$CACHE" -B -ntp verify
  after=$(python3 "$PROJECT/scripts/source-fingerprint.py")
  if [[ $before != "$after" ]]; then echo 'Source changed during verification; rerun command.' >&2; return 1; fi
  python3 "$PROJECT/scripts/runtime-artifacts.py" write
  printf '%s\n' "$after" > "$stamp"
 fi
}
quorum_classpath() {
 CLASSPATH=""
 for module in raft-core raft-storage raft-transport kv-server kv-client raft-verification; do CLASSPATH+="$PROJECT/$module/target/classes:"; done
 CLASSPATH+=$(cat "$PROJECT/raft-verification/target/runtime-classpath.txt")
}
run_tool(){
 # Preserve a working stop path even if the editable source has changed.
 if [[ ${1:-} != down || ! -f "$PROJECT/raft-verification/target/runtime-classpath.txt" ]]; then quorum_build; fi
 quorum_classpath
 local -a flags=(-Xmx512m "-Dmajority.record=${MAJORITY_RECORD:-false}")
 if [[ ${1:-} == benchmark && ${MAJORITY_RECORD:-false} == true ]]; then flags+=("-XX:StartFlightRecording=name=majority-client,filename=$2/client.jfr,settings=profile,maxsize=8m,dumponexit=true"); fi
 local fingerprint
 fingerprint=$(python3 "$PROJECT/scripts/source-fingerprint.py")
 java "${flags[@]}" "-Dmajority.source=$fingerprint" -cp "$CLASSPATH" io.github.cheshmakzanoon.portfolio.majority.ClusterTool "$@"
}
