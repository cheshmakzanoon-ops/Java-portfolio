import csv,datetime,hashlib,json,os,statistics,subprocess,sys
from pathlib import Path
build=sys.argv[1]
project=Path('projects/07-indexforge')
jar=Path(f'.work/A07/artifacts/build{build}-cli.jar').resolve()
java=Path(os.environ['JAVA_HOME'])/'bin/java'
profiles=['CODE_NONE','CODE_LOW','CODE_MEDIUM','CODE_HIGH']
base=project/'evidence/relevance'/f'code-build{build}'
base.mkdir(parents=True,exist_ok=True)
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
identity={'certifiedBuild':int(build),'sourceFingerprint':Path(f'.work/A07/artifacts/build{build}-source.sha256').read_text().strip(),'cliJarSha256':sha(jar),'prespecifiedRulesSha256':sha(project/'fixtures/CODE-FEATURES-v1.md')}
results=[]
def report(state):
 selected=max(results,key=lambda r:r['objective']) if results else None
 value=identity|{'phase':'development-validation-only','state':state,'heldOutOpened':False,'profileOrder':profiles,'selectedProfile':selected['profile'] if selected else None,'selectedMode':selected['selectedMode'] if selected else None,'selection':selected['decision'] if selected else None,'profiles':results,'recordedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 path=base/('grid-selection.json' if state=='COMPLETE' else 'grid-checkpoint.json')
 path.with_suffix('.tmp').write_text(json.dumps(value,indent=2)+'\n');path.with_suffix('.tmp').replace(path)
for profile in profiles:
 if Path('.work/A07/PAUSE-JAVA').exists():
  report('RESOURCE_PAUSED');print('RESOURCE PAUSE before '+profile,flush=True);sys.exit(75)
 parent=base/profile.lower();parent.mkdir(exist_ok=True)
 record_path=parent/'completed-record.json'
 if record_path.exists():
  record=json.loads(record_path.read_text())
  if record['identity']!=identity:raise AssertionError('cached source/artifact/rules identity mismatch')
  for key in ['rawLog','perQuery','selectionFile']:
   if sha(Path(record[key]))!=record[key+'Sha256']:raise AssertionError('cached output changed: '+key)
  print('RESUME verified '+profile,flush=True)
 else:
  attempt=1
  while (parent/f'attempt-{attempt}').exists():attempt+=1
  output=parent/f'attempt-{attempt}'
  log=Path(f'.work/A07/code-build{build}-{profile.lower()}-attempt-{attempt}.log')
  command=[str(java),'--enable-native-access=ALL-UNNAMED','-Xms128m','-Xmx2g',f'-Dindexforge.ranking-profile={profile}','-jar',str(jar),'evaluate',f'.work/A07/code-build{build}-{profile.lower()}','projects/07-indexforge/fixtures',str(output),'development']
  with log.open('x') as stream:subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=300)
  rows=list(csv.DictReader((output/'development-per-query.csv').open()))
  modes=list(dict.fromkeys(r['mode'] for r in rows))
  means={mode:{'ndcg10':statistics.mean(float(r['ndcg10']) for r in rows if r['mode']==mode and r['split']=='validation'),'identifierMRR10':statistics.mean(float(r['mrr10']) for r in rows if r['mode']==mode and r['split']=='validation' and r['type']=='identifier')} for mode in modes}
  decision=(output/'selection.txt').read_text();mode=decision.splitlines()[0].split(': ')[1]
  if means[mode]['ndcg10']<means['BASELINE']['ndcg10'] or means[mode]['identifierMRR10']<means['BASELINE']['identifierMRR10']:raise AssertionError('selection violates preregistered baseline constraints')
  record={'identity':identity,'profile':profile,'selectedMode':mode,'validation':means[mode],'objective':min(means[mode]['ndcg10']/.75,means[mode]['identifierMRR10']/.9),'modes':means,'decision':decision,'command':command,'rawLog':str(log),'rawLogSha256':sha(log),'perQuery':str(output/'development-per-query.csv'),'perQuerySha256':sha(output/'development-per-query.csv'),'selectionFile':str(output/'selection.txt'),'selectionFileSha256':sha(output/'selection.txt')}
  record_path.write_text(json.dumps(record,indent=2)+'\n')
 if results and record['modes']['BASELINE']!=results[0]['modes']['BASELINE']:raise AssertionError('baseline changed across profiles')
 results.append(record);report('IN_PROGRESS')
 print(json.dumps({k:record[k] for k in ['profile','selectedMode','validation','objective']}),flush=True)
report('COMPLETE');selected=max(results,key=lambda r:r['objective'])
print('GRID SELECTION: '+selected['profile']+'/'+selected['selectedMode'],flush=True)
