#!/usr/bin/env bash
set -euo pipefail
BYTEGUARD_PROJECT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BYTEGUARD_ROOT=$(cd "$BYTEGUARD_PROJECT/../.." && pwd)
BYTEGUARD_RUNTIME="$BYTEGUARD_PROJECT/.runtime"
BYTEGUARD_JAR="$BYTEGUARD_PROJECT/cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar"
if [[ -f "$BYTEGUARD_ROOT/scripts/cloud-env.sh" && -d /workspace/.tools/jdk-25 ]]; then source "$BYTEGUARD_ROOT/scripts/cloud-env.sh"; fi
command -v java >/dev/null || { echo 'JDK 25 required' >&2; exit 2; }
mkdir -p "$BYTEGUARD_RUNTIME"
byteguard_fingerprint() {
 python3 - "$BYTEGUARD_PROJECT" "$BYTEGUARD_ROOT" <<'PY'
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
byteguard_build() {
 local expected current='' artifact='' actual_artifact=''
 expected=$(byteguard_fingerprint)
 [[ -f "$BYTEGUARD_RUNTIME/build-fingerprint" ]] && current=$(head -n 1 "$BYTEGUARD_RUNTIME/build-fingerprint")
 [[ -f "$BYTEGUARD_RUNTIME/build-fingerprint" ]] && artifact=$(tail -n 1 "$BYTEGUARD_RUNTIME/build-fingerprint")
 [[ -s "$BYTEGUARD_JAR" ]] && actual_artifact=$(sha256sum "$BYTEGUARD_JAR" | cut -d' ' -f1)
 if [[ "$expected" != "$current" || "$artifact" != "$actual_artifact" || ! -s "$BYTEGUARD_JAR" ]]; then
  (cd "$BYTEGUARD_ROOT" && ./mvnw -f "$BYTEGUARD_PROJECT/pom.xml" -Dmaven.repo.local="$BYTEGUARD_ROOT/.work/A09/m2" -B -ntp verify)
  { byteguard_fingerprint; sha256sum "$BYTEGUARD_JAR" | cut -d' ' -f1; } > "$BYTEGUARD_RUNTIME/build-fingerprint"
 else echo "ByteGuard verified source cache $expected" >&2; fi
}
byteguard_scan_expect() {
 local expected=$1;shift
 set +e
 java -Xmx2g -jar "$BYTEGUARD_JAR" scan "$@"
 local actual=$?
 set -e
 [[ "$actual" -eq "$expected" ]] || { echo "Expected scan exit $expected, got $actual" >&2; exit 1; }
}
