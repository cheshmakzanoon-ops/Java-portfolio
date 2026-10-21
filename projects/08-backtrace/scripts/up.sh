#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
backtrace_prepare
mkdir -p "$backtrace_runtime"
backtrace_cli capabilities > "$backtrace_runtime/capabilities.json"
python3 - "$backtrace_runtime/capabilities.json" <<'PY'
import json,sys
c=json.load(open(sys.argv[1]));assert c['jdk.ExecutionSample']['supported'];print('Backtrace CLI ready; no network listener or background service.')
PY
