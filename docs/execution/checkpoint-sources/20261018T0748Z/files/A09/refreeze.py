from pathlib import Path
import json,hashlib,shutil
r=Path('projects/09-byteguard/corpus'); archive=r/'archive/frozen-v1';archive.mkdir(parents=True,exist_ok=True)
shutil.copytree(r/'src',archive/'src',dirs_exist_ok=True)
shutil.copyfile(r/'labels/SHA256SUMS',r/'labels/SHA256SUMS-v1')
v1=json.loads((r/'labels/frozen-v1.json').read_text());labels=[]
def rewrite_calls(text,name,transform):
 pos=0
 while True:
  start=text.find(name+'(',pos)
  if start<0:return text
  opening=start+len(name); depth=1;i=opening+1
  while depth:
   if text[i]=='(':depth+=1
   elif text[i]==')':depth-=1
   i+=1
  arg=text[opening+1:i-1];replacement=name+'('+transform(arg)+')';text=text[:start]+replacement+text[i:];pos=start+len(replacement)
for item in v1['labels']:
 item=dict(item);p=r/item['file'];text=p.read_text();family=item['rule'];positive=item['positive']
 if family=='BG-SQL-TEXT':
  text=text.replace('static void sink(String text)throws Exception{java.sql.Statement statement=null;statement.execute(text);}', 'static void sink(String text,java.sql.Statement statement)throws Exception{statement.execute(text);}')
  before,sep,body=text.partition('public static void run()throws Exception{')
  assert sep
  body=body.replace('java.sql.Connection conn=null;','')
  body=rewrite_calls(body,'sink',lambda arg:arg+',statement')
  text=before+'public static void run(java.sql.Statement statement,java.sql.Connection conn)throws Exception{'+body
  item['rationale']+=' Statement/Connection are supplied method parameters; receiver non-null is a caller precondition.'
 elif family=='BG-SHELL-COMMAND':
  text=text.replace('new String[]{"/bin/sh","-c",text}', 'new String[]{"/bin/sh","-c","/bin/echo "+text}')
  text=text.replace('new String[]{"/bin/sh","-c",input}', 'new String[]{"/bin/sh","-c","/bin/echo "+input}')
  item['rationale']+=' Shell helper invokes fixed /bin/echo with a command-text token, not a user-selected executable.'
 elif not positive:
  # Guard the final transformed path; never imply arbitrary post-sanitizer transformations preserve confinement.
  text=text.replace('fixtures.Safe.confinedPath(input)','input').replace('Safe.confinedPath(input)','input')
  before,sep,body=text.partition('public static void run()throws Exception{');assert sep
  body=rewrite_calls(body,'sink',lambda arg:'Safe.confinedPath('+arg+')')
  text=before+sep+body
  item['rationale']='External source and matching '+item['pattern']+' transformation are retained; exact trusted Safe.confinedPath validates the FINAL path immediately before filesystem access. Lexical confinement to /sandbox, no symlink-security claim.'
 p.write_text(text);item['sha256']=hashlib.sha256(text.encode()).hexdigest();labels.append(item)
result={'schemaVersion':2,'frozenBeforeEngineEvaluation':True,'revisionReason':'Independent coordinator semantic review BEFORE corpus metrics/tuning; supplied SQL interfaces, fixed shell executable, final-path validation. v1 sources archived under archive/frozen-v1/src.','labels':labels}
(r/'labels/frozen-v2.json').write_text(json.dumps(result,indent=2)+'\n');(r/'labels/SHA256SUMS-v2').write_text(''.join(x['sha256']+'  '+x['file']+'\n' for x in labels));(r/'labels/SHA256SUMS').write_text((r/'labels/SHA256SUMS-v2').read_text())
