from pathlib import Path
import datetime,hashlib,json,os,subprocess,sys,time
root=Path('/workspace/Java-portfolio');work=root/'.work/A09'
started=time.time();source=subprocess.check_output(['bash','-c','source projects/09-byteguard/scripts/common.sh; byteguard_fingerprint'],cwd=root,text=True).strip()
statusfile=work/'clean-certification-shell-exit.txt'
statusfile.unlink(missing_ok=True)
command='''source scripts/cloud-env.sh
set +e
export PORTFOLIO_TEST_RUN_STARTED=$(date +%s)
./mvnw -f projects/09-byteguard/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A09/m2 -B -ntp -Pintegration,mutation clean verify
BYTEGUARD_MAVEN_EXIT=$?
printf '%s\\n' "$BYTEGUARD_MAVEN_EXIT" > .work/A09/clean-certification-shell-exit.txt
exit "$BYTEGUARD_MAVEN_EXIT"
'''
record={'schemaVersion':1,'startedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'startedEpoch':started,'sourceBefore':source,'rootPomSha256Before':hashlib.sha256((root/'pom.xml').read_bytes()).hexdigest(),'command':command,'phase':'running'}
(work/'clean-certification-run.json').write_text(json.dumps(record,indent=2)+'\n')
with (work/'clean-certification.log').open('w') as log:
 result=subprocess.run(['bash','-c',command],cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=2400)
record.update({'phase':'completed','runnerExitCode':result.returncode,'shellMavenExitCode':int(statusfile.read_text().strip()) if statusfile.exists() else None,'completedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsedSeconds':time.time()-started,'sourceAfter':subprocess.check_output(['bash','-c','source projects/09-byteguard/scripts/common.sh; byteguard_fingerprint'],cwd=root,text=True).strip(),'rootPomSha256After':hashlib.sha256((root/'pom.xml').read_bytes()).hexdigest()})
(work/'clean-certification-run.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps(record,indent=2))
assert record['sourceBefore']==record['sourceAfter']
assert record['runnerExitCode']==record['shellMavenExitCode']==0
sys.exit(result.returncode)
