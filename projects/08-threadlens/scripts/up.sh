#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
threadlens_prepare
mkdir -p "$threadlens_runtime"
threadlens_cli capabilities > "$threadlens_runtime/capabilities.json"
python3 - "$threadlens_runtime/capabilities.json" <<'PY'
import json,sys
c=json.load(open(sys.argv[1]));assert c['jdk.ExecutionSample']['supported'];print('ThreadLens CLI ready; no network listener or background service.')
PY
