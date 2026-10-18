from pathlib import Path
p=Path('projects/01-clearledger/scripts/benchmark.sh');s=p.read_text();s=s.replace('prepare_classes\n','prepare_classes\npython3 "$PROJECT/scripts/capture-environment.py" "$PROJECT/target/benchmark/environment.json"\n')
s=s.replace('LEDGER_URL="jdbc:postgresql://127.0.0.1:${CLEARLEDGER_DB_PORT:-18101}/$database" java -Xmx512m -cp "$CP" io.github.cheshmakzanoon.portfolio.clearledger.Workload "$count" "$writers" "$PROJECT/target/benchmark/r${round}-w${writers}"','''output="$PROJECT/target/benchmark/r${round}-w${writers}"
  mkdir -p "$output"
  LEDGER_URL="jdbc:postgresql://127.0.0.1:${CLEARLEDGER_DB_PORT:-18101}/$database" python3 "$PROJECT/scripts/run-profiled.py" "$output/process.json" java -Xmx512m "-XX:StartFlightRecording=filename=$output/workload.jfr,settings=profile,dumponexit=true,maxsize=128m" -cp "$CP" io.github.cheshmakzanoon.portfolio.clearledger.Workload "$count" "$writers" "$output" > "$output/console.log" 2>&1
  jfr summary "$output/workload.jfr" > "$output/jfr-summary.txt"
  jfr print --json --events jdk.CPULoad,jdk.GarbageCollection,jdk.GCHeapSummary,jdk.ObjectAllocationSample "$output/workload.jfr" > "$output/jfr-events.json"
  python3 "$PROJECT/scripts/summarize-jfr.py" "$output"''');p.write_text(s)
