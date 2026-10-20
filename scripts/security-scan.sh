#!/usr/bin/env bash
# Refresh the complete library inventory and fail on known HIGH/CRITICAL findings.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
  echo 'This scanner bootstrap supports Linux x86-64; use the pinned Trivy release on other systems.' >&2
  exit 2
fi
if [[ -x /workspace/.tools/jdk-25/bin/java ]]; then source "$root/scripts/cloud-env.sh"; fi
security_dir="$root/.work/security"
scanner_dir="$security_dir/tools/trivy-0.70.0"
scanner="$scanner_dir/trivy"
archive_sha=8b4376d5d6befe5c24d503f10ff136d9e0c49f9127a4279fd110b727929a5aa9
binary_sha=379d59f24a4a828c55de5f0b91b6805cc35d13580180b658820e648611256166
mkdir -p "$scanner_dir"
if [[ ! -f $scanner ]]; then
  staging=$(mktemp -d "$scanner_dir/install.XXXXXX")
  trap 'rm -rf "$staging"' EXIT
  curl -fsSL --retry 2 \
    https://github.com/aquasecurity/trivy/releases/download/v0.70.0/trivy_0.70.0_Linux-64bit.tar.gz \
    -o "$staging/archive.tar.gz"
  printf '%s  %s\n' "$archive_sha" "$staging/archive.tar.gz" | sha256sum -c -
  tar -xzf "$staging/archive.tar.gz" -C "$staging" trivy
  printf '%s  %s\n' "$binary_sha" "$staging/trivy" | sha256sum -c -
  mv "$staging/trivy" "$scanner"
  rm -rf "$staging"
  trap - EXIT
fi
printf '%s  %s\n' "$binary_sha" "$scanner" | sha256sum -c -
# Always regenerate: scanning an old checked-in inventory can miss newly added dependencies.
"$root/mvnw" -Dmaven.repo.local="$security_dir/m2" -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom
"$scanner" --cache-dir "$security_dir/trivy-cache" sbom \
  --db-repository ghcr.io/aquasecurity/trivy-db:2 \
  --scanners vuln --severity HIGH,CRITICAL --no-progress \
  --format json --output "$security_dir/dependency-scan.json" --exit-code 1 --timeout 5m \
  "$root/target/bom.json"
