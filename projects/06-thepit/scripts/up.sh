#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
# This CLI-first project requires no background service for the asserted demonstration.
printf 'ThePit CLI ready; run scripts/demo.sh. Optional API: scripts/serve.sh\n'
