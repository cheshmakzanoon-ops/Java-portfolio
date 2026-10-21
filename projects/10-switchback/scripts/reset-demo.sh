#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
health
curl --fail --silent --show-error -X POST "http://127.0.0.1:${SWITCHBACK_PORT:-19000}/v1/demo/reset"
