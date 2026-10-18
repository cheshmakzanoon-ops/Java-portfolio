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
classpath >/dev/null
echo 'Verified TenantShield runtime cache ready; no containers or services started'
