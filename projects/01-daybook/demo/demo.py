#!/usr/bin/env python3
"""Real HTTP/SQL/process demonstration, all accounts and credentials disposable fixtures."""
import concurrent.futures, secrets, base64, hashlib, hmac, json, os, pathlib, subprocess, time, urllib.error, urllib.request
project=pathlib.Path(__file__).resolve().parents[1]
tenant='00000000-0000-0000-0000-000000000101'
period='00000000-0000-0000-0000-000000000102'
a='00000000-0000-0000-0000-000000000103'
b='00000000-0000-0000-0000-000000000104'
def b64(value):return base64.urlsafe_b64encode(value).rstrip(b'=')
head=b64(json.dumps({'alg':'HS256','typ':'JWT'}).encode())
body=b64(json.dumps({'iss':'http://127.0.0.1:18109/dev-issuer','sub':'synthetic-demo-operator','aud':['daybook'],'exp':int(time.time())+900,'tenant':tenant,'scope':'ledger.read ledger.write ledger.reconcile ledger.admin ledger.system'}).encode())
unsigned=head+b'.'+body
token=(unsigned+b'.'+b64(hmac.new(b'daybook-disposable-local-demo-key-32-bytes',unsigned,hashlib.sha256).digest())).decode()
checks=[]
def request(path,data=None,key=None,expected=200,csv=False):
 raw=data if isinstance(data,bytes) else json.dumps(data).encode() if data is not None else None
 headers={'Authorization':'Bearer '+token}
 if raw is not None:headers['Content-Type']='text/csv' if csv else 'application/json'
 if key:headers['Idempotency-Key']=key
 req=urllib.request.Request('http://127.0.0.1:18100'+path,data=raw,headers=headers)
 try:
  with urllib.request.urlopen(req,timeout=30) as r: status=r.status; result=r.read().decode()
 except urllib.error.HTTPError as e:status=e.code;result=e.read().decode()
 assert status==expected,(path,status,result)
 return json.loads(result) if result else None
def journal(amount='1.00',reference='demo-original'):
 return {'period':period,'date':'2026-01-02','currency':'CAD','reference':reference,'lines':[{'account':a,'side':'DEBIT','amount':amount},{'account':b,'side':'CREDIT','amount':amount}]}
j=journal();original=request('/v1/journals',j,'demo-original')['journalId']
checks.append('balanced journal committed')
bad=journal();bad['lines'][1]['amount']='0.99'
assert request('/v1/journals',bad,'unbalanced',422)['code']=='UNBALANCED_OR_OVERFLOW'
checks.append('unbalanced journal rejected')
assert request('/v1/journals',j,'demo-original')['journalId']==original
checks.append('same key returned original identifier')
signal=project/'target/crash-after.ready';signal.unlink(missing_ok=True)
port_file=project/'target/crash-after.port';port_file.unlink(missing_ok=True)
nonce=secrets.token_urlsafe(32)
executor=concurrent.futures.ThreadPoolExecutor(max_workers=1)
process=subprocess.Popen(['java','-Xmx96m','-cp',os.environ['DAYBOOK_DEMO_CLASSPATH'],'io.github.cheshmakzanoon.portfolio.daybook.CrashWriter',os.environ['LEDGER_URL'],tenant,period,a,b,'demo-crash','after',str(signal),str(port_file),nonce],stdout=subprocess.DEVNULL)
try:
 deadline=time.monotonic()+30
 while not port_file.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.05)
 assert port_file.exists(),'HTTP writer did not start'
 def invoke_crash():
  with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:'+port_file.read_text().strip()+'/post',data=b'',headers={'Authorization':'Bearer '+nonce}),timeout=40) as response:return response.read()
 pending=executor.submit(invoke_crash)
 while not signal.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.05)
 assert signal.exists(),'writer did not reach committed barrier'
 assert not pending.done(),'HTTP response completed before kill'
 process.kill();process.wait(timeout=10)
 try:pending.result(timeout=10);raise AssertionError('HTTP call unexpectedly succeeded')
 except (OSError, __import__('http.client',fromlist=['RemoteDisconnected']).RemoteDisconnected):pass
 receipt=request('/v1/requests/demo-crash')
 assert len(receipt)==1
 saved=json.loads(receipt[0]['response'])['journalId']
 assert request('/v1/journals',journal('0.10','crash'),'demo-crash')['journalId']==saved
 checks.append('killed writer after commit; recovered retained receipt without duplicate')
