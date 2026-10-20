#!/usr/bin/env bash
set -euo pipefail
project=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
root=$(cd "$project/../.." && pwd)
runtime="$project/.runtime"
mkdir -p "$runtime"
if [[ -d /workspace/.tools/jdk-25 ]]; then
  source "$root/scripts/cloud-env.sh"
else
  if [[ -z ${JAVA_HOME:-} ]]; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
    export JAVA_HOME
  fi
  export PATH="$JAVA_HOME/bin:$PATH"
  export MAVEN_USER_HOME="$root/.work/wrapper"
  export MAVEN_OPTS=${MAVEN_OPTS:--Xmx512m}
fi
fingerprint() {
  python3 - "$project" "$root/pom.xml" <<'PY'
import hashlib,sys
from pathlib import Path
p=Path(sys.argv[1]); h=hashlib.sha256()
paths=sorted([x for x in p.rglob('*') if x.is_file() and 'target' not in x.parts and '.runtime' not in x.parts and (x.suffix in ('.java','.xml','.properties','.sql','.html','.js','.css','.sh','.yaml','.py') or x.name=='pom.xml')]+[Path(sys.argv[2])])
for x in paths:h.update(str(x.relative_to(p) if x.is_relative_to(p) else 'root-pom.xml').encode());h.update(x.read_bytes())
print(h.hexdigest())
PY
}
require_build() {
  local current
  current=$(fingerprint)
  if [[ ! -f "$runtime/build-stamp" || $(cat "$runtime/build-stamp") != "$current" ]]; then
    echo "Current sources require verification: run $project/scripts/build.sh" >&2
    exit 1
  fi
  python3 "$project/scripts/runtime-artifacts.py" check --source "$current"
  export ROUTEWEAVER_SOURCE_REVISION="$current"
}
health() {
  python3 "$project/scripts/lifecycle.py" health
}
