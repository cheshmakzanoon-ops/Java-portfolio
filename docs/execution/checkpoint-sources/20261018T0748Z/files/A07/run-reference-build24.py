"""Execute only after coordinator grants the quiet reference window; no Maven."""
from pathlib import Path
import datetime,hashlib,json,os,platform,subprocess,time,sys
root=Path('/workspace/Java-portfolio');os.chdir(root)
preflight=sys.argv[1:]==['--preflight-only']
if sys.argv[1:] and not preflight:raise SystemExit('Only --preflight-only is supported')
if not preflight and not os.environ.get('INDEXFORGE_QUIET_GRANT'):raise SystemExit('Coordinator quiet grant required in INDEXFORGE_QUIET_GRANT')
project=root/'projects/07-indexforge';prep_path=project/'evidence/performance/preparation-build24.json';prep=json.loads(prep_path.read_text());sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();jar=root/'.work/A07/artifacts/build24-cli.jar';data=Path(prep['dataDirectory'])
if sha(jar)!=prep['cliJarSha256'] or prep['measuredRuns']!=0 or prep['documents']!=100000 or prep['rankingMode']!='BASELINE' or prep['rankingProfile']!='CODE_HIGH':raise SystemExit('Prepared artifact/config mismatch')
if len(prep['preparedPointers'])!=3:raise SystemExit('Three prepared pointers required')
for name,expected in prep['preparedPointers'].items():
 if sha(data/name)!=expected:raise SystemExit('Prepared generation changed: '+name)
h=hashlib.sha256()
for p in sorted(project.rglob('*')):
 if p.is_file() and not set(p.relative_to(project).parts)&{'target','.runtime','evidence'} and p.suffix in {'.java','.xml','.tsv','.jsonl','.sh'}:h.update(p.relative_to(project).as_posix().encode()+b'\0'+p.read_bytes())
for p in [root/'pom.xml',root/'AGENTS.md']:h.update(p.name.encode()+p.read_bytes())
if h.hexdigest()!=prep['sourceFingerprint']:raise SystemExit('Current source changed from prepared certificate')
if preflight:
 print(json.dumps({'ready':True,'javaStarted':False,'documents':prep['documents'],'rankingMode':prep['rankingMode'],'rankingProfile':prep['rankingProfile'],'cliJarSha256':sha(jar),'compiledModelSha256':prep['compiledModelSha256'],'preparedData':str(data)},indent=2));raise SystemExit(0)
attempt=1
while (project/f'evidence/performance/reference-build24-attempt{attempt}').exists():attempt+=1
output=project/f'evidence/performance/reference-build24-attempt{attempt}';output.mkdir()
java=Path('/workspace/.tools/jdk-25/bin/java');command=[str(java),'--enable-native-access=ALL-UNNAMED','-Xms128m','-Xmx2g','-Dindexforge.ranking-mode=BASELINE','-Dindexforge.ranking-profile=CODE_HIGH',f'-Xlog:gc*:file={output}/gc.log:time,uptime,level,tags','-jar',str(jar),'benchmark',str(data),str(output),'100000','30']
text=lambda p:Path(p).read_text() if Path(p).exists() else None
metadata={'phase':'quiet-reference','command':command,'quietGrant':os.environ['INDEXFORGE_QUIET_GRANT'],'sourceFingerprint':prep['sourceFingerprint'],'cliJarSha256':sha(jar),'preparationSha256':sha(prep_path),'runnerSha256':sha(Path(__file__)),'rankingMode':'BASELINE','rankingProfile':'CODE_HIGH','compiledModelSha256':prep['compiledModelSha256'],'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'platform':platform.platform(),'jdkRelease':text('/workspace/.tools/jdk-25/release'),'cgroupCpuMax':text('/sys/fs/cgroup/cpu.max'),'cgroupMemoryMax':text('/sys/fs/cgroup/memory.max'),'cpuInfo':text('/proc/cpuinfo'),'memoryInfo':text('/proc/meminfo'),'boundary':'shared application including leases ACL facets explanations/cache; excludes HTTP','heldOutOpened':False}
(output/'environment.json').write_text(json.dumps(metadata,indent=2)+'\n')
start=time.monotonic()
with (output/'stdout.log').open('x') as stream:r=subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,timeout=600)
metadata.update(elapsedSeconds=time.monotonic()-start,exitCode=r.returncode)
if r.returncode:
 (output/'execution.json').write_text(json.dumps(metadata,indent=2)+'\n');raise SystemExit(r.returncode)
report=json.loads((output/'summary.json').read_text());runs=report['runs'];valid=report['documents']==100000 and report['heapMaxBytes']<=2147483648 and len(runs)==3 and all(x['requests']==900 and x['offeredRps']==30 and x['errors']==0 for x in runs);target=valid and all(x['p95Ms']<200 for x in runs)
metadata.update(workloadAndErrorsPass=valid,p95TargetPass=target,files={str(p.relative_to(output)):sha(p) for p in sorted(output.rglob('*')) if p.is_file()});(output/'execution.json').write_text(json.dumps(metadata,indent=2)+'\n');print(json.dumps({'output':str(output),'workloadAndErrorsPass':valid,'p95TargetPass':target,'runs':runs},indent=2))
if not valid or not target:raise SystemExit('Reference target failed; all actual evidence preserved')
