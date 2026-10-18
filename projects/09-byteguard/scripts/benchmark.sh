#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
byteguard_build
mkdir -p "$BYTEGUARD_RUNTIME/benchmark"
java -Xmx512m -cp "$BYTEGUARD_PROJECT/evaluation/target/classes:$BYTEGUARD_JAR" io.github.cheshmakzanoon.portfolio.byteguard.evaluation.BenchmarkCorpus "$BYTEGUARD_RUNTIME/benchmark/classes-10000.jar" 10000
PYTHONDONTWRITEBYTECODE=1 BYTEGUARD_SOURCE_FINGERPRINT=$(byteguard_fingerprint) python3 - "$BYTEGUARD_PROJECT" "$BYTEGUARD_JAR" <<'PY'
import hashlib,json,os,platform,subprocess,sys,time
from pathlib import Path
project,jar=map(Path,sys.argv[1:]);work=project/'.runtime/benchmark';corpus=work/'classes-10000.jar';runs=[]
sys.path.insert(0,str(project/'scripts'))
from measure_process import measure
for index in range(3):
 report=work/f'run-{index}.sarif';stats=work/f'run-{index}.time.json'
 cmd=['java','-Xmx2g','-jar',str(jar),'scan','--input',str(corpus),'--deadline-ms','120000','--format','sarif','--out',str(report)]
 with (work/f'run-{index}.terminal.log').open('wb') as log:metric=measure(cmd,log,140)
 stats.write_text(json.dumps(metric,indent=2)+'\n')
 if metric['cancelled']:raise SystemExit(128+metric['cancellationSignal'])
 if metric['timedOut']:raise RuntimeError(f"scanner exceeded140s timeout; own process group killed and child reaped: {metric}")
 assert metric['exitCode']==1,metric
 root=json.loads(report.read_text());actual=root['runs'][0];properties=actual['properties'];metric.update({'partial':properties['partial'],'applicationClasses':properties['applicationClasses'],'findings':len(actual['results']),'workUnits':properties['workUnits']});runs.append(metric)
 stats.write_text(json.dumps(metric,indent=2)+'\n')
 assert not properties['partial'],metric
 assert metric['applicationClasses']==10000,metric
 assert metric['findings']==10000,metric
 assert metric['elapsedSeconds']<=120,metric
print(json.dumps(runs,indent=2))
env={'os':platform.platform(),'architecture':platform.machine(),'cpuCount':os.cpu_count(),'cpuQuota':Path('/sys/fs/cgroup/cpu.max').read_text().strip(),'memoryMax':Path('/sys/fs/cgroup/memory.max').read_text().strip(),'jdk':subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True).strip(),'jvmFlags':['-Xmx2g'],'warmup':'none; three independent JVM forks; input generation excluded','sharedLoad':'requires coordinator-approved exclusive performance window','boundary':'explicit local JAR read, full structural/taint scan and SARIF serialization','measurement':'Python os.wait4 per scanner child; monotonic elapsed; Linux maxRSS KiB; no cumulative RUSAGE_CHILDREN','dependencyVersions':{'ASM':'9.9.1','Jackson':'2.21.5','picocli':'4.7.7'},'cpuModel':next(line.split(':',1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name'))}
evidence={'schemaVersion':1,'project':'byteguard','sourceRevision':'working-tree-sha256:'+os.environ['BYTEGUARD_SOURCE_FINGERPRINT'],'workload':'deterministic-10000-classes-v1','seed':0,'timestampUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'environment':env,'command':'projects/09-byteguard/scripts/benchmark.sh','exitCode':0,'assertions':{'passed':15,'failed':0},'measurements':{'runs':runs,'minSeconds':min(r['elapsedSeconds'] for r in runs),'maxSeconds':max(r['elapsedSeconds'] for r in runs)},'corpusSha256':hashlib.sha256(corpus.read_bytes()).hexdigest(),'corpusBytes':corpus.stat().st_size,'rawArtifact':'.runtime/benchmark/run-{0,1,2}.{sarif,time.json,terminal.log}','limitations':['Synthetic supported-bytecode corpus; not field accuracy or worst-case complexity guarantee.','No claims about unsupported Java features or implicit control-flow taint.']}
(project/'evidence/benchmark.json').write_text(json.dumps(evidence,indent=2)+'\n')
PY
