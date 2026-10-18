#!/usr/bin/env bash
set -euo pipefail
threadlens_project=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
threadlens_root=$(cd "$threadlens_project/../.." && pwd)
threadlens_runtime="$threadlens_project/target/demo"
threadlens_classpath_file="$threadlens_project/cli/target/runtime-classpath.txt"
threadlens_fingerprint() {
  python3 - "$threadlens_project" "$threadlens_root" <<'PY'
import hashlib,sys
from pathlib import Path
project,root=map(Path,sys.argv[1:]); files=[root/'pom.xml',root/'mvnw',*sorted((root/'.mvn').rglob('*'))]
files += [p for p in project.rglob('*') if p.is_file() and ('src' in p.parts or p.name=='pom.xml' or 'scripts' in p.parts)]
h=hashlib.sha256()
for p in sorted(set(files)):
 if p.is_file(): h.update(str(p.relative_to(root)).encode());h.update(p.read_bytes())
print(h.hexdigest())
PY
}
threadlens_prepare() {
  command -v java >/dev/null || { echo 'Install JDK 25 and put java on PATH.' >&2; return 1; }
  local current cached=''
  current=$(threadlens_fingerprint)
  [[ ! -f "$threadlens_project/target/build.fingerprint" ]] || cached=$(cat "$threadlens_project/target/build.fingerprint")
  if [[ "$current" != "$cached" || ! -s "$threadlens_classpath_file" ]]; then
    if [[ -d /workspace/.tools/jdk-25 ]]; then source "$threadlens_root/scripts/cloud-env.sh"; fi
    "$threadlens_root/mvnw" -f "$threadlens_project/pom.xml" -Dmaven.repo.local="$threadlens_root/.work/A08/m2" -B -ntp verify org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
    printf '%s\n' "$current" > "$threadlens_project/target/build.fingerprint"
  fi
  threadlens_classpath="$threadlens_project/cli/target/classes:$(cat "$threadlens_classpath_file")"
  if [[ -d /workspace/.tools/jdk-25 ]]; then export JAVA_HOME=/workspace/.tools/jdk-25;export PATH="$JAVA_HOME/bin:$PATH"; fi
}
threadlens_cli() { java -Xmx512m -Dthreadlens.sourceFingerprint="$(threadlens_fingerprint)" -Dthreadlens.sourceRevision="$(git -C "$threadlens_root" rev-parse HEAD)" -cp "$threadlens_classpath" io.github.cheshmakzanoon.portfolio.threadlens.cli.ThreadLensCli "$@"; }
