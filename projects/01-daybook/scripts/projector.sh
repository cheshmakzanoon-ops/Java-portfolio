#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
prepare_classes
exec java -Xmx256m -cp "$CP" io.github.cheshmakzanoon.portfolio.daybook.Projector "$@"
