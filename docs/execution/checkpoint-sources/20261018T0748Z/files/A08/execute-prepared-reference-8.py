#!/usr/bin/env python3
"""Execute the unchanged mandatory protocol only after coordinator quiet-host reservation."""
from pathlib import Path
from datetime import datetime,timezone
import collections,hashlib,json,os,subprocess,sys
inputs=Path('/workspace/Java-portfolio/.work/A08/reference-study-8-inputs')
manifest_path=inputs/'manifest.json'
manifest=json.loads(manifest_path.read_text())
reservation=os.environ.get('THREADLENS_REFERENCE_RESERVATION','').strip()
if not reservation or reservation.startswith('PENDING'):raise SystemExit('Explicit coordinator quiet-host reservation required')
if 'executionStartedUtc'in manifest:raise SystemExit('Refusing to overwrite an earlier attempted reference study')
output=Path(manifest['command'][-2])
if output.exists()and any(output.iterdir()):raise SystemExit('Reference output must be fresh; preserve previous trials')
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
for artifact in manifest['runtimeArtifacts']:
 if sha(artifact['runtimeCopy'])!=artifact['sha256']:raise SystemExit('Runtime artifact changed: '+artifact['runtimeCopy'])
if sha(inputs/'threadlens-agent.jar')!=manifest['agentSha256']:raise SystemExit('Agent artifact changed')
if sha(inputs/'source.tar.gz')!=manifest['sourceArchiveSha256']:raise SystemExit('Source archive changed')
java=Path(manifest['command'][0]);jdk_release=java.parent.parent/'release'
if sha(jdk_release)!=manifest['jdkReleaseSha256']:raise SystemExit('Prepared JDK release metadata changed')
for process in Path('/proc').iterdir():
 if not process.name.isdigit():continue
 try:args=(process/'cmdline').read_bytes().split(b'\0')
 except OSError:continue
 if any(c in args for c in [b'org.codehaus.plexus.classworlds.launcher.Launcher',b'org.apache.maven.wrapper.MavenWrapperMain']):raise SystemExit('Active Maven process violates reference quiet window')
manifest['exclusiveHostReservation']=reservation
manifest['executionStartedUtc']=datetime.now(timezone.utc).isoformat()
manifest['javaVersionObserved']=subprocess.check_output([str(java),'-version'],stderr=subprocess.STDOUT,text=True)
with(inputs/'launcher.log').open('w')as log:
 process=subprocess.Popen(manifest['command'],stdout=log,stderr=subprocess.STDOUT)
 manifest['benchmarkProcessPid']=process.pid
 manifest_path.write_text(json.dumps(manifest,indent=2)+'\n')
 outcome=process.wait()
manifest['completedUtc']=datetime.now(timezone.utc).isoformat();manifest['launcherExitCode']=outcome
if outcome==0:
 environment=json.loads((output/'environment.json').read_text());order=json.loads((output/'run-order.json').read_text());summary=json.loads((output/'summary.json').read_text())
 counts=collections.Counter((x['workload'],x['mode'])for x in order)
 protocol_ok=len(order)==84 and len(counts)==12 and all(v==7 for v in counts.values()) and environment['pairs']==7 and environment['warmupSeconds']==30 and environment['measurementSeconds']==60 and environment['acceptanceEligible']
 measurements=[]
 for item in order:
  measurement=json.loads((output/item['id']/'measurement.json').read_text());protocol_ok &=item['exitCode']==0 and measurement['warmupSeconds']==30 and measurement['requestedMeasurementSeconds']==60 and measurement['actualMeasurementSeconds']>=60
  measurements.append({'id':item['id'],'pair':item['pair'],'mode':item['mode'],'workload':item['workload'],'measurement':measurement})
 (inputs/'raw-measurements.json').write_text(json.dumps(measurements,indent=2)+'\n')
 checks=[{'workload':w,'mode':mode,'throughputPass':v['throughputGatePass'],'p99Pass':v['p99GatePass']}for w,modes in summary.items()for mode,v in modes.items()]
 manifest['fullProtocolValidated']=bool(protocol_ok);manifest['overheadGateChecks']=checks;manifest['allOverheadGatesPass']=bool(protocol_ok)and len(checks)==8 and all(c['throughputPass']and c['p99Pass']for c in checks)
manifest_path.write_text(json.dumps(manifest,indent=2)+'\n')
raise SystemExit(outcome or(0 if manifest.get('allOverheadGatesPass')else 3))
