from pathlib import Path
import xml.etree.ElementTree as E,json,hashlib,collections,sys,datetime
number=sys.argv[1];log=sys.argv[2];b=Path('projects/07-indexforge');jar=Path(f'.work/A07/artifacts/build{number}-cli.jar')
out={'build':int(number),'effectiveJUnit':'6.1.3','log':log,'sourceFingerprint':Path(f'.work/A07/artifacts/build{number}-source.sha256').read_text().strip(),'cliJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'testSuites':[],'coverage':{},'mutation':{},'recordedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
for m in ['ingest','index','query','service','evaluation','cli']:
 for pattern in ['target/surefire-reports/TEST-*.xml','target/failsafe-reports/TEST-*.xml']:
  for p in (b/m).glob(pattern):
   r=E.parse(p).getroot();out['testSuites'].append({k:r.attrib[k] for k in ['name','tests','failures','errors','skipped']})
 p=b/m/'target/site/jacoco/jacoco.xml'
 if p.exists():out['coverage'][m]={c.attrib['type']:{k:int(c.attrib[k]) for k in ['covered','missed']} for c in E.parse(p).getroot().findall('counter')}
 p=b/m/'target/pit-reports/mutations.xml'
 if p.exists():
  mutations=E.parse(p).getroot().findall('mutation');out['mutation'][m]={'total':len(mutations),'statuses':dict(collections.Counter(x.attrib['status'] for x in mutations)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
Path(f'.work/A07/artifacts/build{number}-verification.json').write_text(json.dumps(out,indent=2)+'\n');(b/f'evidence/certification-build{number}.json').write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({'tests':sum(int(s['tests']) for s in out['testSuites']),'sourceFingerprint':out['sourceFingerprint'],'mutation':out['mutation']},indent=2))
