#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_build
health
export SWITCHBACK_BENCHMARK_URL="http://127.0.0.1:${SWITCHBACK_PORT:-19000}"
output=${1:-$runtime/benchmark/run-$(date -u +%Y%m%dT%H%M%SZ)}
if [[ -e "$output/corpus-raw.json" || -e "$output/validation-jmh.json" ]]; then
  echo "Refusing to overwrite prior benchmark artifacts: choose a fresh output directory" >&2
  exit 1
fi
mkdir -p "$output"
{ "$JAVA_HOME/bin/java" -version; uname -sm; rg -m1 "model name" /proc/cpuinfo; cat /etc/os-release; getconf _NPROCESSORS_ONLN; cat /sys/fs/cgroup/cpu.max; cat /sys/fs/cgroup/memory.max; printf "JVM flags: client-Xms512m-Xmx1536m; server-Xmx768m; system warmup: none excluded, report run0 separately; measurements:3 per instance; concurrency:1; data seeds:4200..4209; solver seed:42; budget:30000ms; step cap:100000; JMH:3 forks/3x1s warmup/3x1s measurement\n"; } > "$output/environment.txt" 2>&1
"$JAVA_HOME/bin/java" -cp "$project/switchback-verification/target/switchback-verification-1.0.0-SNAPSHOT-driver.jar" org.openjdk.jmh.Main '.*ValidationBenchmark.*' -rf json -rff "$output/validation-jmh.json" -prof gc > "$output/validation-jmh.log" 2>&1
pid=$(cat "$runtime/service.pid")
python3 - "$pid" "$output/server-resources.jsonl" <<'PYMON' &
import json,os,sys,time
from pathlib import Path
pid=sys.argv[1]; out=Path(sys.argv[2]); ticks=os.sysconf('SC_CLK_TCK')
with out.open('w') as stream:
    while True:
        try:
            raw=Path('/proc/'+pid+'/stat').read_text(); stat=raw[raw.rfind(')')+2:].split()
            status={line.split(':',1)[0]:line.split(':',1)[1].strip() for line in Path('/proc/'+pid+'/status').read_text().splitlines() if ':' in line}
            record={'monotonicNanos':time.monotonic_ns(),'userCpuSeconds':int(stat[11])/ticks,'systemCpuSeconds':int(stat[12])/ticks,'rssBytes':int(status['VmRSS'].split()[0])*1024,'highWaterRssBytes':int(status['VmHWM'].split()[0])*1024,'threads':int(status['Threads'])}
            stream.write(json.dumps(record)+'\n');stream.flush();time.sleep(1)
        except FileNotFoundError:break
PYMON
monitor_pid=$!
trap 'kill "$monitor_pid" 2>/dev/null || true; wait "$monitor_pid" 2>/dev/null || true' EXIT
timeout 1800 "$JAVA_HOME/bin/java" -Xms512m -Xmx1536m -Xlog:gc:file="$output/gc.log" -jar "$project/switchback-verification/target/switchback-verification-1.0.0-SNAPSHOT-driver.jar" corpus "$output"

if [[ -f "$runtime/service.pid" ]]; then
  pid=$(cat "$runtime/service.pid")
  if [[ -r "/proc/$pid/status" ]]; then
    cp "/proc/$pid/status" "$output/server-process-status.txt"
    cp "$runtime/service-gc.log" "$output/server-gc.log"
  fi
fi
