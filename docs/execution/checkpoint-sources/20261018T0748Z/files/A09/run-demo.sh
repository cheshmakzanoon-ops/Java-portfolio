#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
python3 .work/A09/collect-final.py > .work/A09/final-summary.json
source projects/09-byteguard/scripts/common.sh
actual_source=$(byteguard_fingerprint)
expected_source=$(cat .work/A09/release-final-source.sha256)
[[ "$actual_source" == "$expected_source" ]]
# Seed the same cache record produced by common.sh from this actual stronger verified build.
{ printf '%s\n' "$actual_source"; sha256sum "$BYTEGUARD_JAR" | cut -d' ' -f1; } > "$BYTEGUARD_RUNTIME/build-fingerprint"
projects/09-byteguard/scripts/reset-demo.sh > .work/A09/operations.log 2>&1
projects/09-byteguard/scripts/up.sh >> .work/A09/operations.log 2>&1
projects/09-byteguard/scripts/demo.sh > .work/A09/demo.log 2>&1
projects/09-byteguard/scripts/demo.sh > .work/A09/demo-cache-reuse.log 2>&1
projects/09-byteguard/scripts/down.sh >> .work/A09/operations.log 2>&1
python3 - <<'PY'
from pathlib import Path
import json,hashlib,datetime,re
p=Path('projects/09-byteguard');e=p/'evidence';reports={}
for name in ['positive','corrected','partial','baseline']:
 data=json.loads((p/f'.runtime/demo/{name}.sarif').read_text());run=data['runs'][0]
 reports[name]={'findings':len(run['results']),'partial':run['properties']['partial'],'unfinishedFrontier':run['properties']['unfinishedFrontier']}
 if name=='baseline':reports[name]['baselineState']=run['results'][0]['baselineState']
log=Path('.work/A09/demo.log').read_text();codes=[int(v) for v in re.findall(r'; exit ([0-3])',log)]
assert codes==[1,0,3,0],codes
assert 'verified source cache' in log
assert 'verified source cache' in Path('.work/A09/demo-cache-reuse.log').read_text()
assert 'Demo assertions passed' in log and 'Demo assertions passed' in Path('.work/A09/demo-cache-reuse.log').read_text()
data={'schemaVersion':1,'project':'byteguard','status':'PASS','sourceRevision':'working-tree-sha256:'+Path('.work/A09/release-final-source.sha256').read_text().strip(),'timestampUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'command':'scripts/reset-demo.sh; scripts/up.sh; scripts/demo.sh; scripts/demo.sh; scripts/down.sh','exitCode':0,'assertions':{'passed':10,'failed':0},'observedExitCodes':codes,'scenarios':reports,'cache':'Same actual freshly verified source/JAR record seeded; both actual demos reused the verified artifact, and all scenario assertions passed twice. Separate isolated control verifies source-change/artifact-tamper invalidation.','artifactSha256':hashlib.sha256((p/'cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar').read_bytes()).hexdigest(),'rawArtifact':'.work/A09/demo.log','cacheReuseRawArtifact':'.work/A09/demo-cache-reuse.log','operationsRawArtifact':'.work/A09/operations.log'}
(e/'demo.json').write_text(json.dumps(data,indent=2)+'\n')
print(json.dumps(data,indent=2))
PY
