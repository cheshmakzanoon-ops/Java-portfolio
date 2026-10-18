import csv,datetime,hashlib,json,os,statistics,subprocess,sys
from pathlib import Path
build=sys.argv[1]
project=Path('projects/07-indexforge')
jar=Path(f'.work/A07/artifacts/build{build}-cli.jar').resolve()
java=Path(os.environ['JAVA_HOME'])/'bin/java'
profiles=['CURRENT','CONTEXTUAL','SEMANTIC','NAVIGATIONAL','CONTENT_FOCUSED','COORDINATED']
results=[]
for profile in profiles:
 output=project/'evidence/relevance'/f'weight-coverage-build{build}'/profile.lower()
 log=Path(f'.work/A07/weight-build{build}-{profile.lower()}.log')
 command=[str(java),'--enable-native-access=ALL-UNNAMED','-Xms128m','-Xmx2g',f'-Dindexforge.ranking-profile={profile}','-jar',str(jar),'evaluate',f'.work/A07/weight-build{build}-{profile.lower()}','projects/07-indexforge/fixtures',str(output),'development']
 with log.open('w') as stream: subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=300)
 rows=list(csv.DictReader((output/'development-per-query.csv').open()))
 modes=list(dict.fromkeys(r['mode'] for r in rows))
 means={mode:{'ndcg10':statistics.mean(float(r['ndcg10']) for r in rows if r['mode']==mode and r['split']=='validation'),'identifierMRR10':statistics.mean(float(r['mrr10']) for r in rows if r['mode']==mode and r['split']=='validation' and r['type']=='identifier')} for mode in modes}
 if results and means['BASELINE']!=results[0]['modes']['BASELINE']:raise AssertionError('baseline changed across profiles')
 decision=(output/'selection.txt').read_text()
 mode=decision.splitlines()[0].split(': ')[1]
 if means[mode]['ndcg10']<means['BASELINE']['ndcg10'] or means[mode]['identifierMRR10']<means['BASELINE']['identifierMRR10']:raise AssertionError('selection violates preregistered baseline constraints')
 record={'profile':profile,'selectedMode':mode,'validation':means[mode],'objective':min(means[mode]['ndcg10']/.75,means[mode]['identifierMRR10']/.9),'modes':means,'decision':decision,'command':command,'rawLog':str(log),'rawLogSha256':hashlib.sha256(log.read_bytes()).hexdigest()}
 results.append(record)
 print(json.dumps({k:record[k] for k in ['profile','selectedMode','validation','objective']}),flush=True)
selected=max(results,key=lambda r:r['objective'])
report={'phase':'development-validation-only','heldOutOpened':False,'certifiedBuild':int(build),'sourceFingerprint':Path(f'.work/A07/artifacts/build{build}-source.sha256').read_text().strip(),'cliJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'prespecifiedRulesSha256':hashlib.sha256((project/'fixtures/WEIGHT-COVERAGE-v1.md').read_bytes()).hexdigest(),'selectedProfile':selected['profile'],'selectedMode':selected['selectedMode'],'selection':selected['decision'],'profiles':results,'recordedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
output=project/'evidence/relevance'/f'weight-coverage-build{build}'
(output/'grid-selection.json').write_text(json.dumps(report,indent=2)+'\n')
print('GRID SELECTION: '+report['selectedProfile']+'/'+report['selectedMode'],flush=True)
