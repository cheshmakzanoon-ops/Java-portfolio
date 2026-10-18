#!/usr/bin/env python3
"""Compare preserved versus rebuilt payloads without treating ZIP timestamps as code changes."""
import hashlib,io,json,sys,zipfile
from pathlib import Path
root=Path('/workspace/Java-portfolio');archive=Path(sys.argv[1]);output=Path(sys.argv[2]);project=root/'projects/04-pulsestream'
def sha(data):return hashlib.sha256(data).hexdigest()
def zip_compare(old,new,prefix=''):
 differences=[];metadata=[]
 with zipfile.ZipFile(io.BytesIO(old)) as left,zipfile.ZipFile(io.BytesIO(new)) as right:
  lm={x.filename:x for x in left.infolist()};rm={x.filename:x for x in right.infolist()}
  if list(lm)!=list(rm):metadata.append({'path':prefix,'entryOrderChanged':True})
  for name in sorted(set(lm)|set(rm)):
   path=prefix+name
   if name not in lm or name not in rm:differences.append({'path':path,'presence':'added' if name in rm else 'removed'});continue
   a,b=left.read(name),right.read(name)
   if lm[name].date_time!=rm[name].date_time:metadata.append({'path':path,'zipTimestampBefore':lm[name].date_time,'zipTimestampAfter':rm[name].date_time})
   if a==b:continue
   if name.endswith('.jar') and a.startswith(b'PK') and b.startswith(b'PK'):
    d,m=zip_compare(a,b,path+'!/');differences.extend(d);metadata.extend(m)
   else:differences.append({'path':path,'beforeSha256':sha(a),'afterSha256':sha(b)})
 return differences,metadata
records=[];class_differences=[];classes=0
for module in ('engine','kafka-adapter','postgres-store','service','verification'):
 before=archive/'prior/module-targets'/module;after=project/module/'target'
 lm={str(p.relative_to(before/'classes')):p for p in (before/'classes').rglob('*') if p.is_file()};rm={str(p.relative_to(after/'classes')):p for p in (after/'classes').rglob('*') if p.is_file()}
 for name in sorted(set(lm)|set(rm)):
  classes+=name.endswith('.class')
  if name not in lm or name not in rm or lm[name].read_bytes()!=rm[name].read_bytes():class_differences.append({'module':module,'path':name})
 for old in sorted(before.glob('*.jar')):
  new=after/old.name
  if not new.exists():records.append({'module':module,'jar':old.name,'missingCurrent':True});continue
  a,b=old.read_bytes(),new.read_bytes();d,m=zip_compare(a,b)
  records.append({'module':module,'jar':old.name,'previousSha256':sha(a),'rebuiltSha256':sha(b),'binaryIdentical':a==b,'recursiveEntryPayloadIdentical':not d,'contentDifferences':d,'zipMetadataDifferences':m})
result={'schemaVersion':1,'project':'pulsestream','comparison':'Preserved prepared artifacts versus actual cold/repackaged rebuild; no product source changes','mainClassFilesCompared':classes,'mainClassOrResourceDifferences':class_differences,'jars':records,'allRecursiveJarPayloadsIdentical':all(x.get('recursiveEntryPayloadIdentical',False) for x in records),'allJarBinaryHashesIdentical':all(x.get('binaryIdentical',False) for x in records),'limitation':'ZIP metadata/order differences are disclosed; unchanged source alone is not asserted to imply byte-identical JARs.'}
output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'classFilesCompared':classes,'classOrResourceDifferences':len(class_differences),'jarCount':len(records),'binaryIdenticalJars':sum(x.get('binaryIdentical',False) for x in records),'recursivePayloadIdentical':result['allRecursiveJarPayloadsIdentical']}))
