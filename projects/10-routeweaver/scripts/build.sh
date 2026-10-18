#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
cd "$root"
./mvnw -f "$project/pom.xml" -Dmaven.repo.local="$root/.work/A10/m2" -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply
./mvnw -f "$project/pom.xml" -Dmaven.repo.local="$root/.work/A10/m2" -B -ntp verify
fingerprint > "$runtime/build-stamp"
