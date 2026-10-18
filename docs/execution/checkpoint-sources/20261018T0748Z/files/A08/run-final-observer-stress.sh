#!/usr/bin/env bash
set -euo pipefail
cd /workspace/Java-portfolio
source scripts/cloud-env.sh
source projects/08-threadlens/scripts/common.sh
out=/workspace/Java-portfolio/.work/A08/final-observer-stress-6
[[ ! -e "$out" ]] || { echo 'Refusing to overwrite stress evidence' >&2; exit 2; }
mkdir -p "$out"
threadlens_classpath="$threadlens_project/cli/target/classes:$(cat "$threadlens_classpath_file")"
cp "$threadlens_project/agent/target/threadlens-agent-1.0.0-SNAPSHOT.jar" "$out/agent.jar"
cat > "$out/agent.yaml" <<EOF
allowPackages: [io.github.cheshmakzanoon.portfolio.threadlens.workloads]
methodNames: [plantedMonitorContention]
maxMethods: 1
queueCapacity: 65536
outputDirectory: $out/agent-output
EOF
export THREADLENS_STRESS_OUT="$out"
export THREADLENS_STRESS_FINGERPRINT="$(threadlens_fingerprint)"
python3 - <<'PY'
import hashlib,json,os,platform,subprocess,tarfile
from datetime import datetime,timezone
from pathlib import Path
r=Path.cwd();p=r/'projects/08-threadlens';out=Path(os.environ['THREADLENS_STRESS_OUT'])
files=[f for f in p.rglob('*') if f.is_file() and ('src' in f.parts or f.name=='pom.xml' or 'scripts' in f.parts)]
files += [r/'pom.xml',r/'mvnw',*[f for f in (r/'.mvn').rglob('*') if f.is_file()]]
with tarfile.open(out/'source.tar.gz','w:gz') as t:
 for f in sorted(set(files)):t.add(f,arcname=f.relative_to(r))
meta={'sourceRevision':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'buildInputFingerprint':os.environ['THREADLENS_STRESS_FINGERPRINT'],'startedUtc':datetime.now(timezone.utc).isoformat(),'agentSha256':hashlib.sha256((out/'agent.jar').read_bytes()).hexdigest(),'sourceArchiveSha256':hashlib.sha256((out/'source.tar.gz').read_bytes()).hexdigest(),'platform':platform.platform(),'contention':'shared build host; functional boundedness only, not overhead evidence','durationSeconds':1200,'jvmFlags':['-Xms256m','-Xmx256m','-XX:ActiveProcessorCount=2'],'selectedAgentMethods':['plantedMonitorContention']}
(out/'source-identity.json').write_text(json.dumps(meta,indent=2)+'\n')
PY
java -Xms256m -Xmx256m -XX:ActiveProcessorCount=2 "-javaagent:$out/agent.jar=config=$out/agent.yaml" -cp "$threadlens_classpath" io.github.cheshmakzanoon.portfolio.threadlens.cli.ObserverStress 1200 "$out/observer" > "$out/process.log" 2>&1
java -Xmx512m -XX:ActiveProcessorCount=2 -cp "$threadlens_classpath" io.github.cheshmakzanoon.portfolio.threadlens.cli.ThreadLensCli analyze "$out/observer/observer.jfr" "$out/replayed-report" --timings "$out/agent-output" > "$out/replay.log" 2>&1
python3 - <<'PY'
import json,os
from datetime import datetime,timezone
from pathlib import Path
out=Path(os.environ['THREADLENS_STRESS_OUT']);s=json.loads((out/'observer/stress-summary.json').read_text());status=dict(line.split('=',1) for line in (out/'agent-output/observer-status.txt').read_text().splitlines() if '=' in line)
assert s['passed'] and s['elapsedSeconds']>=1200
assert status['collectionHealthy']=='true' and status['incomplete']=='false'
assert status['probeFailures']=='0' and int(status['observedTimingEvents'])>0
assert len((out/'agent-output/methods.tsv').read_text().splitlines())>1
assert (out/'agent-output/timing-summary.tsv').stat().st_size>0
report=json.loads((out/'replayed-report/report.json').read_text())
assert 'fixture-secret-never-export' not in json.dumps(report)
result={'completedUtc':datetime.now(timezone.utc).isoformat(),'stress':s,'agentStatus':status,'replayFixtureSecretAbsent':True,'scope':'final live observer functional stress; shared host, not overhead evidence'}
(out/'completed.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
PY
