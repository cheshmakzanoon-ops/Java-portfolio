from pathlib import Path
import collections, datetime, hashlib, json, shutil, statistics, xml.etree.ElementTree as ET

root = Path('/workspace/Java-portfolio')
p = root / 'projects/09-byteguard'
ev = p / 'evidence'
source = 'working-tree-sha256:a6055cdfb18245240a3341c4a712c256e5502541dcdb85523e662423d7edf09c'
artifact = p / 'cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path): return json.loads(path.read_text())
def save(path, value): path.write_text(json.dumps(value, indent=2) + '\n')
jarsha = sha(artifact)
assert jarsha == '390a7a39ef6041fdcbc347b5a0be437eead09a9feb0bb2277b11951a6783db93'
assert (p/'.runtime/build-fingerprint').read_text().splitlines() == [source.split(':')[1],jarsha]

# Historical failures and successful measurements retain their original identities.
history = ev/'history-before-r11'
history.mkdir(exist_ok=True)
for name in ['coverage.json','mutation.json','demo.json','corpus-results.json','benchmark-envelope.json']:
    dest = history/name
    if not dest.exists(): shutil.copy2(ev/name,dest)
if not (history/'benchmark-raw').exists(): shutil.copytree(ev/'benchmark-raw',history/'benchmark-raw')

old = read(ev/'verification-before-r11.json')
reports = []
for path in sorted(p.glob('*/target/*-reports/TEST-*.xml')):
    suite=ET.parse(path).getroot()
    row={'class':suite.attrib['name'],**{key:int(suite.attrib.get(key,0)) for key in ['tests','failures','errors','skipped']},'report':str(path.relative_to(root)),'reportSha256':sha(path)}
    reports.append(row)
assert sum(r['tests'] for r in reports)==65
assert not any(r[k] for r in reports for k in ['failures','errors','skipped'])
v={k:old[k] for k in ['schemaVersion','project','environment']}
v.update(status='PASS',timestampUtc='2026-10-18T03:30:39Z',sourceRevision=source,
         command='source scripts/cloud-env.sh; ./mvnw -f projects/09-byteguard/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A09/m2 -B -ntp -Pintegration,mutation clean verify',
         exitCode=0,reactorReportedBuildSuccess=True,suites=reports,testCount=65,packagedArtifactSha256=jarsha,
         rawArtifact='.work/coordinator/byteguard-r11-clean.log',rawArtifactSha256=sha(root/'.work/coordinator/byteguard-r11-clean.log'),
         scope='Fresh clean reactor, integration and original critical mutation gates on the measured source; local Linux verification.',
         historicalExitDiscrepancy={'evidence':'verification-before-r11.json','preservedExitCode':1,'resolution':'Historical cause remains unknown. Fresh clean full command independently returned0; original result was not rewritten.'},
         limitations=['Bounded supported analysis model; corpus metrics do not establish field accuracy.','Object-insensitive shared heap and shell argument joins have disclosed false positives.','Clean-checkout and repository-wide release checks remain separate.'])
save(ev/'verification.json',v)

aggregate=p/'evaluation/target/site/jacoco-aggregate/jacoco.xml'
coverage=read(p/'evaluation/target/coverage-summary.json')
counters=coverage['counters']; ratios=coverage['ratios']
assert ratios['LINE']>=.85 and ratios['BRANCH']>=.75
save(ev/'coverage.json',{'schemaVersion':1,'module':'byteguard-analysis-core','sourceRevision':source,'counters':counters,'ratios':ratios,'required':{'LINE':.85,'BRANCH':.75},'rawXml':str(aggregate.relative_to(root)),'rawXmlSha256':sha(aggregate),'scope':'Original core group extracted by the actual verification coverage gate from the aggregate unit/evaluation report.'})
mutpath=p/'analysis-core/target/pit-reports/mutations.xml'
mutations=ET.parse(mutpath).getroot().findall('mutation')
counts=collections.Counter(m.attrib['status'] for m in mutations)
assert counts=={'KILLED':164,'SURVIVED':13}
mut=read(history/'mutation.json')
mut.update(sourceRevision=source,counts=dict(counts),total=len(mutations),killedPercent=100*counts['KILLED']/len(mutations),rawXmlSha256=sha(mutpath),
           survivors=[{'class':m.findtext('mutatedClass'),'method':m.findtext('mutatedMethod'),'line':m.findtext('lineNumber'),'description':m.findtext('description'),'status':m.attrib['status']} for m in mutations if m.attrib['status']!='KILLED'])
