#!/usr/bin/env python3
"""Capture non-sensitive host/runtime measurement facts; never dump environment variables."""
import datetime,json,os,platform,subprocess,sys
from pathlib import Path
project=Path(__file__).resolve().parents[1]
def read(path):
 try:return Path(path).read_text().strip()
 except OSError:return None
def command(args):
 r=subprocess.run(args,text=True,capture_output=True,timeout=15)
 return {'exitCode':r.returncode,'output':(r.stdout+r.stderr).strip()}
model=next((l.split(':',1)[1].strip() for l in (read('/proc/cpuinfo') or '').splitlines() if l.startswith('model name')),platform.processor())
result={'schemaVersion':1,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'os':platform.system(),'kernel':platform.release(),'architecture':platform.machine(),'cpuModel':model,'logicalCpuCount':os.cpu_count(),'affinityCpuCount':len(os.sched_getaffinity(0)) if hasattr(os,'sched_getaffinity') else None,'cgroupCpuMax':read('/sys/fs/cgroup/cpu.max'),'cgroupMemoryMax':read('/sys/fs/cgroup/memory.max'),'jdk':command(['java','-version']),'sourceRevision':command(['python3',str(project/'scripts/fingerprint.py')])['output'],'containerImages':command(['docker','image','inspect','postgres:18.3-alpine','apache/kafka:4.2.1','--format','{{json .RepoDigests}}']),'limits':{'heap':'512MiB','partitions':8,'fullKeys':10000,'fullMeasurements':1000000,'queueBatches':8,'maximumFetchedRecords':1000},'boundary':'single shared host; PostgreSQL and Kafka containers share CPU/memory; Java process CPU excludes their work'}
Path(sys.argv[1]).write_text(json.dumps(result,indent=2)+'\n')
