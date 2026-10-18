#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
matchcore_build
exec java -Xmx512m -cp "$classpath" io.github.cheshmakzanoon.portfolio.matchcore.HttpApi "$MATCHCORE_DATA/api.log" demo "${MATCHCORE_PORT:-18600}" "${MATCHCORE_ISSUER:-trusted-local-demo}"