save(ev/'mutation.json',mut)
corpus=read(p/'evaluation/target/corpus-results.json')
assert corpus['positiveDetected']==60 and corpus['negativeCorrect']==60
corpus.update(sourceRevision=source,labelsSha256=sha(p/'corpus/labels/frozen-v2.json'),scope='Actual current clean CorpusTest output; frozen labels unchanged.')
save(ev/'corpus-results.json',corpus)

reservation=root/'.work/coordinator/quiet-reference-4-byteguard/reservation.json'
res=read(reservation)
assert res['exitCode']==0 and len(res['lockPaths'])==3
assert not any(res[k] for k in ['otherJavaProcessesAtReservation','otherApplicationContainersAtReservation','remainingJavaProcesses','remainingApplicationContainers'])
raw=ev/'benchmark-raw'
shutil.copy2(reservation,raw/'reservation.json')
benchmark=read(ev/'benchmark.json')
assert benchmark['sourceRevision']==source
forks=[]
for i,r in enumerate(benchmark['measurements']['runs']):
    assert r['exitCode']==1 and not r['partial'] and r['applicationClasses']==10000 and r['findings']==10000 and r['elapsedSeconds']<=120
    base=p/'.runtime/benchmark'/f'run-{i}'
    for suffix in ['.time.json','.terminal.log']: shutil.copy2(Path(str(base)+suffix),raw/f'run-{i}{suffix}')
    sarif=Path(str(base)+'.sarif')
    forks.append({'index':i,'measurements':r,'sarifBytes':sarif.stat().st_size,'sarifSha256':sha(sarif),'terminalSha256':sha(Path(str(base)+'.terminal.log')),'statsSha256':sha(Path(str(base)+'.time.json')),'rawSarif':str(sarif.relative_to(root))})
assert len({f['sarifSha256'] for f in forks})==1
save(ev/'benchmark-envelope.json',{'schemaVersion':1,'project':'byteguard','status':'PASS','sourceRevision':source,'measuredPackagedArtifactSha256':jarsha,'reservation':'benchmark-raw/reservation.json','reservationSha256':sha(raw/'reservation.json'),'corpusSha256':benchmark['corpusSha256'],'corpusBytes':benchmark['corpusBytes'],'forks':forks,'independentReview':{'reviewer':'A08 ThreadLens owner','status':'CLOSED_NO_UNRESOLVED_FINDING','scope':'Current source, descriptor bounds, fresh65-method reports, original fixture rejection, actual three raw full scans, artifact/cache identity and preserved failures. No general soundness claim.'},'elapsedSummary':{'medianSeconds':statistics.median(r['elapsedSeconds'] for r in benchmark['measurements']['runs']),'sampleCvPercent':100*statistics.stdev(r['elapsedSeconds'] for r in benchmark['measurements']['runs'])/statistics.mean(r['elapsedSeconds'] for r in benchmark['measurements']['runs'])},'limitations':benchmark['limitations']})
reg=ev/'descriptor-regression'
reg.mkdir(exist_ok=True)
for name in ['evidence.json','after.sarif','after.log']:
    shutil.copy2(root/'.work/coordinator/byteguard-r11-regression'/name,reg/name)

demo=read(history/'demo.json')
current=read(root/'.work/coordinator/byteguard-current-demo/results.json')
assert len(current['operations'])==5 and all(r['exitCode']==0 for r in current['operations'])
demo.update(sourceRevision=source,timestampUtc=current['observedAtUtc'],command='bash scripts/portfolio.sh {reset-demo,up,demo,demo,down} byteguard (separate actual subprocesses)',operations=current['operations'],packagedArtifactSha256=jarsha,cache='Both current demos used the exact verified source/artifact cache; each asserted scan exits1/0/3/0. Historical miss/invalidation controls retained separately.',limitations=['Local current-artifact entrypoint validation; repository-wide clean-checkout verification remains separate.'])
demo['assertions']={'passed':12,'failed':0,'scope':'Six explicit Python assertions per actual demo, two demos; shell also checked four expected scanner exits per demo.'}
for variant in ['positive','corrected','partial','baseline']:
    run=read(p/'.runtime/demo'/f'{variant}.sarif')['runs'][0]
    demo['scenarios'][variant]={'findings':len(run['results']),'partial':run['properties']['partial'],'unfinishedFrontier':run['properties']['unfinishedFrontier']}
    if variant=='baseline':demo['scenarios'][variant]['baselineState']=run['results'][0]['baselineState']
demo.pop('rawArtifact',None);demo.pop('cacheReuseRawArtifact',None)
save(ev/'demo.json',demo)
print(json.dumps({'tests':65,'coverage':ratios,'mutation':dict(counts),'artifact':jarsha,'source':source,'benchmarkForks':len(forks),'demoOperations':len(current['operations'])},indent=2))
