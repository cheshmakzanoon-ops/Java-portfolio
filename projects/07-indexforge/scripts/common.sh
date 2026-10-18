#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
repo_dir=$(cd "$project_dir/../.." && pwd)
if [[ -f "$repo_dir/scripts/cloud-env.sh" && -d /workspace/.tools/jdk-25 ]]; then source "$repo_dir/scripts/cloud-env.sh"; fi
command -v java >/dev/null || { echo 'JDK 25 is required' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python3 is required for source fingerprint validation' >&2; exit 1; }
jar="$project_dir/cli/target/indexforge-cli-1.0.0-SNAPSHOT.jar"
data="$project_dir/.runtime/demo"
source_fingerprint() { python3 - "$project_dir" <<'PY'
import hashlib,sys
from pathlib import Path
root=Path(sys.argv[1]);h=hashlib.sha256()
for p in sorted(root.rglob('*')):
 if p.is_file() and not set(p.relative_to(root).parts)&{'target','.runtime','evidence'} and p.suffix in {'.java','.xml','.tsv','.jsonl','.sh'}:
  h.update(p.relative_to(root).as_posix().encode()+b'\0'+p.read_bytes())
for p in [root.parent.parent/'pom.xml',root.parent.parent/'AGENTS.md']:
 h.update(p.name.encode()+p.read_bytes())
print(h.hexdigest())
PY
}
require_build() {
 fingerprint=$(source_fingerprint)
 if [[ ! -f "$jar" || ! -f "$project_dir/.runtime/tested-source" || "$(cat "$project_dir/.runtime/tested-source")" != "$fingerprint" ]]; then
  echo 'A verified build for this source fingerprint is required. Run projects/07-indexforge/scripts/verify.sh (coordinate a Maven slot in shared cloud runs).' >&2
  exit 1
 fi
}
run_cli() { timeout "${INDEXFORGE_TIMEOUT:-300}" java --enable-native-access=ALL-UNNAMED -Xms128m -Xmx2g -jar "$jar" "$@"; }
