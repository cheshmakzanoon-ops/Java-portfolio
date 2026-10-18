import datetime, importlib.util, json, pathlib, subprocess, time

repo=pathlib.Path('/workspace/Java-portfolio')
project=repo/'projects/05-tenantshield'
spec=importlib.util.spec_from_file_location('walkthrough',project/'demo/walkthrough.py')
walkthrough=importlib.util.module_from_spec(spec)
spec.loader.exec_module(walkthrough)
token=walkthrough.token('alice')
url='http://127.0.0.1:18501/v1/tenants/harbor/documents/doc-7'
before,_=walkthrough.call('GET',url,token)
assert before==403
compose=['docker','compose','-f',str(project/'infra/compose.yaml')]
subprocess.run(compose+['pause','postgres'],check=True)
try:
    outage,_=walkthrough.call('GET',url,token)
    assert outage==503, outage
finally:
    subprocess.run(compose+['unpause','postgres'],check=True)
deadline=time.monotonic()+20
while True:
    after,_=walkthrough.call('GET',url,token)
    if after==403:break
    if time.monotonic()>deadline:raise AssertionError(('recovery',after))
    time.sleep(.1)
fingerprint=subprocess.check_output(['python3',str(project/'scripts/fingerprint.py')],text=True).strip()
report={'schemaVersion':1,'project':'tenantshield','sourceRevision':fingerprint,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'environment.json','workload':'real-protected-operation-postgresql-outage-recovery-v1','command':'python3 .work/A05/protected-outage.py','exitCode':0,'assertions':{'passed':3,'failed':0},'rawArtifact':'.work/A05/protected-outage.log','measurements':{'protectedBefore':before,'protectedDuringDatabasePause':outage,'protectedAfterRecovery':after},'limitations':['Disposable local stack; this demonstrates failure refusal and recovery, not an availability guarantee.','Run follows the asserted demo, so Alice remains revoked; recovery must preserve DENY.']}
(project/'evidence/protected-outage.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS protected resource before/during/after database pause:',before,outage,after)
