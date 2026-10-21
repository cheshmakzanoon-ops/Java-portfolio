#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
for command in java docker python3; do command -v "$command" >/dev/null || { echo "Required: $command" >&2; exit 1; }; done
if [[ ${HANDOFF_PREPARED:-0} == 1 ]]; then
  python3 "$PROJECT/scripts/prepared-build.py" verify
elif python3 "$PROJECT/scripts/lifecycle.py" running; then
  : # Existing owned runtimes retain their built artifacts.
else
  outcome=$?
  [[ "$outcome" == 1 ]] || exit "$outcome"
  build
fi
exec python3 "$PROJECT/scripts/lifecycle.py" up
