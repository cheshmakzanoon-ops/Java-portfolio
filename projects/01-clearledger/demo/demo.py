#!/usr/bin/env python3
"""Real HTTP/SQL/process demonstration, all accounts and credentials disposable fixtures."""
import base64, hashlib, hmac, json, os, pathlib, subprocess, time, urllib.error, urllib.request
project=pathlib.Path(__file__).resolve().parents[1]
tenant='00000000-0000-0000-0000-000000000101'
period='00000000-0000-0000-0000-000000000102'
a='00000000-0000-0000-0000-000000000103'
b='00000000-0000-0000-0000-000000000104'
def b64(value):return base64.urlsafe_b64encode(value).rstrip(b'=')
head=b64(json.dumps({'alg':'HS256','typ':'JWT'}).encode())
body=b64(json.dumps({'iss':'http://127.0.0.1:18109/dev-issuer','sub':'synthetic-demo-operator','aud':['clearledger'],'exp':int(time.time())+900,'tenant':tenant,'scope':'ledger.read ledger.write ledger.reconcile ledger.admin'}).encode())
unsigned=head+b'.'+body
token=(unsigned+b'.'+b64(hmac.new(b'clearledger-disposable-local-demo-key-32-bytes',unsigned,hashlib.sha256).digest())).decode()
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
process=subprocess.Popen(['java','-Xmx96m','-cp',os.environ['CLEARLEDGER_DEMO_CLASSPATH'],'io.github.cheshmakzanoon.portfolio.clearledger.CrashWriter',os.environ['LEDGER_URL'],tenant,period,a,b,'demo-crash','after',str(signal)],stdout=subprocess.DEVNULL)
try:
 deadline=time.monotonic()+30
 while not signal.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.05)
 assert signal.exists(),'writer did not reach committed barrier'
 process.kill();process.wait(timeout=10)
 receipt=request('/v1/requests/demo-crash')
 assert len(receipt)==1
 saved=json.loads(receipt[0]['response'])['journalId']
 assert request('/v1/journals',journal('0.10','crash'),'demo-crash')['journalId']==saved
 checks.append('killed writer after commit; recovered retained receipt without duplicate')
finally:
 if process.poll() is None:process.kill();process.wait(timeout=10)
 signal.unlink(missing_ok=True)
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
cmd=['docker','compose','-f',str(project/'infra/compose.yaml'),'exec','-T','postgres','psql','-At','-v','ON_ERROR_STOP=1','-U','postgres','-d','clearledger','-c',"SELECT count(*) FROM (SELECT tenant_id,journal_id FROM ledger.posting GROUP BY tenant_id,journal_id HAVING count(*)<2 OR sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END)<>0) bad;"]
assert subprocess.check_output(cmd,text=True).strip()=='0'
checks.append('source invariant audit: zero unbalanced or short journals')
source=hashlib.sha256()
for path in sorted(project.rglob('*')):
 if path.is_file() and 'target' not in path.parts and 'evidence' not in path.parts:source.update(str(path.relative_to(project)).encode());source.update(path.read_bytes())
result={'schemaVersion':1,'project':'clearledger','sourceRevision':'working-tree:'+source.hexdigest(),'workload':'five-minute-demo-v1','seed':20261017,'command':'projects/01-clearledger/scripts/demo.sh','exitCode':0,'assertions':{'passed':len(checks),'failed':0},'observations':checks,'measurements':{},'limitations':['local disposable HS256 dev profile; protected RSA/OIDC behavior tested separately in integration suite','synthetic data only; no payment network','writer killed at database commit barrier before an adapter response is emitted']}
(project/'target/demo-result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
