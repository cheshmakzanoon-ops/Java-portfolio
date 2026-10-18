from pathlib import Path
import subprocess,json,sys,datetime
root=Path('/workspace/Java-portfolio');runs=[]
for name,script,logname in [('reset','reset-demo.sh','portable-operations-reset.log'),('up','up.sh','portable-operations-up.log'),('demo','demo.sh','portable-demo.log'),('reuse','demo.sh','portable-demo-cache-reuse.log'),('down','down.sh','portable-operations-down.log')]:
 with (root/'.work/A09'/logname).open('w') as log:
  result=subprocess.run([str(root/'projects/09-byteguard/scripts'/script)],cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=2400)
 runs.append({'scenario':name,'command':script,'exitCode':result.returncode,'rawArtifact':'.work/A09/'+logname})
 (root/'.work/A09/portable-demo-exits.json').write_text(json.dumps({'timestampUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'runs':runs},indent=2)+'\n')
 if result.returncode:sys.exit(result.returncode)
print(json.dumps(runs,indent=2))
