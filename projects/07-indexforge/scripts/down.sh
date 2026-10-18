#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
# CLI operations are bounded processes; no background resources are started by up.
echo 'IndexForge CLI has no running daemon. Local snapshots are preserved.'
