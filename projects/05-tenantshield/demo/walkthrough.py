"""Real local OIDC and HTTP assertions. Tokens are held in memory and never printed."""
import json, pathlib, time, urllib.request, urllib.parse, urllib.error, hashlib, subprocess, datetime
BASE='http://127.0.0.1:18500/v1/tenants/'
PROJECT=pathlib.Path(__file__).resolve().parents[1]

def call(method,url,token=None,body=None,headers=None):
    data=None if body is None else json.dumps(body).encode()
    h={'Content-Type':'application/json'}
    if token: h['Authorization']='Bearer '+token
    h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url,data,headers=h,method=method),timeout=10) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code,json.loads(e.read() or '{}')

def token(user):
    body=urllib.parse.urlencode({'grant_type':'password','client_id':'portfolio-demo','username':user,'password':'local-pass'}).encode()
    with urllib.request.urlopen('http://127.0.0.1:18502/realms/tenantshield/protocol/openid-connect/token',body,timeout=10) as r:
        return json.load(r)['access_token']

def run():
    (PROJECT/'evidence/demo.json').unlink(missing_ok=True)
    alice,admin=token('alice'),token('admin')
    assertions=[]
    def check(name,condition):
        assert condition,name
        assertions.append(name)
        print('PASS '+name)
    def member(active=True,suspended=False):
        code,body=call('PUT',BASE+'harbor/memberships/alice',admin,{'roles':['reader'],'department':'support','clearance':3,'suspended':suspended,'administrative':False,'active':active})
        check('membership mutation '+str((active,suspended)),code==200)
        return body['tenantEpoch']
    member()
    for tenant in ['harbor','orchard']:
        code,current=call('POST',BASE+tenant+'/decisions',admin,{'action':'document:read','resource':{'type':'document','id':'doc-7'},'context':{}})
        check(tenant+' active version read',code==200)
        old=current['policyVersion']
        code,draft=call('POST',BASE+tenant+'/policy-versions',admin,json.loads((PROJECT/'fixtures/policy.json').read_text()))
        check(tenant+' typed draft and regressions',code==200)
        code,published=call('POST',BASE+tenant+'/policy-versions/'+str(draft['version'])+'/publish',admin,{}, {'If-Match':'"'+str(old)+'"'})
        check(tenant+' expected-version publication',code==200)
    def decision(tenant,doc,expected,reason=None):
        code,d=call('POST',BASE+tenant+'/decisions',alice,{'action':'document:read','resource':{'type':'document','id':doc},'context':{'purpose':'support'}})
        check(tenant+'/'+doc+' '+expected+((' '+reason) if reason else ''),code==200 and d['decision']==expected and (reason is None or d['reason']==reason))
        return d
    warm=decision('harbor','doc-7','ALLOW')
    decision('orchard','doc-7','DENY','NO_MEMBERSHIP')
    decision('harbor','doc-other','DENY','NO_ABAC_ALLOW')
    decision('harbor','doc-missing','DENY','INDETERMINATE')
    member(suspended=True)
    decision('harbor','doc-7','DENY','EXPLICIT_DENY')
    member()
    decision('harbor','doc-7','ALLOW')
    code,_=call('GET','http://127.0.0.1:18501/v1/tenants/harbor/documents/doc-7',alice)
    check('protected resource allows fresh authorized request',code==200)
    epoch=member(active=False)
    revoked=decision('harbor','doc-7','DENY','NO_MEMBERSHIP')
    check('revocation epoch exceeds warmed-cache decision',revoked['tenantEpoch']>=epoch>warm['tenantEpoch'])
    code,audits=call('GET',BASE+'harbor/audit',admin)
    check('authorized redacted audit contains revocation epoch',code==200 and any(a['epoch']>=epoch and a['result']=='DENY' for a in audits) and all(len(a['principal'])==64 for a in audits))
    code,_=call('GET','http://127.0.0.1:18501/v1/tenants/harbor/documents/doc-7',alice)
    check('protected resource refuses revoked principal',code==403)
    fingerprint=subprocess.check_output(['python3',str(PROJECT/'scripts/fingerprint.py')],text=True).strip()
    report={'schemaVersion':1,'project':'tenantshield','sourceRevision':fingerprint,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'environment.json','rawArtifact':'demo-output.txt','workload':'protected-oidc-demo-v1','command':'projects/05-tenantshield/scripts/demo.sh','exitCode':0,'assertions':{'passed':len(assertions),'failed':0},'scenarios':assertions,'measurements':{'revocationEpoch':epoch},'limitations':['Disposable loopback fixture credentials; no production deployment.']}
    (PROJECT/'evidence/demo.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':run()
