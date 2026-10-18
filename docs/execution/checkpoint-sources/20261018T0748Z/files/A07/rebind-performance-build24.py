"""Untimed certified24 runtime check of the explicitly audited unchanged100k index."""
from pathlib import Path
import datetime,hashlib,json,os,shutil,subprocess,time,zipfile
root=Path('/workspace/Java-portfolio');os.chdir(root)
if Path('.work/A07/PAUSE-JAVA').exists():raise SystemExit('Java resource pause active')
project=Path('projects/07-indexforge');sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();previous=project/'evidence/performance/preparation-build23.json';prep=json.loads(previous.read_text());certificate=json.loads((project/'evidence/certification-build24.json').read_text());assert certificate['strictMutationCertificationPassed'];jar=Path('.work/A07/artifacts/build24-cli.jar').resolve();oldjar=Path('.work/A07/artifacts/build23-cli.jar');audit=project/'evidence/performance/index-reuse-build23-24.json'
with zipfile.ZipFile(oldjar) as a,zipfile.ZipFile(jar) as b:
 assert set(a.namelist())==set(b.namelist()),'Packaged entry set changed'
 differences=[{'entry':n,'beforeSha256':hashlib.sha256(a.read(n)).hexdigest(),'afterSha256':hashlib.sha256(b.read(n)).hexdigest()} for n in sorted(set(a.namelist())) if a.read(n)!=b.read(n)]
 assert [x['entry'] for x in differences]==['io/github/cheshmakzanoon/portfolio/indexforge/SearchEngine.class'],differences
 identity={'oldJarSha256':sha(oldjar),'newJarSha256':sha(jar),'changedEntries':differences,'allOtherEntriesByteIdentical':True,'indexSchemaAndBuilderAndMapperAndGeneratorUnchanged':True,'priorPreparationSha256':sha(previous),'reason':'Only query request-context cache/cursor digest canonicalization changed; all indexing/dependency/resource entries are byte-identical.'}
audit.write_text(json.dumps(identity,indent=2)+'\n');data=Path(prep['dataDirectory']);assert len(prep['preparedPointers'])==3
for name,expected in prep['preparedPointers'].items():assert sha(data/name)==expected,name
helper=Path('.work/A07/RebindPerformance.java').resolve();shutil.copyfile('.work/A07/RebindPerformance-build24.java.txt',helper);log=Path('.work/A07/rebind-performance-build24.log');target=project/'evidence/performance/preparation-build24.json';assert not log.exists() and not target.exists();command=[str(Path(os.environ['JAVA_HOME'])/'bin/java'),'--enable-native-access=ALL-UNNAMED','-Xms128m','-Xmx2g','-Dindexforge.ranking-mode=BASELINE','-Dindexforge.ranking-profile=CODE_HIGH','-cp',str(jar),str(helper),str(data)];start=time.monotonic();started=datetime.datetime.now(datetime.timezone.utc).isoformat()
with log.open('x') as stream:r=subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,timeout=180)
if r.returncode:raise SystemExit(r.returncode)
result=[json.loads(line) for line in log.read_text().splitlines() if line.startswith('{')][-1]
selection=Path('projects/07-indexforge/evidence/relevance/code-build24/code_high/attempt-1/selection.txt').read_text();model=next(x.split(': ',1)[1] for x in selection.splitlines() if x.startswith('Model SHA256: '));assert result['compiledModelSha256']==model
for name,expected in prep['preparedPointers'].items():assert sha(data/name)==expected,'Runtime unexpectedly republished '+name
result.update(seed=prep['seed'],sourceBytes=prep['sourceBytes'],dataDirectory=str(data),reusedPreparedIndexFromBuild23=True,priorPreparationSha256=sha(previous),indexReuseAudit=str(audit),indexReuseAuditSha256=sha(audit),preparedPointers=prep['preparedPointers'],sourceFingerprint=certificate['sourceFingerprint'],cliJarSha256=sha(jar),preparationHelperSha256=sha(helper),command=command,startedUtc=started,elapsedSeconds=time.monotonic()-start,rawLog=str(log),rawLogSha256=sha(log));target.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
