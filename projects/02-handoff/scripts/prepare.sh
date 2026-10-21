#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
export HANDOFF_PREPARED=0
python3 "$PROJECT/scripts/prepared-build.py" idle
before=$(python3 "$PROJECT/scripts/prepared-build.py" snapshot)
rm -f "$PROJECT/target/prepared-build.json"
echo "Verifying Handoff; full output: $PROJECT/target/preparation.log"
build > "$PROJECT/target/preparation.log" 2>&1
python3 "$PROJECT/scripts/prepared-build.py" stamp --expected "$before"
echo 'Prepared mode ready. HANDOFF_PREPARED=1 performs hash validation without Maven.'
