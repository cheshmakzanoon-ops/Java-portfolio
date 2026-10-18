#!/usr/bin/env python3
"""Coordinator-only reservation: hold all build slots and record actual isolation."""
import datetime,fcntl,json,os,pathlib,signal,subprocess,sys,time
root=pathlib.Path('/workspace/Java-portfolio')
out=pathlib.Path(sys.argv[1]).resolve();command=sys.argv[2:]
if not command:raise SystemExit('Expected reservation directory and command')
out.mkdir(parents=True,exist_ok=False)
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def java_processes():
 rows=[]
 for line in subprocess.check_output(['ps','-eo','pid=,stat=,comm=,args='],text=True).splitlines():
  fields=line.split(None,3)
  if len(fields)==4 and fields[2]=='java' and not fields[1].startswith('Z'):rows.append({'pid':int(fields[0]),'state':fields[1],'command':fields[3]})
 return rows
def containers():
 rows=[]
 for line in subprocess.check_output(['docker','ps','--format','{{json .}}'],text=True).splitlines():
  row=json.loads(line)
  if 'testcontainers/ryuk' not in row['Image']:rows.append({k:row[k] for k in ['ID','Image','Names','Status']})
 return rows
record={'requestedAtUtc':now(),'command':command,'sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'lockPaths':[]}
locks=[];start=time.monotonic()
for index in range(3):
 p=root/'.work/build-slots'/f'{index}.lock';stream=p.open('a')
 while True:
  try:fcntl.flock(stream.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB);break
  except BlockingIOError:
   if time.monotonic()-start>1800:raise SystemExit('Reservation could not drain builds within1800seconds')
   time.sleep(.25)
 locks.append(stream);record['lockPaths'].append(str(p))
record.update(startedAtUtc=now(),otherJavaProcessesAtReservation=java_processes(),otherApplicationContainersAtReservation=containers(),cpuQuota=(pathlib.Path('/sys/fs/cgroup/cpu.max').read_text().strip()),memoryLimit=pathlib.Path('/sys/fs/cgroup/memory.max').read_text().strip(),filesystemType=subprocess.check_output(['stat','-f','-c','%T',str(root)],text=True).strip(),freeDiskBytes=os.statvfs(root).f_bavail*os.statvfs(root).f_frsize)
record_path=out/'reservation.json'
record_path.write_text(json.dumps(record,indent=2)+'\n')
if record['otherJavaProcessesAtReservation'] or record['otherApplicationContainersAtReservation']:
 record.update(exitCode=2,completedAtUtc=now(),result='REFUSED: host was not quiet')
 record_path.write_text(json.dumps(record,indent=2)+'\n');raise SystemExit(2)
child=None
def stop(sig,frame):
 if child is not None and child.poll() is None:
  os.killpg(child.pid,signal.SIGTERM)
  try:child.wait(timeout=30)
  except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.wait()
 record.update(exitCode=128+sig,completedAtUtc=now(),result='INTERRUPTED')
 record_path.write_text(json.dumps(record,indent=2)+'\n');raise SystemExit(128+sig)
for sig in (signal.SIGTERM,signal.SIGINT):signal.signal(sig,stop)
with (out/'command.log').open('wb') as log:
 child=subprocess.Popen(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
 status=child.wait()
record.update(exitCode=status,completedAtUtc=now(),elapsedSeconds=time.monotonic()-start,remainingJavaProcesses=java_processes(),remainingApplicationContainers=containers(),result='PASS' if status==0 else 'FAIL')
record_path.write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({k:record[k] for k in ['result','exitCode','elapsedSeconds','completedAtUtc']},indent=2),flush=True)
raise SystemExit(status if status>=0 else 128-status)
