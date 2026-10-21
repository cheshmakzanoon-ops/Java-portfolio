#!/usr/bin/env bash
# Install only the pinned toolchain; never alter tracked application files.
set -euo pipefail
if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
  echo 'This bootstrap supports Linux x86_64. Install Eclipse Temurin JDK25 on other platforms.' >&2
  exit 2
fi
portfolio_tools=${PORTFOLIO_TOOLS_DIR:-/workspace/.tools}
portfolio_archive=OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz
portfolio_sha=dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e
if [[ ! -x $portfolio_tools/jdk-25/bin/javac ]]; then
  mkdir -p "$portfolio_tools/downloads"
  if [[ ! -f $portfolio_tools/downloads/$portfolio_archive ]]; then
    portfolio_download=$(mktemp "$portfolio_tools/downloads/jdk-25.download.XXXXXX")
    trap 'rm -f "$portfolio_download"' EXIT
    curl -fsSL --retry 2 "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/$portfolio_archive" -o "$portfolio_download"
    printf '%s  %s\n' "$portfolio_sha" "$portfolio_download" | sha256sum -c -
    mv "$portfolio_download" "$portfolio_tools/downloads/$portfolio_archive"
  fi
  (cd "$portfolio_tools/downloads"; printf '%s  %s\n' "$portfolio_sha" "$portfolio_archive" | sha256sum -c -)
  portfolio_extract=$(mktemp -d "$portfolio_tools/jdk-25.staging.XXXXXX")
  trap 'rm -rf "$portfolio_extract"' EXIT
  tar -xzf "$portfolio_tools/downloads/$portfolio_archive" --strip-components=1 -C "$portfolio_extract"
  if [[ -e $portfolio_tools/jdk-25 ]]; then
    echo 'Existing incomplete JDK25 installation must be inspected before replacement.' >&2
    exit 1
  fi
  mv "$portfolio_extract" "$portfolio_tools/jdk-25"
fi
portfolio_version=$("$portfolio_tools/jdk-25/bin/java" -version 2>&1)
if [[ $portfolio_version != *'25.0.4.1+1'* || $portfolio_version != *Temurin* ]]; then
  echo 'Existing JDK differs from pinned Temurin25.0.4.1+1; inspect installation before replacement.' >&2
  exit 1
fi
printf '%s\n' "$portfolio_version"
"$portfolio_tools/jdk-25/bin/javac" -version
