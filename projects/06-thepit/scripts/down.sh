#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
printf 'ThePit CLI has no background resources; retained local journals at %s\n' "$THEPIT_DATA"
