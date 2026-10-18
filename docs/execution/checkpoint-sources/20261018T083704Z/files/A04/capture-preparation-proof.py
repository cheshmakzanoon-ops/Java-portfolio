#!/usr/bin/env python3
"""AFTER actual supported preparation/root checker, seal byte-equivalent capture inputs."""
from pathlib import Path
import hashlib,json,subprocess,sys,datetime
ROOT=Path(__file__).resolve().parents[2];WORK=ROOT/'.work/A04';PROJECT=ROOT/'projects/04-pulsestream'
def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for chunk in iter(lambda:stream.read(1048576),b''):h.update(chunk)
 return h.hexdigest()
def main():
 if len(sys.argv)!=2:raise SystemExit('Pass actual15method preparation gate JSON after root authorizes/runs it')
 gate_path=Path(sys.argv[1]).resolve();gate=json.loads(gate_path.read_text())
 if gate['methodCount']!=15 or gate['rootCheckerExitCode']!=0 or gate['positiveCutoff']<=0:raise RuntimeError('Actual preparation gate required')
 archive=Path((WORK/'latest-capture-fix-path').read_text().strip());prior_path=archive/'prior-inputs.json';prior=json.loads(prior_path.read_text())
 current=subprocess.run(['python3',str(PROJECT/'scripts/fingerprint.py')],text=True,capture_output=True,check=True).stdout.strip()
 if current!='working-tree-sha256:bf8294af855673e08feb586894e30e1ed22f77ff065af8c2714c8ffd0e2e6ace':raise RuntimeError('Unreviewed script-inclusive source identity')
 for name in ['build.fingerprint','classpath.fingerprint']:
  if (WORK/'runtime'/name).read_text().strip()!=current:raise RuntimeError('Supported preparation not current')
 expected={**prior['mainClassAndResourceHashes'],**prior['projectJarHashes'],**prior['preparedRuntimeInputHashes']}
 for name,digest in expected.items():
  if sha(name)!=digest:raise RuntimeError('Compiled/runtime input changed: '+name)
 # Verify there are no added/removed compiled classes/resources/JARs hidden by matching old paths.
 actual_classes={str(p):sha(p) for module in ['engine','kafka-adapter','postgres-store','service','verification'] for p in (PROJECT/module/'target/classes').rglob('*') if p.is_file()}
 actual_jars={str(p):sha(p) for module in ['engine','kafka-adapter','postgres-store','service','verification'] for p in (PROJECT/module/'target').glob('*.jar')}
 if actual_classes!=prior['mainClassAndResourceHashes'] or actual_jars!=prior['projectJarHashes']:raise RuntimeError('Main classes/resources or JAR path-set changed')
 ignored={'target','docs','evidence','__pycache__','.jqwik-database'}
 paths=[p for p in PROJECT.rglob('*') if p.is_file() and p.name!='README.md' and not ignored.intersection(p.relative_to(PROJECT).parts)]
 paths += [p for p in [ROOT/'pom.xml',ROOT/'mvnw',ROOT/'scripts/cloud-env.sh',ROOT/'scripts/with-build-slot.sh',ROOT/'.mvn/maven.config',ROOT/'.mvn/wrapper/maven-wrapper.properties'] if p.exists()]
 restored=hashlib.sha256()
 for path in sorted(paths):
  data=(archive/'prior'/path.relative_to(ROOT)).read_bytes() if path==PROJECT/'scripts/benchmark-engine.sh' else path.read_bytes()
  restored.update(str(path.relative_to(ROOT)).encode()+b'\0'+len(data).to_bytes(8,'big')+data)
 if 'working-tree-sha256:'+restored.hexdigest()!=prior['sourceRevision']:raise RuntimeError('Source changes exceed reviewed product recording script')
 baseline=WORK/'pre-optimization-engine';manifest=json.loads((baseline/'manifest.json').read_text());guard_paths=[WORK/'run-micro-capture.py',WORK/'test-micro-capture.py',WORK/'capture-preparation-proof.py',WORK/'run-engine-baseline.sh',WORK/'run-quiet.py',PROJECT/'scripts/benchmark-engine.sh',baseline/'manifest.json',gate_path,prior_path,*[baseline/name for name in manifest['files']]]
 for key in ['preparationLog','rootCheckerLog','controlsLog']:guard_paths.append(Path(gate[key]))
 value={'schemaVersion':1,'classification':'new-startup-recording-capture-inputs','sourceRevision':current,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'priorInputs':str(prior_path),'unchangedCompiledAndRuntimeInputs':expected,'preparation':gate,'guardHashes':{str(p):sha(p) for p in guard_paths},'byteProof':{'mainClasses':44,'projectJars':6,'preparedRuntimeInputs':129,'allComparedBytesIdentical':True,'onlyProductInputDifference':'scripts/benchmark-engine.sh: JFR directory and per-fork startup recording argument'},'quiet12IdentityRetained':prior['sourceRevision'],'oldRunnerAndReadyPreserved':True,'recordingBoundary':'New explicit recordings start with each fork JVM and include initialization/warmup/shutdown; quiet12 built-in-profile recordings were measurement-only.'}
 output=archive/'MICRO_READY.json'
 if output.exists():raise RuntimeError('Refuse overwrite of existing capture receipt; preserve failed/superseded evidence')
 output.write_text(json.dumps(value,indent=2)+'\n');print(output)
if __name__=='__main__':main()
