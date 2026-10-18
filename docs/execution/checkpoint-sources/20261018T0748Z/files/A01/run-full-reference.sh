#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
# Prepared product scripts activate the pinned environment themselves; this launcher never builds.
exec python3 "$ROOT/.work/A01/run-full-reference.py" "$@"
