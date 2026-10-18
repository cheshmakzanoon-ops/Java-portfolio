from pathlib import Path
import hashlib,json,math,shutil,zipfile
root=Path('/workspace/Java-portfolio');p=root/'projects/09-byteguard';runtime=p/'.runtime/benchmark';benchmark=json.loads((p/'evidence/benchmark.json').read_text());reservation_path=root/'.work/coordinator/quiet-reference-2/reservation.json';reservation=json.loads(reservation_path.read_text());passed=[]
def check(name,condition):
 if not condition:raise AssertionError(name)
 passed.append(name)
check('reservation successful',reservation['result']=='PASS' and reservation['exitCode']==0)
check('all three build locks held',len(reservation['lockPaths'])==3 and {Path(x).name for x in reservation['lockPaths']}=={'0.lock','1.lock','2.lock'})
for key in ['otherJavaProcessesAtReservation','otherApplicationContainersAtReservation','remainingJavaProcesses','remainingApplicationContainers']:check(key,not reservation[key])
check('four-core quota',reservation['cpuQuota']=='400000 100000')
check('configured heap2GiB',benchmark['environment']['jvmFlags']==['-Xmx2g'])
corpus=runtime/'classes-10000.jar';check('actual corpus hash',hashlib.sha256(corpus.read_bytes()).hexdigest()==benchmark['corpusSha256'])
with zipfile.ZipFile(corpus) as archive:
 names=archive.namelist();check('10000 unique actual class entries',len(names)==10000 and len(set(names))==10000)
 check('all deterministic class identities present',set(names)=={f'benchmark/Case{i:05d}.class' for i in range(10000)})
raw=p/'evidence/benchmark-raw';raw.mkdir(exist_ok=True);shutil.copyfile(reservation_path,raw/'reservation.json');runs=[];reference=None
for index in range(3):
 stats=runtime/f'run-{index}.time.json';sarif=runtime/f'run-{index}.sarif';terminal=runtime/f'run-{index}.terminal.log';metric=json.loads(stats.read_text());actual=json.loads(sarif.read_text())['runs'][0];properties=actual['properties'];results=actual['results'];fingerprints={r['partialFingerprints']['byteguard/v1'] for r in results}
 check(f'run{index} precise findings exit',metric['exitCode']==1 and metric['terminationSignal'] is None)
 check(f'run{index} complete without cancellation/timeout',not metric['partial'] and not metric['cancelled'] and not metric['timedOut'] and not properties['partial'] and not properties['unfinishedFrontier'])
 check(f'run{index} all10000 parsed application classes',properties['applicationClasses']==properties['parsedClasses']==properties['inputClasses']==10000)
 check(f'run{index}10000 unique findings',len(results)==len(fingerprints)==10000)
 check(f'run{index}<=120s',math.isfinite(metric['elapsedSeconds']) and 0<metric['elapsedSeconds']<=120)
 check(f'run{index}actual positive resource usage',metric['maxRssKiB']>0 and metric['userSeconds']>0 and metric['systemSeconds']>=0)
 check(f'run{index}expected SQL family only',{r['ruleId'] for r in results}=={'BG-SQL-TEXT'})
 check(f'run{index}unique concrete source instructions',len({r['properties']['sourceBytecode'] for r in results})==10000)
 check(f'run{index}no unknown library calls',properties['unknownCalls']==0)
 if reference is None:reference=fingerprints
 else:check(f'run{index}stable finding identities across forks',fingerprints==reference)
 shutil.copyfile(stats,raw/stats.name);shutil.copyfile(terminal,raw/terminal.name)
 runs.append({'index':index,'measurements':metric,'sarifBytes':sarif.stat().st_size,'sarifSha256':hashlib.sha256(sarif.read_bytes()).hexdigest(),'terminalSha256':hashlib.sha256(terminal.read_bytes()).hexdigest(),'statsSha256':hashlib.sha256(stats.read_bytes()).hexdigest(),'rawSarif':str(sarif.relative_to(p))})
measuredJar=root/'.work/A09/before-clean-certification/measured-cli.jar'
envelope={'schemaVersion':1,'project':'byteguard','status':'PASS','sourceRevision':benchmark['sourceRevision'],'measuredPackagedArtifactSha256':hashlib.sha256(measuredJar.read_bytes()).hexdigest(),'reservation':'benchmark-raw/reservation.json','reservationSha256':hashlib.sha256(reservation_path.read_bytes()).hexdigest(),'corpusSha256':benchmark['corpusSha256'],'corpusBytes':corpus.stat().st_size,'forks':runs,'independentChecks':{'passed':len(passed),'failed':0,'checks':passed},'scope':'Independent post-run inspection of actual three quiet full scans; no scanner rerun. Stable fingerprint sets, unique source identities, actual archive entries and all resource/status measurements checked.','limitations':benchmark['limitations']}
(p/'evidence/benchmark-envelope.json').write_text(json.dumps(envelope,indent=2)+'\n')
print(json.dumps({'forks':[{'seconds':r['measurements']['elapsedSeconds'],'rssKiB':r['measurements']['maxRssKiB'],'sarifBytes':r['sarifBytes']} for r in runs],'checks':len(passed),'stableUniqueFindings':len(reference),'measuredJar':envelope['measuredPackagedArtifactSha256']},indent=2))