finally:
 if process.poll() is None:process.kill();process.wait(timeout=10)
 signal.unlink(missing_ok=True);port_file.unlink(missing_ok=True);executor.shutdown(wait=True)
reverse=request('/v1/journals/'+original+'/reversals',{'date':'2026-02-01'},'demo-reverse')['journalId']
assert request('/v1/journals/'+original+'/reversals',{'date':'2026-02-01'},'demo-reverse')['journalId']==reverse
checks.append('full reversal preserved source; repeated reversal request returned receipt')
request('/v1/journals',journal('2.00','ambiguous'),'demo-ambiguous-1')
request('/v1/journals',journal('2.00','ambiguous'),'demo-ambiguous-2')
csv=b'source_id,reference,currency,amount\n1,demo-original,CAD,1.00\n2,ambiguous,CAD,2.00\n3,missing,CAD,3.00\n1,demo-original,CAD,1.00\n4,invalid,CAD,1.001\n'
imported=request('/v1/statements',csv,csv=True)['importId']
assert request('/v1/statements',csv,csv=True)['importId']==imported
rows=request('/v1/reconciliation/'+imported)
assert [r['status'] for r in rows]==['MATCHED','AMBIGUOUS','UNMATCHED','DUPLICATE','INVALID'],rows
checks.append('same file preserved results: matched, ambiguous, unmatched, duplicate, invalid')
print('Unresolved ambiguity:',json.dumps(rows[1],sort_keys=True))
replayed=request('/v1/reports/replay',{})
assert request('/v1/reports/freshness')['pendingEvents']==0
report=request('/v1/reports/accounts/'+a)
assert report['authoritative'] is False and report['pendingEvents']==0
assert int(report['balances'][0]['debits_minor'])-int(report['balances'][0]['credits_minor'])==410
checks.append('verified shadow replay promoted with zero pending events')
statement=request('/v1/accounts/'+a+'/statement?limit=2')
assert len(statement['rows'])==2 and statement['snapshot']
checks.append('authoritative statement returned keyset cursor and visibility boundary')
audit_sql=(project/'ledger-verification/src/main/resources/daybook/source-audit.sql').read_text().strip().rstrip(';')
cmd=['docker','compose','-f',str(project/'infra/compose.yaml'),'exec','-T','postgres','psql','-At','-v','ON_ERROR_STOP=1','-U','postgres','-d','daybook','-c','SELECT count(*) FROM ('+audit_sql+') bad;']
assert subprocess.check_output(cmd,text=True).strip()=='0'
checks.append('complete source audit: zero journal count/amount/currency/date/reversal violations')
root=project.parents[1]
source=hashlib.sha256(); manifest={}
paths=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','projects/01-daybook'],cwd=root,text=True).splitlines()
for relative in sorted(set(paths)):
 path=root/relative
 if not path.is_file() or '/docs/' in relative or '/evidence/' in relative:continue
 content=path.read_bytes();source.update(relative.encode()+b'\0'+content+b'\0');manifest[relative]=hashlib.sha256(content).hexdigest()
result={'schemaVersion':1,'project':'daybook','sourceRevision':'working-tree:'+source.hexdigest(),'sourceManifest':manifest,'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'rootPomSha256':hashlib.sha256((root/'pom.xml').read_bytes()).hexdigest(),'workload':'five-minute-demo-v1','seed':20261017,'command':'projects/01-daybook/scripts/demo.sh','exitCode':0,'assertions':{'passed':len(checks),'failed':0},'observations':checks,'measurements':{},'limitations':['local disposable HS256 dev profile; protected RSA/OIDC behavior tested separately in integration suite','synthetic data only; no payment network','writer killed at database commit barrier before response headers of an actual HTTP request are emitted']}
(project/'target/demo-result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
