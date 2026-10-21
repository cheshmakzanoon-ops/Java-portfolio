#!/usr/bin/env bash
set -euo pipefail
FAULTLINE_PROJECT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FAULTLINE_ROOT=$(cd "$FAULTLINE_PROJECT/../.." && pwd)
FAULTLINE_RUNTIME="$FAULTLINE_PROJECT/.runtime"
FAULTLINE_JAR="$FAULTLINE_PROJECT/cli/target/faultline-cli-1.0.0-SNAPSHOT.jar"
if [[ -f "$FAULTLINE_ROOT/scripts/cloud-env.sh" && -d /workspace/.tools/jdk-25 ]]; then source "$FAULTLINE_ROOT/scripts/cloud-env.sh"; fi
command -v java >/dev/null || { echo 'JDK 25 required' >&2; exit 2; }
mkdir -p "$FAULTLINE_RUNTIME"
faultline_fingerprint() {
 python3 - "$FAULTLINE_PROJECT" "$FAULTLINE_ROOT" <<'PY'
import hashlib,sys
from pathlib import Path
project,root=map(Path,sys.argv[1:]); paths=[]
for module in ['analysis-core','report','cli','evaluation']:
 paths.extend((project/module/'src').rglob('*'));paths.append(project/module/'pom.xml')
paths.extend((project/'corpus').rglob('*'));paths.extend((project/'rules').rglob('*'))
paths.extend((project/'contracts').rglob('*'));paths.extend((project/'scripts').rglob('*'))
paths.extend([project/'pom.xml',root/'pom.xml',root/'mvnw'])
paths.extend((root/'.mvn').rglob('*')); digest=hashlib.sha256()
for path in sorted(set(paths)):
 if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':digest.update(str(path.relative_to(root)).encode()+b'\0'+path.read_bytes()+b'\0')
print(digest.hexdigest())
PY
}
faultline_build() {
 local expected observed temporary current='' artifact='' actual_artifact=''
 expected=$(faultline_fingerprint)
 [[ -f "$FAULTLINE_RUNTIME/build-fingerprint" ]] && current=$(head -n 1 "$FAULTLINE_RUNTIME/build-fingerprint")
 [[ -f "$FAULTLINE_RUNTIME/build-fingerprint" ]] && artifact=$(tail -n 1 "$FAULTLINE_RUNTIME/build-fingerprint")
 [[ -s "$FAULTLINE_JAR" ]] && actual_artifact=$(sha256sum "$FAULTLINE_JAR" | cut -d' ' -f1)
 if [[ "$expected" != "$current" || "$artifact" != "$actual_artifact" || ! -s "$FAULTLINE_JAR" ]]; then
  (cd "$FAULTLINE_ROOT" && ./mvnw -f "$FAULTLINE_PROJECT/pom.xml" -Dmaven.repo.local="$FAULTLINE_ROOT/.work/A09/m2" -B -ntp verify)
  observed=$(faultline_fingerprint)
  [[ "$observed" == "$expected" ]] || { echo 'Faultline source changed during verification; run preparation again.' >&2; return 2; }
  [[ -s "$FAULTLINE_JAR" ]] || { echo 'Verified CLI artifact is missing.' >&2; return 2; }
  actual_artifact=$(sha256sum "$FAULTLINE_JAR" | cut -d' ' -f1)
  temporary=$(mktemp "$FAULTLINE_RUNTIME/.build-fingerprint.XXXXXX")
  if ! { printf '%s\n%s\n' "$expected" "$actual_artifact" > "$temporary" && mv -f -- "$temporary" "$FAULTLINE_RUNTIME/build-fingerprint"; }; then
   rm -f -- "$temporary"
   echo 'Unable to publish the verified CLI receipt.' >&2
   return 2
  fi
 else echo "Faultline verified source cache $expected" >&2; fi
}
faultline_scan_expect() {
 local expected=$1;shift
 set +e
 java -Xmx2g -jar "$FAULTLINE_JAR" scan "$@"
 local actual=$?
 set -e
 [[ "$actual" -eq "$expected" ]] || { echo "Expected scan exit $expected, got $actual" >&2; exit 1; }
}
