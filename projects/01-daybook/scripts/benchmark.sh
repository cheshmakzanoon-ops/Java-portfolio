#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
count="${1:-100000}"; rounds="${2:-3}"
[[ "$count" =~ ^[0-9]+$ && "$rounds" =~ ^[1-9][0-9]*$ ]] || { echo 'usage: benchmark.sh [journals<=100000] [rounds]' >&2; exit 2; }
if [[ "${3:-}" == --prepared ]]; then
 python3 "$PROJECT/scripts/runtime-cache.py" check || { echo 'Prepared workload source/artifacts do not match; rebuild before reserving a quiet interval.' >&2; exit 1; }
 echo 'Using exact source/artifact-validated workload runtime without starting Maven.'
elif [[ -z "${3:-}" ]]; then
 prepare_classes
else
 echo 'usage: benchmark.sh [journals<=100000] [rounds] [--prepared]' >&2; exit 2
fi
"${COMPOSE[@]}" up -d --wait --wait-timeout 90
python3 "$PROJECT/scripts/capture-environment.py" "$PROJECT/target/benchmark/environment.json" "$0" "$@"
export LEDGER_SOURCE_REVISION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceRevision"])' "$PROJECT/target/benchmark/environment.json")"
for ((round=1;round<=rounds;round++)); do
 for writers in 1 8 32; do
  database="daybook_benchmark_r${round}_w${writers}"
  # This command deliberately replaces only its named, disposable benchmark database.
  "${COMPOSE[@]}" exec -T postgres dropdb -U postgres --if-exists "$database"
  "${COMPOSE[@]}" exec -T postgres createdb -U postgres "$database"
  output="$PROJECT/target/benchmark/r${round}-w${writers}"
  mkdir -p "$output"
  driver_status=0
  LEDGER_URL="jdbc:postgresql://127.0.0.1:${DAYBOOK_DB_PORT:-18101}/$database" python3 "$PROJECT/scripts/run-profiled.py" "$output/process.json" java -Xmx512m "-XX:StartFlightRecording=filename=$output/workload.jfr,settings=profile,dumponexit=true,maxsize=128m" -cp "$CP" io.github.cheshmakzanoon.portfolio.daybook.Workload "$count" "$writers" "$output" > "$output/console.log" 2>&1 || driver_status=$?
  jfr summary "$output/workload.jfr" > "$output/jfr-summary.txt"
  jfr print --json --events jdk.CPULoad,jdk.GarbageCollection,jdk.GCHeapSummary,jdk.ObjectAllocationSample "$output/workload.jfr" > "$output/jfr-events.json"
  python3 "$PROJECT/scripts/summarize-jfr.py" "$output"
  if ((driver_status != 0)); then
   echo "Workload failed with exit $driver_status; preserving process, client outcomes and JFR artifacts in $output" >&2
   exit "$driver_status"
  fi
  "${COMPOSE[@]}" exec -T postgres psql -At -v ON_ERROR_STOP=1 -U postgres -d "$database" -c "SELECT json_build_object('journals',(SELECT count(*) FROM ledger.journal),'receipts',(SELECT count(*) FROM ledger.idempotency_receipt),'outboxEvents',(SELECT count(*) FROM ledger.outbox_event),'pendingAfterReplay',(SELECT count(*) FROM ledger.outbox_event e WHERE NOT EXISTS(SELECT FROM ledger.projection_checkpoint p JOIN ledger.projection_head h ON h.generation=p.generation WHERE p.event_id=e.id)),'tenantsTouched',(SELECT count(DISTINCT tenant_id) FROM ledger.journal),'accountsTouched',(SELECT count(DISTINCT (tenant_id,account_id)) FROM ledger.posting));" > "$output/postconditions.json"
  python3 "$PROJECT/scripts/validate-workload.py" "$output" "$count"
 done
done
