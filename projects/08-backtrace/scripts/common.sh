#!/usr/bin/env bash
set -euo pipefail
backtrace_project=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
backtrace_root=$(cd "$backtrace_project/../.." && pwd)
backtrace_runtime="$backtrace_project/target/demo"
backtrace_classpath_file="$backtrace_project/cli/target/runtime-classpath.txt"
backtrace_fingerprint() {
  python3 - "$backtrace_project" "$backtrace_root" <<'PY'
import hashlib,sys
from pathlib import Path
project,root=map(Path,sys.argv[1:]); files=[root/'pom.xml',root/'mvnw',*sorted((root/'.mvn').rglob('*'))]
files += [p for p in project.rglob('*') if p.is_file() and 'target' not in p.parts and '__pycache__' not in p.parts and ('src' in p.parts or p.name=='pom.xml' or 'scripts' in p.parts)]
h=hashlib.sha256()
for p in sorted(set(files)):
 if p.is_file(): h.update(str(p.relative_to(root)).encode());h.update(p.read_bytes())
print(h.hexdigest())
PY
}
backtrace_prepare() {
  command -v java >/dev/null || { echo 'Install JDK 25 and put java on PATH.' >&2; return 1; }
  local current cached=''
  current=$(backtrace_fingerprint)
  [[ ! -f "$backtrace_project/target/build.fingerprint" ]] || cached=$(cat "$backtrace_project/target/build.fingerprint")
  if [[ "$current" != "$cached" || ! -s "$backtrace_classpath_file" ]]; then
    if [[ -d /workspace/.tools/jdk-25 ]]; then source "$backtrace_root/scripts/cloud-env.sh"; fi
    "$backtrace_root/mvnw" -f "$backtrace_project/pom.xml" -Dmaven.repo.local="$backtrace_root/.work/A08/m2" -B -ntp verify org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
    printf '%s\n' "$current" > "$backtrace_project/target/build.fingerprint"
  fi
  backtrace_classpath="$backtrace_project/cli/target/classes:$(cat "$backtrace_classpath_file")"
  if [[ -d /workspace/.tools/jdk-25 ]]; then export JAVA_HOME=/workspace/.tools/jdk-25;export PATH="$JAVA_HOME/bin:$PATH"; fi
}
backtrace_cli() { java -Xmx512m -Dbacktrace.sourceFingerprint="$(backtrace_fingerprint)" -Dbacktrace.sourceRevision="$(git -C "$backtrace_root" rev-parse HEAD)" -cp "$backtrace_classpath" io.github.cheshmakzanoon.portfolio.backtrace.cli.BacktraceCli "$@"; }
