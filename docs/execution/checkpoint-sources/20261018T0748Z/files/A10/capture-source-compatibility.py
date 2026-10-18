from pathlib import Path
import collections, datetime, hashlib, json, re, shutil, xml.etree.ElementTree as ET
root=Path('/workspace/Java-portfolio'); project=root/'projects/10-routeweaver'; work=root/'.work/A10'; evidence=project/'evidence'
log=(work/'source-compatibility-integration.log').read_text()
assert '[INFO] BUILD SUCCESS' in log and '[INFO] BUILD FAILURE' not in log
completed=re.search(r'Finished at: (\S+)',log).group(1)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
paths=sorted([x for x in project.rglob('*') if x.is_file() and 'target' not in x.parts and '.runtime' not in x.parts and (x.suffix in ('.java','.xml','.properties','.sql','.html','.sh','.yaml','.py') or x.name=='pom.xml')]+[root/'pom.xml'])
h=hashlib.sha256(); manifest={}
for path in paths:
    name=str(path.relative_to(project)) if path.is_relative_to(project) else 'root-pom.xml'
    h.update(name.encode());h.update(path.read_bytes());manifest[name]=sha(path)
source=h.hexdigest(); suites=[]; methods=[]; reportdir=work/'source-compatibility-reports';reportdir.mkdir(exist_ok=True)
for module in ('routeweaver-domain','routeweaver-planning','routeweaver-service','routeweaver-verification'):
    for kind, directory in (('ordinary','surefire-reports'),('postgresql','failsafe-reports')):
        for path in sorted((project/module/'target'/directory).glob('TEST-*.xml')):
            report=ET.parse(path).getroot()
            item={'module':module,'suite':report.attrib['name'],'kind':kind,'report':str(path.relative_to(project)),'reportSha256':sha(path),**{key:int(report.attrib[key]) for key in ('tests','failures','errors','skipped')}}
            assert not sum(item[k] for k in ('failures','errors','skipped')),item
            suites.append(item)
            for method in report.findall('testcase'):methods.append({'suite':report.attrib['name'],'method':method.attrib['name']})
            dest=reportdir/module/directory;dest.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,dest/path.name)
ordinary=sum(s['tests'] for s in suites if s['kind']=='ordinary');pg=sum(s['tests'] for s in suites if s['kind']=='postgresql')
assert (ordinary,pg)==(40,14),(ordinary,pg)
assert any(m['method']=='dispatcherFencesSourceMismatchAndResumesOnlyMatchingSourceWithoutMislabeledCandidates' for m in methods)
assert any('junit-jupiter-engine/6.1.3/' in p.read_text() for p in (project/'routeweaver-service/target/surefire-reports').glob('TEST-*.xml'))
coverage={}
for module in ('routeweaver-domain','routeweaver-planning'):
    path=project/module/'target/site/jacoco/jacoco.xml'
    coverage[module]={x.attrib['type']:{'covered':int(x.attrib['covered']),'missed':int(x.attrib['missed'])} for x in ET.parse(path).getroot().findall('counter') if x.attrib['type'] in ('BRANCH','LINE')}
    for counter,minratio in (('LINE',.85),('BRANCH',.75)):
        c=coverage[module][counter];assert c['covered']/(c['covered']+c['missed'])>=minratio
pit=project/'routeweaver-planning/target/pit-reports'; statuses=collections.Counter(x.attrib['status'] for x in ET.parse(pit/'mutations.xml').getroot())
assert statuses=={'KILLED':82},statuses
preserved=work/'pit-source-compatibility';shutil.copytree(pit,preserved,dirs_exist_ok=True)
mutation={'target':'io.github.cheshmakzanoon.portfolio.routeweaver.Validator*','generated':sum(statuses.values()),'statusCounts':dict(statuses),'strictKillPercent':100,'thresholdPercent':70,'xml':str((pit/'mutations.xml').relative_to(project)),'html':str((pit/'index.html').relative_to(project)),'xmlSha256':sha(pit/'mutations.xml'),'preservedCopy':str(preserved.relative_to(root))}
command='source scripts/cloud-env.sh; ./mvnw -f projects/10-routeweaver/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A10/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration,mutation,benchmarks verify'
old=json.loads((evidence/'integration.json').read_text()); historical=evidence/('integration-historical-'+old['sourceRevision'].split(':')[1][:8]+'.json')
if not historical.exists():shutil.copyfile(evidence/'integration.json',historical)
result={'schemaVersion':1,'project':'routeweaver','sourceRevision':'working-tree-sha256:'+source,'sourceHashScope':'sorted project-relative runtime Java/POM/XML/properties/SQL/HTML/shell/YAML/Python path+bytes plus root POM; excludes target/.runtime and Markdown/JSON documentation/evidence','rootPomSha256':sha(root/'pom.xml'),'completedAtUtc':completed,'recordedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'command':command,'exitCode':0,'testMethods':ordinary+pg,'ordinaryMethods':ordinary,'postgresqlMethods':pg,'propertyChecks':40,'completeOracleLeaves':95901,'effectiveJUnit':'6.1.3','testSuites':suites,'executedMethods':methods,'coreCoverage':coverage,'mutation':mutation,'rawLog':'.work/A10/source-compatibility-integration.log','preservedReports':str(reportdir.relative_to(root)),'sourceManifest':manifest,'sourceCompatibilityRegression':{'expiredOldSource':'FAILED with unchanged independently valid historical incumbent, no new candidate; old fence cannot finish/publish','queuedOldSource':'FAILED without incumbent or trace','sameSourceRecovery':'COMPLETED at newer fence with valid nondegraded incumbent and new authoritative candidate','newSourceSubmission':'COMPLETED'},'historicalLiveEvidence':'live.json records actual first/repeat 60-job demos, Chromium and SIGKILL under source2aa7d7da; not relabeled to current binary','acceptance':{'P10-A1':'PASS scoped bounded validator/oracle checks','P10-A2':'PASS baseline/Timefold/directed/residual/nondegradation checks','P10-A3':'PASS current real PostgreSQL recovery/fencing/source-compatibility; historical actual process SIGKILL preserved','P10-A4':'PASS historical real60-job6-vehicle demo; compact-UI final-browser preflight pending','P10-A5':'IN_PROGRESS quiet corpus/JMH and clean-checkout pending'},'unrun':['final-source compact-UI browser preflight','quiet10instance3runcorpus','quietJMHmeasurement','finalcleancheckout'],'limitations':['bounded checks on a shared host are not universal proofs','no large-instance optimality or production claim','no accepted performance run yet']}
(evidence/'integration.json').write_text(json.dumps(result,indent=2)+'\n')
(evidence/'mutation.json').write_text(json.dumps({k:result[k] for k in ('schemaVersion','project','sourceRevision','rootPomSha256','completedAtUtc','command','exitCode','testMethods','effectiveJUnit','coreCoverage','mutation','rawLog','unrun')},indent=2)+'\n')
env=json.loads((evidence/'environment.json').read_text());env.update(sourceRevision=result['sourceRevision'],rootPomSha256=result['rootPomSha256']);(evidence/'environment.json').write_text(json.dumps(env,indent=2)+'\n')
(project/'.runtime/build-stamp').write_text(source+'\n')
print(json.dumps({'source':source,'completed':completed,'tests':ordinary+pg,'postgresql':pg,'mutations':dict(statuses),'coverage':coverage},indent=2))
