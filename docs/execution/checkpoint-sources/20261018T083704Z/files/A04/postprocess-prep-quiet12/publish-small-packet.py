from pathlib import Path
import json,hashlib,shutil,datetime
root=Path('/workspace/Java-portfolio');work=root/'.work/A04';scratch=work/'postprocess-prep-quiet12';e=root/'projects/04-pulsestream/evidence/reference-20261018-quiet12';e.mkdir(exist_ok=False)
summary_path=work/'quiet-runs/20261018T070840Z-405639/summary.json';s=json.loads(summary_path.read_text());a=json.loads((scratch/'assembled.json').read_text());raw=json.loads((scratch/'raw-verification.json').read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
shutil.copy2(scratch/'assembled.json',e/'analysis.json');shutil.copy2(scratch/'raw-verification.json',e/'raw-index.json')
for phase in s['phases']:
 if not phase.get('artifactDirectories'):continue
 d=Path(phase['artifactDirectories'][0]);name=phase['name']
 for filename in ['result.json','environment.json','jmh.json','baseline-engine-manifest.json']:
  p=d/filename
  if p.exists():shutil.copy2(p,e/(name+'-'+filename))
old=a['engines']['engine-original']['originalJmhResult'];current=a['engines']['engine-current']['originalJmhResult']
envelope={'schemaVersion':1,'project':'pulsestream','classification':'completed-prescribed-study-pending-final-review','sourceRevision':s['sourceRevision'],
'quietReservation':s['reservation'],'started':s['started'],'ended':s['ended'],'terminalStatus':s['status'],'exitCode':s['exitCode'],'teardown':s['teardown'],
'originalSummary':str(summary_path.relative_to(root)),'originalSummarySha256':sha(summary_path),'runnerSha256':'3be9d084510d2156cb8d2c8d1cba1fda0628b807b6d529604e2a5a1da3a3baa8','readyReceiptSha256':'a6f41eecfdb8d1e96764a7d0c152b29ace9874b06953e5202e524e46c6fee0b9',
'originalEngineManifestSha256':'0815eee01b6a35130d3bac586e071ca9cf27ed79ce59e35f5f205e8253baffbb','completedMeasurementDirectories':[str(Path(p).relative_to(root)) for p in s['completedMeasurementDirectories']],
'rawFilesVerified':raw['rawFilesVerified'],'rawBytesVerified':raw['rawBytesVerified'],'rawIndex':'raw-index.json','analysis':'analysis.json',
'fullRounds':3,'acceptedMeasurementsPerRound':1000000,'duplicateCountPerRound':1000,'assertionsPassedPerRound':6,'assertionsFailed':0,
'ratesEventsPerSecond':[v['result']['measurements']['acceptedEventsPerSecond'] for v in a['durableRounds']],
'acrossRoundThroughput':a['throughputAcrossRoundStatistics'],'aggregateAcceptedEventsPerSecond':a['aggregateAcceptedEventsPerSecond'],
'engineCurrentToOriginalMeanThroughputRatio':current['primaryMetric']['score']/old['primaryMetric']['score'],
'engineNormalizedAllocationReductionPercent':100*(1-current['secondaryMetrics']['gc.alloc.rate.norm']['score']/old['secondaryMetrics']['gc.alloc.rate.norm']['score']),
'jfrStatus':{'durableFullRecordingsRetained':3,'engineRecordingsRetained':2,'engineForksPerVariant':3,'allEngineForkNumericSamplesRetained':True,'separateEngineForkRecordingsRetained':False,'semanticParse':'PENDING_ROOT_JAVA_GRANT','boundary':'JMH reports the same profiler pathname for all3forks within each variant. Exactly1recording per variant remains; do not claim preserved per-fork recordings.'},
'postprocessingActivity':raw['postprocessingActivity'],'scope':'Quiet10 failure remains separate and untouched. No engine rows borrowed. No stable capacity, dominant bottleneck, end-to-end optimization speedup, or final release approval claimed.',
'smallCopiedInputs':{p.name:sha(p) for p in sorted(e.glob('*.json'))}}
(e/'envelope.json').write_text(json.dumps(envelope,indent=2)+'\n')
(e/'README.md').write_text('''# Completed quiet12 PulseStream study

The coordinator completed the entire original/current3fork JMH plus three prescribed1m-event durable rounds in one quiet reservation,07:08:40–08:09:12UTC on2026-10-18. All six workload assertions passed in every round; runner exit0 and scoped teardown succeeded. This records measured execution, not final release approval or stable capacity.

- [Envelope](envelope.json): source/runner/receipt/baseline identity, terminal outcome, rates, sample variability and explicit boundaries.
- [Analysis](analysis.json): exact three-round metrics and three-fork raw JMH samples/fork-mean statistics.
- [Raw index](raw-index.json):25verified original files/70,859,616bytes, exact local locators/SHA256/sizes plus bounded GC-text summaries. Large GC/JFR/console artifacts remain under the indexed ignored `.work/A04/runtime` paths; they are not embedded in Git. Copied small input files retain their original JSON content.

The arithmetic mean rate is876.493events/s with sample CV17.739%; aggregate3m/summed drain is859.785events/s. Observed current/original fresh-engine mean ratio6.646 is a microbenchmark comparison with high between-fork CV. It does not establish durable causal benefit. Each durable run retains a full workload JFR. JMH numeric data retain all3forks, but the profiler reused a pathname and only one JFR remains per variant. Semantic JFR inspection awaits root's Java grant; no complete per-fork recording coverage is claimed. Quiet10 remains a failed earlier attempt and contributes no rows here.

See [PERFORMANCE](../../docs/PERFORMANCE.md) for per-round tables and measured exclusions. Postprocessing performed only light JSON/SHA/text work after measurements while another functional/stress window ran; no Java/JFR parser/Maven/Docker/new workload was launched.
''')
print('public packet',e,'files',len(list(e.iterdir())),'bytes',sum(p.stat().st_size for p in e.iterdir()))
