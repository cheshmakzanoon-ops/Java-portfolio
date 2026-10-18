"""Actual final-source CLI scripts, with certificate-bound cache marker and disposable fixtures."""
from pathlib import Path
import datetime,hashlib,json,os,shutil,subprocess,time
root=Path('/workspace/Java-portfolio');os.chdir(root);project=root/'projects/07-indexforge';certificate=json.loads((project/'evidence/certification-build24.json').read_text());assert certificate['strictMutationCertificationPassed'];sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();assert sha(project/'cli/target/indexforge-cli-1.0.0-SNAPSHOT.jar')==certificate['cliJarSha256']
h=hashlib.sha256()
for p in sorted(project.rglob('*')):
 if p.is_file() and not set(p.relative_to(project).parts)&{'target','.runtime','evidence'} and p.suffix in {'.java','.xml','.tsv','.jsonl','.sh'}:h.update(p.relative_to(project).as_posix().encode()+b'\0'+p.read_bytes())
for p in [root/'pom.xml',root/'AGENTS.md']:h.update(p.name.encode()+p.read_bytes())
assert h.hexdigest()==certificate['sourceFingerprint'];(project/'.runtime/tested-source').write_text(h.hexdigest()+'\n')
env=os.environ.copy();env['JAVA_TOOL_OPTIONS']='-Dindexforge.ranking-mode=SOURCE_AWARE -Dindexforge.ranking-profile=CODE_HIGH';results=[];logs=root/'.work/A07';started=datetime.datetime.now(datetime.timezone.utc).isoformat();before={str(p):sha(p) for p in (project/'.runtime/performance-build23/index').glob('*/CURRENT')};assert len(before)==3
for step,script,count in [('reset-before','reset-demo.sh',None),('up','up.sh',None),('demo2000','demo.sh',2000),('demo20000','demo.sh',20000),('down','down.sh',None),('reset-after','reset-demo.sh',None)]:
 if step=='up':
  data=project/'.runtime/demo';assert data==Path('/workspace/Java-portfolio/projects/07-indexforge/.runtime/demo');shutil.rmtree(data);assert not data.exists()
 if count:env['INDEXFORGE_DOCUMENTS']=str(count)
 command=['bash',str(project/'scripts'/script)];log=logs/f'final24-{step}.log';start=time.monotonic()
 with log.open('x') as stream:r=subprocess.run(command,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=420)
 record={'step':step,'command':command,'exitCode':r.returncode,'elapsedSeconds':time.monotonic()-start,'rawLog':str(log.relative_to(root)),'rawLogSha256':sha(log)};results.append(record)
 if r.returncode:raise SystemExit('Demo script failed; preserve '+str(log))
 if step=='up':assert (project/'.runtime/demo/sources').is_dir()
 if count:
  result=[json.loads(line) for line in log.read_text().splitlines() if line.startswith('{')][-1];assert result['documents']==count and result['seed']==7007 and result['tenants']==3 and result['assertions']=='passed';assert result['sourceBytes']=={2000:439546,20000:4435559}[count];record['result']=result
 if step=='reset-after':assert (project/'.runtime/demo/sources').is_dir() and not list((project/'.runtime').glob('demo-run-*'))
 print(json.dumps(record),flush=True)
assert all(sha(Path(p))==expected for p,expected in before.items()),'Demo reset changed prepared performance index'
record={'phase':'actual-final-source-script-demonstrations','certifiedBuild':24,'sourceFingerprint':certificate['sourceFingerprint'],'cliJarSha256':certificate['cliJarSha256'],'testedSourceMarkerProvenance':'Written only after full strict24 verification and current source/target artifact identity assertions; verify.sh itself was not rerun.','rankingMode':'SOURCE_AWARE','rankingProfile':'CODE_HIGH','javaToolOptions':env['JAVA_TOOL_OPTIONS'],'startedUtc':started,'steps':results,'scriptsSha256':{n:sha(project/'scripts'/n) for n in ['common.sh','up.sh','demo.sh','down.sh','reset-demo.sh']},'preparedPerformancePointersUnchanged':True,'heldOutTuningPerformed':False};output=project/'evidence/demonstrations/build24-final-scripts.json';output.parent.mkdir(exist_ok=True);output.write_text(json.dumps(record,indent=2)+'\n')
