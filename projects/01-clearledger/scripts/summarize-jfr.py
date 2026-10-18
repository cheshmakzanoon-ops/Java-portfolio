#!/usr/bin/env python3
"""Summarize sampled JFR observations with event counts and explicit limitations."""
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);events=json.loads((p/'jfr-events.json').read_text())['recording']['events']
def selected(name):return [e['values'] for e in events if e['type']==name]
load=selected('jdk.CPULoad');gc=selected('jdk.GarbageCollection');allocation=selected('jdk.ObjectAllocationSample');heap=selected('jdk.GCHeapSummary')
obj={'schemaVersion':1,'rawArtifact':str(p/'workload.jfr'),'eventCounts':{'cpuLoad':len(load),'garbageCollection':len(gc),'heapSummary':len(heap),'allocationSample':len(allocation)},'meanSampledJvmCpuLoad':sum(e['jvmUser']+e['jvmSystem'] for e in load)/len(load) if load else None,'maxSampledHeapUsedBytes':max((e['heapUsed'] for e in heap),default=None),'sampledAllocationWeightBytes':sum(e['weight'] for e in allocation) if allocation else None,'gcDurations':[e['duration'] for e in gc],'limitations':['JFR profile settings; CPU and allocation observations are sampled','allocation weight is not exact allocated bytes','all durable system runs include JFR overhead','recording maxsize128MiB may retain only later events in a long run; inspect recording duration and event counts']}
(p/'profile-summary.json').write_text(json.dumps(obj,indent=2)+'\n')
