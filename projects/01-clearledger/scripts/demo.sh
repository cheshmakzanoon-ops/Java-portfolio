#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
"$PROJECT/scripts/up.sh"
"$PROJECT/scripts/reset-demo.sh"
export CLEARLEDGER_DEMO_CLASSPATH="$CP"
python3 "$PROJECT/demo/demo.py"
