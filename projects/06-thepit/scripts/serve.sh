#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
thepit_build
exec java -Xmx512m -cp "$classpath" io.github.cheshmakzanoon.portfolio.thepit.HttpApi "$THEPIT_DATA/api.log" demo "${THEPIT_PORT:-18600}" "${THEPIT_ISSUER:-trusted-local-demo}"
