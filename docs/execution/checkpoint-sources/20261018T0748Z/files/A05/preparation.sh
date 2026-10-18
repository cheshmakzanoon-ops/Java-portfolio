#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
source projects/05-tenantshield/scripts/common.sh
require_current_build
require_running_build
wait_for_services
trap 'projects/05-tenantshield/scripts/down.sh' EXIT
export TENANTSHIELD_SOURCE_FINGERPRINT="$(fingerprint)"
export TENANTSHIELD_MIGRATION_URL=jdbc:postgresql://127.0.0.1:18503/tenantshield
export TENANTSHIELD_MIGRATION_USER=migration
export TENANTSHIELD_MIGRATION_PASSWORD=local-migration-only
bounded 1800 java -Xmx512m -cp "$(classpath)" io.github.cheshmakzanoon.portfolio.tenantshield.Workload seed "$RUNTIME_DIR/seed.json"
unset TENANTSHIELD_MIGRATION_USER TENANTSHIELD_MIGRATION_PASSWORD TENANTSHIELD_MIGRATION_URL
counts() {
  compose exec -T postgres psql -X -A -t -v ON_ERROR_STOP=1 -U migration -d tenantshield -c "SELECT json_build_object('tenants',(SELECT count(*) FROM tenant WHERE tenant_id LIKE 'load-%'),'policyVersions',(SELECT count(*) FROM policy_version WHERE tenant_id LIKE 'load-%'),'roles',(SELECT count(*) FROM role WHERE tenant_id LIKE 'load-%'),'roleEdges',(SELECT count(*) FROM role_edge WHERE tenant_id LIKE 'load-%'),'resources',(SELECT count(*) FROM resource WHERE tenant_id LIKE 'load-%'),'activePolicies',(SELECT count(*) FROM active_policy WHERE tenant_id LIKE 'load-%'));"
}
counts > .work/A05/seed-before-reset.json
python3 - <<'PY'
import json
x=json.load(open('.work/A05/seed-before-reset.json'))
assert x == dict(tenants=50,policyVersions=10000,roles=1000000,roleEdges=990000,resources=50000,activePolicies=50),x
print('Full seed row counts verified:',x)
PY
projects/05-tenantshield/scripts/demo.sh
cp projects/05-tenantshield/evidence/demo.json .work/A05/demo-before-reset.json
projects/05-tenantshield/scripts/reset-demo.sh
counts > .work/A05/seed-after-reset.json
python3 - <<'PY'
import json
assert json.load(open('.work/A05/seed-before-reset.json')) == json.load(open('.work/A05/seed-after-reset.json'))
print('Demo reset preserves all prescribed workload rows')
PY
compose exec -T postgres psql -X -A -t -v ON_ERROR_STOP=1 -U migration -d tenantshield -c "SELECT count(*) FROM tenant WHERE tenant_id IN ('harbor','orchard');" > .work/A05/demo-tenant-count-after-reset.txt
[[ "$(cat .work/A05/demo-tenant-count-after-reset.txt)" == 0 ]]
projects/05-tenantshield/scripts/up.sh
projects/05-tenantshield/scripts/demo.sh
python3 - <<'PY'
import json,datetime,os,pathlib
p=pathlib.Path('projects/05-tenantshield/evidence/preparation.json')
first=json.load(open('.work/A05/demo-before-reset.json'))
second=json.load(open('projects/05-tenantshield/evidence/demo.json'))
assert first['exitCode']==second['exitCode']==0
assert first['assertions']['passed']==second['assertions']['passed']==21
p.write_text(json.dumps(dict(schemaVersion=1,project='tenantshield',sourceRevision=os.environ['TENANTSHIELD_SOURCE_FINGERPRINT'],timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),environment='environment.json',workload='full-seed-demo-reset-repeat-preparation-v1',command='.work/A05/preparation.sh',rawArtifact='../../../.work/A05/preparation.log',exitCode=0,assertions=dict(passed=4,failed=0),seedRows=json.load(open('.work/A05/seed-after-reset.json')),demoAssertionsPerRun=21,limitations=['Preparation only: no performance measurements or quiet-window claim.']),indent=2)+'\n')
PY
projects/05-tenantshield/scripts/down.sh
trap - EXIT
