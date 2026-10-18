import datetime,hashlib,json,pathlib,subprocess,xml.etree.ElementTree as E
root=pathlib.Path.cwd()
for slug,owner,last_module,mode in [('06-matchcore','A06','benchmarks','MATCHCORE_PREPARED'),('03-quorumkv','A03','raft-verification','QUORUMKV_PREPARED')]:
 project=root/'projects'/slug;tool=project/'scripts/runtime-artifacts.py';receipt=project/last_module/'target/verified-artifacts.json';data=json.loads(receipt.read_text());paths=[pathlib.Path(p) for p in data['artifacts']];rows=[]
 function='matchcore_build' if owner=='A06' else 'quorum_build'
 def check(label,expected,prepared=False):
  cmd=['python3',str(tool),'check'] if not prepared else ['bash','-c',f'export {mode}=1; source projects/{slug}/scripts/common.sh; {function}']
  p=subprocess.run(cmd,capture_output=True,text=True);rows.append({'case':label,'exitCode':p.returncode,'expectedSuccess':expected==0,'stderr':p.stderr.strip()})
  assert (p.returncode==0)==(expected==0),(label,p.returncode,p.stderr)
 check('untouched actual verified outputs',0)
 for label,target in [('modified compiled class',next(p for p in paths if p.suffix=='.class')),('modified dependency JAR',next(p for p in paths if p.suffix=='.jar')),('modified runtime classpath',project/last_module/'target/runtime-classpath.txt'),('changed executable source',tool)]:
  original=target.read_bytes()
  try:target.write_bytes(original+b'\n');check(label,1,True)
  finally:target.write_bytes(original)
  check('exact restoration after '+label,0)
 extra=project/last_module/'target/classes/cache-probe.txt'
 try:extra.write_text('injected resource');check('unexpected extra compiled resource',1,True)
 finally:extra.unlink()
 for label,target in [('symbolic link compiled root',project/last_module/'target/classes'),('symbolic link dependency JAR',next(p for p in paths if p.suffix=='.jar'))]:
  backup=target.with_name(target.name+'.cache-probe-backup');assert not backup.exists();target.rename(backup)
  try:target.symlink_to(backup,target_is_directory=backup.is_dir());check(label,1,True)
  finally:target.unlink();backup.rename(target)
  check('exact restoration after '+label,0)
 link=project/last_module/'target/classes/cache-probe-link'
 try:link.symlink_to(project/last_module/'target/classes',target_is_directory=True);check('symbolic link nested directory',1,True)
 finally:link.unlink()
 check('actual prepared cache reuse without Maven',0,True)
 variable='classpath' if owner=='A06' else 'CLASSPATH'
 cmd=f'source projects/{slug}/scripts/common.sh; '+('matchcore_classpath' if owner=='A06' else 'quorum_classpath')+f'; [[ ":${variable}:" != *::* ]]'
 p=subprocess.run(['bash','-c',cmd],capture_output=True,text=True);assert p.returncode==0,p.stderr
 rows.append({'case':'effective Java classpath contains no caller-CWD empty element','exitCode':p.returncode,'expectedSuccess':True})
 source=subprocess.check_output(['python3',str(project/'scripts/source-fingerprint.py')],text=True).strip()
 log=root/'.work/coordinator'/('matchcore-artifact-boundary-verify.log' if owner=='A06' else 'quorumkv-artifact-boundary-verify.log')
 started=int(subprocess.check_output(['stat','-c','%W',str(log)],text=True));assert started>0
 suites=[E.parse(p).getroot() for p in project.glob('*/target/surefire-reports/TEST-*.xml') if p.stat().st_mtime>=started]
 counts={k:sum(int(s.get(k,0)) for s in suites) for k in ('tests','failures','errors','skipped')};assert counts['tests']>0 and sum(counts[k] for k in ('failures','errors','skipped'))==0,counts
 assert 'BUILD SUCCESS' in log.read_text()
 evidence={'observedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceFingerprint':source,'scope':'Operational artifact cache repair after A05 review; no production Java changes','ordinaryVerification':{'exitCode':0,'counts':counts,'freshReportCutoffUnix':started,'rawLog':str(log.relative_to(root)),'sha256':hashlib.sha256(log.read_bytes()).hexdigest()},'actualArtifactChecks':rows,'snapshotArtifactCount':len(paths),'quietPreparedMode':mode+'=1 refuses absent/stale source or artifacts before Maven','limitations':['Trusted local build receipt, not a cryptographic attestation.','Full mandatory system measurements remain unrun.']}
 (project/'evidence/runtime-artifact-cache.json').write_text(json.dumps(evidence,indent=2)+'\n')
 print(json.dumps({'project':slug,'source':source,'ordinary':counts,'checks':len(rows),'artifacts':len(paths)}),flush=True)
