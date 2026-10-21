#!/usr/bin/env python3
"""Unix reference-host process measurements, retaining the child's actual exit status."""
import datetime,json,pathlib,resource,subprocess,sys,time
output=pathlib.Path(sys.argv[1]);command=sys.argv[2:]
started=time.monotonic();process=subprocess.run(command,check=False);usage=resource.getrusage(resource.RUSAGE_CHILDREN)
result={'schemaVersion':1,'recordedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'command':command,'exitCode':process.returncode,'wholeProcessElapsedSeconds':time.monotonic()-started,'userCpuSeconds':usage.ru_utime,'systemCpuSeconds':usage.ru_stime,'peakProcessRssBytes':usage.ru_maxrss*(1 if sys.platform=='darwin' else 1024),'voluntaryContextSwitches':usage.ru_nvcsw,'involuntaryContextSwitches':usage.ru_nivcsw,'measurementBoundary':'entire child JVM including startup/reference generation/SQL writes/audit/replay; differs from posting-only throughput interval','limitations':['Unix getrusage child process accounting','PostgreSQL CPU/RSS excluded; database shares reference host']}
output.write_text(json.dumps(result,indent=2)+'\n');sys.exit(process.returncode if process.returncode>=0 else 128-process.returncode)
