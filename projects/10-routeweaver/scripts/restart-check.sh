#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
health
"$JAVA_HOME/bin/java" -Xmx768m -jar "$project/routeweaver-verification/target/routeweaver-verification-1.0.0-SNAPSHOT-driver.jar" fixture > "$runtime/restart-fixture.json"
python3 - "$project" "${ROUTEWEAVER_PORT:-19000}" <<'PY' | tee "$runtime/restart-result.json"
import json,os,signal,subprocess,sys,time,urllib.request
from pathlib import Path
project=Path(sys.argv[1]); runtime=project/'.runtime'; base='http://127.0.0.1:'+sys.argv[2]
def api(path,body=None):
    headers={'Content-Type':'application/json'}
    token=os.environ.get('ROUTEWEAVER_DEMO_TOKEN')
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    with urllib.request.urlopen(request,timeout=10) as response:return json.load(response)
p=json.loads((runtime/'restart-fixture.json').read_text());p['id']='routeweaver-restart-42'
api('/v1/problems',p)
job=api('/v1/problems/'+p['id']+'/solve',{'expectedVersion':1,'requestId':'restart-check-v1','seed':42,'budgetMs':30000,'steps':100000})
deadline=time.monotonic()+15
while time.monotonic()<deadline:
    before=api('/v1/solve-jobs/'+job['id'])
    if before['state']=='RUNNING' and before.get('incumbent'):break
    assert before['state'] not in ('COMPLETED','FAILED','CANCELLED'),before['state']
    time.sleep(.1)
else:raise AssertionError('worker did not establish a validated incumbent before crash deadline')
import importlib.util
spec=importlib.util.spec_from_file_location('routeweaver_lifecycle',project/'scripts/lifecycle.py')
lifecycle=importlib.util.module_from_spec(spec);spec.loader.exec_module(lifecycle)
jar=project/'routeweaver-service/target/routeweaver-service-1.0.0-SNAPSHOT-exec.jar'
java=Path(os.environ['JAVA_HOME'])/'bin/java'
pid=lifecycle.owned(runtime,jar,java)
assert pid is not None,'owned service must be running before crash'
lifecycle.signal_owned(runtime,jar,java,pid,signal.SIGKILL)
stop_deadline=time.monotonic()+5
while lifecycle.process(pid) is not None and time.monotonic()<stop_deadline:time.sleep(.02)
assert lifecycle.process(pid) is None,'crashed process did not exit before restart'
subprocess.run([str(project/'scripts/up.sh')],check=True,timeout=120,stdout=subprocess.DEVNULL)
deadline=time.monotonic()+75
while time.monotonic()<deadline:
    after=api('/v1/solve-jobs/'+job['id'])
    if after['state'] in ('COMPLETED','CANCELLED','FAILED'):break
    time.sleep(.2)
else:raise AssertionError('durable job remained nonterminal after process restart and lease recovery')
assert after['state']=='COMPLETED',after
assert after['fence']>before['fence'],(before['fence'],after['fence'])
valid=after['incumbent'];assert valid and valid['score']['hard']==0
def score_key(solution):
    score=solution['score'];return (score['hard'],score['unassignedPriority'],score['operatingCost']+score['disruption'])
assert score_key(valid)<=score_key(before['incumbent']),'same-input retained incumbent must not degrade across restart'
assert valid['candidate']['inputHash']==job['inputHash']
assigned=[j for r in valid['candidate']['routes'] for j in r['jobs']]
assert len(assigned)==len(set(assigned))
assert set(assigned).isdisjoint(valid['candidate']['unassigned'])
assert set(assigned)|set(valid['candidate']['unassigned'])=={j['id'] for j in p['jobs']}
plan=api('/v1/plans/'+p['id']);assert plan['revision']==0 and plan['solution'] is None
print(json.dumps({'schemaVersion':1,'project':'routeweaver','scenario':'actual-service-SIGKILL-and-restart','jobId':job['id'],'state':after['state'],'oldFence':before['fence'],'newFence':after['fence'],'retainedValidatedIncumbent':True,'automaticPublication':False,'source':after['sourceRevision']}))
PY
