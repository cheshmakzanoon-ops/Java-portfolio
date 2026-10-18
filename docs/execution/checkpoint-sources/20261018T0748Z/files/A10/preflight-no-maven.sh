#!/usr/bin/env bash
set -euo pipefail
root=/workspace/Java-portfolio
source "$root/projects/10-routeweaver/scripts/common.sh"
require_build
python3 - "$project" "$ROUTEWEAVER_SOURCE_REVISION" <<'PY'
import ast,hashlib,json,sys,zipfile
from pathlib import Path
p=Path(sys.argv[1]);m=json.loads((p/'evidence/artifacts-current.json').read_text())
assert m['sourceRevision']=='working-tree-sha256:'+sys.argv[2]
for a in m['artifacts']:
    f=p/a['path'];assert hashlib.sha256(f.read_bytes()).hexdigest()==a['sha256'],str(f)
with zipfile.ZipFile(p/'routeweaver-service/target/routeweaver-service-1.0.0-SNAPSHOT-exec.jar') as z:
    assert z.read('BOOT-INF/classes/static/index.html')==(p/'routeweaver-service/src/main/resources/static/index.html').read_bytes()
ast.parse((p/'scripts/browser-check.py').read_text())
print('Source stamp, service/driver artifact hashes, packaged HTML and browser helper syntax: PASS')
PY
"$JAVA_HOME/bin/java" -jar "$project/routeweaver-verification/target/routeweaver-verification-1.0.0-SNAPSHOT-driver.jar" fixture > "$root/.work/A10/final-artifact-fixture.json"
python3 - "$root/.work/A10/final-artifact-fixture.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]));assert len(p['jobs'])==60 and len(p['vehicles'])==6
assert any('urgent' in v['skills'] and not v['onboard'] and v['currentLocation']==p['depot'] and not v['failed'] for v in p['vehicles'])
print('Self-contained driver fixture: 60 jobs/six vehicles with depot standby: PASS')
PY
"$JAVA_HOME/bin/java" -cp "$project/routeweaver-verification/target/routeweaver-verification-1.0.0-SNAPSHOT-driver.jar" org.openjdk.jmh.Main -l > "$root/.work/A10/final-jmh-discovery.log"
python3 - "$root/.work/A10/final-jmh-discovery.log" <<'PY'
import sys
s=open(sys.argv[1]).read();assert 'ValidationBenchmark.fullReferenceValidation' in s and 'ValidationBenchmark.immutableProblemImportValidation' in s
print('Both full route-validation/problem-import JMH methods discovered without measurement: PASS')
PY
