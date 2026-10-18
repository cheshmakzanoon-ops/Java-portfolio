from pathlib import Path
from collections import Counter
import json
p=Path('/workspace/Java-portfolio/projects/06-matchcore');out=p/'evidence/reference-20261018/measurement-envelope.json';envelope=json.loads(out.read_text())
for row in envelope['samplingAndGc']:
 item=next(i for i in envelope['integrity'] if i['mode']==row['mode'] and i['repetition']==row['repetition'])
 d=Path('/workspace/Java-portfolio')/item['rawDirectory']
 events=json.loads((d/'jfr-events.json').read_text())['recording']['events']
 counts=Counter(e['type'] for e in events);assert dict(counts)==row['jfrSampleCounts']
 nearest=Counter();with_own=0;leaf=Counter();alloc=Counter()
 for event in events:
  values=event['values']
  if event['type']=='jdk.ExecutionSample':
   frames=(values.get('stackTrace') or {}).get('frames') or []
   for n,frame in enumerate(frames):
    method=frame.get('method') or {};owner=(method.get('type') or {}).get('name','?');name=owner+'.'+method.get('name','?')
    if n==0:leaf[name]+=1
    if owner.startswith('io/github/cheshmakzanoon/portfolio/matchcore/'):
     nearest[name]+=1;with_own+=1;break
  elif event['type']=='jdk.ObjectAllocationSample':alloc[(values.get('objectClass') or {}).get('name','?')]+=values.get('weight',0)
 assert leaf.most_common(15)==[tuple(i) for i in row['sampledCpuTopFrames']]
 assert alloc.most_common(15)==[tuple(i) for i in row['allocationSampleWeightsByClass']]
 row['nearestApplicationFrameSampleCounts']=nearest.most_common(12)
 row['executionSamplesWithApplicationFrame']=with_own
 row['samplingIndependentlyRecomputedFromRawEvents']=True
 print(row['mode'],row['repetition'],nearest.most_common(7),flush=True)
 del events
 # Collapse heap-size details from GC remark/cleanup category names.
 kinds=Counter()
 for name,count in row['gcLog']['pauseKinds'].items():
  if name.startswith('Pause Remark'):name='Pause Remark'
  if name.startswith('Pause Cleanup'):name='Pause Cleanup'
  kinds[name]+=count
 row['gcLog']['pauseKinds']=dict(kinds)
out.write_text(json.dumps(envelope,indent=2)+'\n')
