#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
source scripts/cloud-env.sh
source projects/08-threadlens/scripts/common.sh
# Coordinator must reserve the quiet host before invoking this script.
: "${THREADLENS_REFERENCE_RESERVATION:?Coordinator quiet-host reservation description required}"
export THREADLENS_REFERENCE_INPUT=/workspace/Java-portfolio/.work/A08/reference-study-7-inputs
export THREADLENS_REFERENCE_OUTPUT=/workspace/Java-portfolio/.work/A08/reference-study-7
export THREADLENS_REFERENCE_FINGERPRINT="$(threadlens_fingerprint)"
[[ ! -e "$THREADLENS_REFERENCE_INPUT" && ! -e "$THREADLENS_REFERENCE_OUTPUT" ]] || { echo 'Refusing to overwrite reference artifacts' >&2; exit 2; }
python3 - <<'PY'
from pathlib import Path
from datetime import datetime,timezone
import json,hashlib,os,shutil,subprocess,tarfile
r=Path.cwd();p=r/'projects/08-threadlens';dest=Path(os.environ['THREADLENS_REFERENCE_INPUT']);dest.mkdir();runtime=dest/'runtime';runtime.mkdir();paths=[p/'cli/target/threadlens-cli-1.0.0-SNAPSHOT.jar',*[Path(s)for s in(p/'cli/target/runtime-classpath.txt').read_text().strip().split(':')]];copied=[]
for i,path in enumerate(paths):
 if not path.is_file():raise RuntimeError('reference requires immutable packaged artifacts: '+str(path))
 target=runtime/f'{i:02d}-{path.name}';shutil.copy2(path,target);copied.append({'original':str(path),'runtimeCopy':str(target),'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
agent=dest/'threadlens-agent.jar';shutil.copy2(p/'agent/target/threadlens-agent-1.0.0-SNAPSHOT.jar',agent)
files=[f for f in p.rglob('*')if f.is_file()and'target'not in f.parts and'evidence'not in f.parts];files +=[r/'pom.xml',r/'mvnw',*[f for f in(r/'.mvn').rglob('*')if f.is_file()]]
with tarfile.open(dest/'source.tar.gz','w:gz')as archive:
 for f in sorted(set(files)):archive.add(f,arcname=f.relative_to(r))
revision=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip();cp=':'.join(x['runtimeCopy']for x in copied)
command=[str(Path(os.environ['JAVA_HOME'])/'bin/java'),'-Xmx512m','-XX:ActiveProcessorCount=4','-Dthreadlens.sourceFingerprint='+os.environ['THREADLENS_REFERENCE_FINGERPRINT'],'-Dthreadlens.sourceRevision='+revision,'-cp',cp,'io.github.cheshmakzanoon.portfolio.threadlens.cli.ThreadLensCli','benchmark',os.environ['THREADLENS_REFERENCE_OUTPUT'],str(agent)]
manifest={'schemaVersion':1,'sourceRevision':revision,'buildInputFingerprint':os.environ['THREADLENS_REFERENCE_FINGERPRINT'],'preparedUtc':datetime.now(timezone.utc).isoformat(),'exclusiveHostReservation':os.environ['THREADLENS_REFERENCE_RESERVATION'],'command':command,'runtimeArtifacts':copied,'agentSha256':hashlib.sha256(agent.read_bytes()).hexdigest(),'sourceArchiveSha256':hashlib.sha256((dest/'source.tar.gz').read_bytes()).hexdigest(),'protocol':{'workloads':['cpu','allocation','contention','virtual'],'randomSeed':42908,'pairedBlocksPerWorkload':7,'modes':['baseline','jfr','instrumented'],'warmupSeconds':30,'measurementSeconds':60,'freshJvmRuns':84,'fixedObservationSeconds':7560}}
(dest/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');shutil.copy2(p/'evidence/verification.json',dest/'functional-certificate.json')
with(dest/'launcher.log').open('w')as log:result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
manifest['completedUtc']=datetime.now(timezone.utc).isoformat();manifest['launcherExitCode']=result.returncode
if result.returncode==0:
 summary=json.loads((Path(os.environ['THREADLENS_REFERENCE_OUTPUT'])/'summary.json').read_text());checks=[(workload,mode,values['throughputGatePass'] and values['p99GatePass'])for workload,modes in summary.items()for mode,values in modes.items()];manifest['overheadGateChecks']=[{'workload':w,'mode':m,'passed':passed}for w,m,passed in checks];manifest['allOverheadGatesPass']=len(checks)==8 and all(x[2]for x in checks)
(dest/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');raise SystemExit(result.returncode or (0 if manifest.get('allOverheadGatesPass')else 3))
PY
