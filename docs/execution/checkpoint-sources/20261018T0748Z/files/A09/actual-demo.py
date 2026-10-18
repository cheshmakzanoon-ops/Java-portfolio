from pathlib import Path
import subprocess,json,sys,datetime
root=Path('/workspace/Java-portfolio');runs=[]
for name,script,logname in [('reset','reset-demo.sh','operations-reset.log'),('up','up.sh','operations-up.log'),('demo','demo.sh','demo.log'),('reuse','demo.sh','demo-cache-reuse.log'),('down','down.sh','operations-down.log')]:
 with (root/'.work/A09'/logname).open('w') as log:
  result=subprocess.run([str(root/'projects/09-byteguard/scripts'/script)],cwd=root,stdout=log,stderr=subprocess.STDOUT,timeout=2400)
 runs.append({'scenario':name,'command':script,'exitCode':result.returncode,'rawArtifact':'.work/A09/'+logname})
 (root/'.work/A09/actual-demo-exits.json').write_text(json.dumps({'timestampUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'runs':runs},indent=2)+'\n')
 if result.returncode:sys.exit(result.returncode)
print(json.dumps(runs,indent=2))
