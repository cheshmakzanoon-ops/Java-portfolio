from pathlib import Path
import hashlib,json,xml.etree.ElementTree as E,datetime,re,sys
root=Path('/workspace/Java-portfolio');p=root/'projects/01-clearledger';log=root/(sys.argv[1] if len(sys.argv)>1 else '.work/coordinator/clearledger-strict-json-integration.log')
assert '[INFO] BUILD SUCCESS' in log.read_text(), 'full integration has not passed'
reports=[];methods=[]
for f in sorted(p.glob('*/target/*-reports/TEST-*.xml')):
 if 'surefire' not in str(f) and 'failsafe' not in str(f):continue
 x=E.parse(f).getroot();counts={k:int(x.attrib.get(k,'0')) for k in ['tests','failures','errors','skipped']};reports.append({'file':str(f.relative_to(root)),**counts})
 for c in x.findall('testcase'):methods.append({'class':c.attrib['classname'],'method':c.attrib['name']})
assert reports and not sum(r['failures']+r['errors']+r['skipped'] for r in reports)
h=hashlib.sha256();manifest={}
for f in sorted(p.rglob('*')):
 if f.is_file() and 'target' not in f.parts and (f.suffix in ['.java','.sql'] or f.name=='pom.xml'):
  rel=str(f.relative_to(p));h.update(rel.encode()+b'\0'+f.read_bytes()+b'\0');manifest[rel]=hashlib.sha256(f.read_bytes()).hexdigest()
dependencyJars=set()
for f in p.glob('*/target/*-reports/TEST-*.xml'):
 for entry in E.parse(f).getroot().findall('properties/property'):
  if entry.attrib.get('name') in ['java.class.path','surefire.test.class.path']:
   dependencyJars.update(x.rsplit('/',1)[-1] for x in entry.attrib.get('value','').split(':') if x.endswith('.jar'))
coverage={}
x=E.parse(p/'ledger-domain/target/site/jacoco/jacoco.xml').getroot()
for c in x.findall('counter'):
 if c.attrib['type'] in ['LINE','BRANCH']:coverage[c.attrib['type']]={k:int(c.attrib[k]) for k in ['covered','missed']}
obj={'schemaVersion':1,'project':'clearledger','recordedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceRevision':'working-tree:java-sql-pom-sha256:'+h.hexdigest(),'sourceHashScope':'sorted project-relative Java/SQL/POM path+NUL+bytes+NUL, excluding target','rootPomSha256':hashlib.sha256((root/'pom.xml').read_bytes()).hexdigest(),'command':'source scripts/cloud-env.sh; ./mvnw -f projects/01-clearledger/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A01/m2 -B -ntp -Pintegration verify','rawArtifact':str(log.relative_to(root)),'exitCode':0,'testReports':reports,'testMethods':methods,'testTotal':sum(r['tests'] for r in reports),'propertyCases':500,'actualDependencyJars':sorted(dependencyJars),'coreCoverage':coverage,'sourceManifest':manifest,'acceptance':{'P01-A1':{'status':'PASS','methods':['databaseMatchesIndependentGeneratedModelAndPrivileges','rawRuntimeSqlRejectsGeneratedInvalidJournalsAtomically','sqlNullMalformedShapesAndDeferredBackstopRejectAtomically','balanceOverflowRejectsWholeJournalAndReceipt','sixtyFourDuplicateRequestsAndRacingReversals']},'P01-A2':{'status':'PASS','methods':['sixtyFourDuplicateRequestsAndRacingReversals','closeRacesWritersWithoutPostingIntoClosedPeriod','actualProcessTerminationBeforeAndAfterCommit','protectedOidcAndTwoApiInstances','statementSnapshotExcludesLaterCommittedJournal']},'P01-A3':{'status':'PASS','methods':['reconciliationDistinguishesRowsAndPreservesDuplicateImport','blankReferencesNeverAutomatchOnAmountAlone']},'P01-A4':{'status':'PASS','methods':['projectionHandlesEarlierAllocatedEventCommittedLater','backupRestoreReauditsImmutableSource','sourceAuditFindsAdministratorCorruptionOutsideRuntimePermissions']},'P01-A5':{'status':'IN_PROGRESS','remaining':['actual repeatable demo/reset/up/down','full quiet100k workload writer1/8/32 three rounds','JMH/JFR/raw environment and performance interpretation','clean-checkout certification']}},'limitations':['bounded generated, race and subprocess histories on one host','system workload and demo are distinct unrun gates','historical mutation evidence retained separately; no implied whole-adapter mutation coverage']}
(p/'evidence/integration-current.json').write_text(json.dumps(obj,indent=2)+'\n')
print(json.dumps({k:obj[k] for k in ['sourceRevision','rootPomSha256','testTotal','coreCoverage']}))
