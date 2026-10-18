#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
if ! python3 "$PROJECT_DIR/scripts/build-cache.py" verify; then
  if [[ "${TENANTSHIELD_NO_MAVEN:-0}" == 1 ]]; then
    echo 'No-Maven mode refuses absent, stale or modified verified artifacts' >&2
    exit 1
  fi
  before="$(fingerprint)"
  rm -f "$RUNTIME_DIR/build-manifest.json" "$RUNTIME_DIR/build.fingerprint"
  "$REPO_DIR/mvnw" -f "$PROJECT_DIR/pom.xml" "-Dmaven.repo.local=$M2_DIR" -B -ntp verify
  python3 "$PROJECT_DIR/scripts/build-cache.py" record "$before"
fi
CP="$(classpath)"
bounded 30 java -Xmx128m -cp "$CP" org.openjdk.jmh.Main -l > "$RUNTIME_DIR/jmh-discovery.txt"
python3 - "$RUNTIME_DIR/jmh-discovery.txt" <<'PY'
from pathlib import Path
import sys
prefix = 'io.github.cheshmakzanoon.portfolio.tenantshield.PolicyBenchmark.'
expected = {prefix + 'coldCompilation', prefix + 'warmIndexedEvaluation'}
observed = {line.strip() for line in Path(sys.argv[1]).read_text().splitlines() if line.strip() and line.strip() != 'Benchmarks:'}
assert observed == expected, 'Actual JMH discovery differs from both expected benchmarks: ' + repr(observed)
PY
echo 'Verified TenantShield runtime cache ready; no containers or services started'
