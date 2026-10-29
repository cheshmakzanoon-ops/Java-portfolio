#!/usr/bin/env bash
# Read-only operator tool for a durable command log: book, replay, verify and a narrated tour.
set -euo pipefail
source "$(dirname "$0")/common.sh"
thepit_build
if [[ ${1:-} == tour && -z ${2:-} ]]; then
 # An unnamed tour writes a fresh journal under the ignored runtime directory.
 run_dir="$(mktemp -d "$THEPIT_DATA/tour.XXXXXX")"
 set -- tour "$run_dir/commands.log"
fi
exec java -Xmx512m -cp "$classpath" io.github.cheshmakzanoon.portfolio.thepit.PitCli "$@"
