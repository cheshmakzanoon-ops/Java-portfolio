#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
python3 - "$backtrace_runtime" <<'PY'
import shutil,sys
from pathlib import Path
p=Path(sys.argv[1]);assert p.name=='demo' and p.parent.name=='target';shutil.rmtree(p,ignore_errors=True)
print('Removed only Backtrace target/demo generated fixtures and reports.')
PY
