#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
count="${1:-100000}"; rounds="${2:-3}"
[[ "$count" =~ ^[0-9]+$ && "$rounds" =~ ^[1-9][0-9]*$ ]] || { echo 'usage: benchmark.sh [journals<=100000] [rounds]' >&2; exit 2; }
"${COMPOSE[@]}" up -d --wait --wait-timeout 90
prepare_classes
export LEDGER_SOURCE_REVISION="working-tree:$(find "$PROJECT" -path '*/target' -prune -o -path '*/evidence' -prune -o -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)"
for ((round=1;round<=rounds;round++)); do
 for writers in 1 8 32; do
  database="clearledger_benchmark_r${round}_w${writers}"
  # This command deliberately replaces only its named, disposable benchmark database.
  "${COMPOSE[@]}" exec -T postgres dropdb -U postgres --if-exists "$database"
  "${COMPOSE[@]}" exec -T postgres createdb -U postgres "$database"
  LEDGER_URL="jdbc:postgresql://127.0.0.1:${CLEARLEDGER_DB_PORT:-18101}/$database" java -Xmx512m -cp "$CP" io.github.cheshmakzanoon.portfolio.clearledger.Workload "$count" "$writers" "$PROJECT/target/benchmark/r${round}-w${writers}"
 done
done
