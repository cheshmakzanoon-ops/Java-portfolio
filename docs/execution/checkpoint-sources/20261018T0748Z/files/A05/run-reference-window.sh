#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
source projects/05-tenantshield/scripts/common.sh
[[ "${TENANTSHIELD_QUIET_GRANTED:-0}" == 1 ]] || { echo 'Coordinator quiet reservation must be explicitly granted' >&2; exit 1; }
expected="$(cat .work/A05/reference-source.txt)"
[[ "$(fingerprint)" == "$expected" ]] || { echo 'Prepared source changed; no reference run permitted' >&2; exit 1; }
export TENANTSHIELD_NO_MAVEN=1
require_current_build
python3 - <<'PY'
import subprocess
rows=subprocess.check_output(['ps','-eo','stat=,comm='],text=True).splitlines()
active=[r for r in rows if len(r.split())==2 and r.split()[1]=='java' and not r.split()[0].startswith('Z')]
assert not active, 'Other active Java processes remain; refuse quiet measurement'
PY
projects/05-tenantshield/scripts/prepare-runtime.sh > .work/A05/reference-cache-check.log 2>&1
finish() {
  local result=$?
  trap - EXIT
  projects/05-tenantshield/scripts/down.sh > .work/A05/performance-down.log 2>&1 || result=1
  printf '%s\n' "$result" > .work/A05/reference-exit-code.txt
  exit "$result"
}
trap finish EXIT
projects/05-tenantshield/scripts/up.sh > .work/A05/performance-up.log 2>&1
[[ "$(fingerprint)" == "$expected" ]] || { echo 'Source changed during startup; no reference run permitted' >&2; exit 1; }
python3 - <<'PY'
import datetime,hashlib,json,pathlib,platform,subprocess
p=pathlib.Path('projects/05-tenantshield/evidence/environment.json')
old=p.read_text()
pathlib.Path('.work/A05/environment-before-reference.json').write_text(old)
x=json.loads(old)
x['sourceRevision']=pathlib.Path('.work/A05/reference-source.txt').read_text().strip()
x['capturedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
x['performanceIsolation']='Coordinator explicitly granted quiet host; no other active Java process at protocol entry; own PostgreSQL/Keycloak and two required services run during measurements'
x['measurementClass']='quiet-baseline'
x['kernel']=platform.release()
x['cpuQuota']=pathlib.Path('/sys/fs/cgroup/cpu.max').read_text().strip()
x['memoryLimit']=pathlib.Path('/sys/fs/cgroup/memory.max').read_text().strip()
x['rootPomSha256']=hashlib.sha256(pathlib.Path('pom.xml').read_bytes()).hexdigest()
x['runtimeArtifactManifest']=json.loads(pathlib.Path('.work/A05/runtime/build-manifest.json').read_text())
x['ownServicePids']={s:int(pathlib.Path('.work/A05/runtime/'+s+'.pid').read_text()) for s in ['decision-service','demo-resource-server']}
p.write_text(json.dumps(x,indent=2)+'\n')
PY
projects/05-tenantshield/scripts/benchmark.sh > .work/A05/performance.log 2>&1
python3 - <<'PY'
import json,pathlib
p=pathlib.Path('projects/05-tenantshield/evidence')
rows=[json.loads((p/f'http-{n}.json').read_text()) for n in [1,2,3]]
for row in rows:
 assert row['requests']==100000 and row['concurrency']==16 and row['exitCode']==0
 assert row['measurements']['errors']==0 and row['measurements']['denials']==20000
assert len({r['sourceRevision'] for r in rows})==1
assert json.loads((p/'jmh-envelope.json').read_text())['exitCode']==0
print('All three full 100000-request runs and three-fork JMH completed successfully')
PY
