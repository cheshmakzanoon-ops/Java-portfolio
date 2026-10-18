from pathlib import Path
from datetime import datetime,timezone
import json,hashlib,xml.etree.ElementTree as E,subprocess,re
root=Path('/workspace/Java-portfolio');p=root/'projects/09-byteguard';out=p/'evidence';out.mkdir(exist_ok=True)
log=root/'.work/A09/release-final.log';assert 'BUILD SUCCESS' in log.read_text(),'No successful certification'
suites=[]
for module in ['analysis-core','report','cli','evaluation']:
 for directory in ['surefire-reports','failsafe-reports']:
  for file in (p/module/'target'/directory).glob('TEST-*.xml'):
   r=E.parse(file).getroot();suites.append({'class':r.attrib['name'],'tests':int(r.attrib['tests']),'failures':int(r.attrib.get('failures',0)),'errors':int(r.attrib.get('errors',0)),'skipped':int(r.attrib.get('skipped',0)),'report':str(file.relative_to(root)),'reportSha256':hashlib.sha256(file.read_bytes()).hexdigest()})
assert suites and not any(s[k] for s in suites for k in ['failures','errors','skipped'])
fingerprint=subprocess.check_output(['bash','-c','source projects/09-byteguard/scripts/common.sh; byteguard_fingerprint'],cwd=root,text=True).strip()
expected=(root/'.work/A09/release-final-source.sha256').read_text().strip();assert fingerprint==expected,(fingerprint,expected)
jar=p/'cli/target/byteguard-cli-1.0.0-SNAPSHOT.jar'
coverage=json.loads((p/'evaluation/target/coverage-summary.json').read_text());assert coverage['ratios']['LINE']>=.85 and coverage['ratios']['BRANCH']>=.75
mutation=p/'analysis-core/target/pit-reports/mutations.xml';mr=E.parse(mutation).getroot();counts={}
for m in mr:counts[m.attrib['status']]=counts.get(m.attrib['status'],0)+1
assert sum(counts.values())>0 and counts.get('KILLED',0)/sum(counts.values())>=.7,counts
assert not any(counts.get(k,0) for k in ['RUN_ERROR','MEMORY_ERROR','UNKNOWN']),counts
assert (p/'analysis-core/target/pit-reports/index.html').stat().st_size>0
corpus=json.loads((p/'evaluation/target/corpus-results.json').read_text());assert len(corpus['cases'])==120 and corpus['positiveDetected']==60 and corpus['negativeCorrect']>=57
corpus['sourceRevision']='working-tree-sha256:'+fingerprint;corpus['frozenLabelSha256']=hashlib.sha256((p/'corpus/labels/frozen-v2.json').read_bytes()).hexdigest();(out/'corpus-results.json').write_text(json.dumps(corpus,indent=2)+'\n')
coverage['sourceRevision']='working-tree-sha256:'+fingerprint;(out/'coverage.json').write_text(json.dumps(coverage,indent=2)+'\n')
mutants={'schemaVersion':1,'project':'byteguard','sourceRevision':'working-tree-sha256:'+fingerprint,'status':'PASS','thresholdPercent':70,'targetClasses':['dataflow.Taint','dataflow.FrameState','dataflow.WorklistSolver','cfg.ControlFlowBuilder'],'counts':counts,'total':sum(counts.values()),'killedPercent':100*counts.get('KILLED',0)/sum(counts.values()),'rawXml':str(mutation.relative_to(root)),'rawXmlSha256':hashlib.sha256(mutation.read_bytes()).hexdigest(),'rawHtml':'projects/09-byteguard/analysis-core/target/pit-reports/index.html','survivors':[{'class':m.findtext('mutatedClass'),'method':m.findtext('mutatedMethod'),'line':m.findtext('lineNumber'),'description':m.findtext('description'),'status':m.attrib['status']} for m in mr if m.attrib['status']!='KILLED']};(out/'mutation.json').write_text(json.dumps(mutants,indent=2)+'\n')
junitVersions=set()
for module in ['analysis-core','evaluation']:
 for file in (p/module/'target/surefire-reports').glob('TEST-*.xml'):
  junitVersions.update(re.findall(r'junit-jupiter-api/([^/]+)/junit-jupiter-api-',file.read_text()))
assert junitVersions=={'6.1.3'},junitVersions
env={'jdk':subprocess.check_output(['bash','-c','source scripts/cloud-env.sh; java -version'],cwd=root,stderr=subprocess.STDOUT,text=True).strip(),'rootPomSha256':hashlib.sha256((root/'pom.xml').read_bytes()).hexdigest(),'junitEffectiveVersion':sorted(junitVersions),'cpuQuota':Path('/sys/fs/cgroup/cpu.max').read_text().strip(),'memoryMax':Path('/sys/fs/cgroup/memory.max').read_text().strip(),'os':subprocess.check_output(['uname','-srmo'],text=True).strip()}
e={'schemaVersion':1,'project':'byteguard','status':'CHECKS_PASSED_EXIT_DISCREPANCY','timestampUtc':datetime.now(timezone.utc).isoformat(),'sourceRevision':'working-tree-sha256:'+fingerprint,'command':'source scripts/cloud-env.sh; ./mvnw -f projects/09-byteguard/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A09/m2 -B -ntp -Pintegration,mutation verify','exitCode':1,'outerCommandStatus':'Earlier combined outer tool-session1 despite reactor BUILD SUCCESS; preserved until identified.','suites':suites,'testCount':sum(s['tests'] for s in suites),'packagedArtifactSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'environment':env,'rawArtifact':'.work/A09/release-final.log','limitations':['Verification covers the declared bounded model; full benchmark and asserted script demo are separate release gates.','Weak mutable heap aliases can contaminate reused helper callers; independently observed false-positive control is preserved.']};(out/'verification.json').write_text(json.dumps(e,indent=2)+'\n')
print(json.dumps({'tests':e['testCount'],'coverage':coverage['ratios'],'mutations':counts,'positiveDetected':corpus['positiveDetected'],'negativeCorrect':corpus['negativeCorrect'],'sourceRevision':fingerprint},indent=2))
