#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
source scripts/cloud-env.sh
export PORTFOLIO_TEST_RUN_STARTED="$(date +%s)"
printf '%s\n' "$PORTFOLIO_TEST_RUN_STARTED" > .work/A02/gzip-v2-ordinary-cutoff.txt
if ./mvnw -f projects/02-durableflow/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A02/m2 -B -ntp verify > .work/A02/gzip-v2-ordinary.log 2>&1; then
  printf '0\n' > .work/A02/gzip-v2-ordinary-exit.txt
else
  outcome=$?
  printf '%s\n' "$outcome" > .work/A02/gzip-v2-ordinary-exit.txt
  tail -n 70 .work/A02/gzip-v2-ordinary.log
  exit "$outcome"
fi
python3 scripts/check-tests.py projects/02-durableflow verify > .work/A02/gzip-v2-ordinary-checker.log 2>&1
python3 - <<'PY'
from pathlib import Path
import shutil
project=Path('projects/02-durableflow')
destination=Path('.work/A02/gzip-v2-ordinary-reports')
destination.mkdir(exist_ok=False)
for source in project.glob('*/target/surefire-reports/*'):
    if source.is_file() and source.suffix in {'.xml','.txt'}:
        target=destination/source.relative_to(project)
        target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source,target)
PY
export PORTFOLIO_TEST_RUN_STARTED="$(date +%s)"
printf '%s\n' "$PORTFOLIO_TEST_RUN_STARTED" > .work/A02/gzip-v2-preparation-cutoff.txt
if projects/02-durableflow/scripts/prepare.sh > .work/A02/gzip-v2-supported-prepare.log 2>&1; then
  printf '0\n' > .work/A02/gzip-v2-preparation-exit.txt
else
  outcome=$?
  printf '%s\n' "$outcome" > .work/A02/gzip-v2-preparation-exit.txt
  tail -n 70 .work/A02/gzip-v2-supported-prepare.log
  exit "$outcome"
fi
python3 scripts/check-tests.py projects/02-durableflow verify > .work/A02/gzip-v2-preparation-checker.log 2>&1
python3 projects/02-durableflow/scripts/prepared-build.py verify > .work/A02/gzip-v2-prepared-check.log
python3 projects/02-durableflow/scripts/prepared-build.py snapshot > .work/A02/gzip-v2-source-after.json
cmp .work/A02/gzip-v2-source-before.json .work/A02/gzip-v2-source-after.json
echo 'Ordinary verify, fresh discovery, supported preparation and prepared validation PASS.'
