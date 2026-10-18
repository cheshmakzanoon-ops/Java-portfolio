#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
if curl -fsS --max-time 2 http://127.0.0.1:18100/health >/dev/null 2>&1; then
 owned_api_running || { echo 'Port18100 belongs to an untracked API; refusing to reuse it.' >&2; exit 1; }
 prepare_classes
 "${COMPOSE[@]}" up -d --wait --wait-timeout 90
 seed_demo
 echo 'ClearLedger current prepared API reused.'
 exit 0
fi
if owned_api_running; then echo 'Tracked API is alive but unhealthy; run down before starting again.' >&2; exit 1; fi
prepare_classes
"${COMPOSE[@]}" up -d --wait --wait-timeout 90
LEDGER_MIGRATE=true nohup java -Xmx256m -jar "$PROJECT/ledger-api/target/clearledger-api-1.0.0-SNAPSHOT-exec.jar" --spring.profiles.active=dev > "$PROJECT/target/api.log" 2>&1 &
echo "$!" > "$PROJECT/target/api.pid"
for ((i=0;i<90;i++)); do
 if curl -fsS --max-time 1 http://127.0.0.1:18100/health >/dev/null 2>&1; then seed_demo; echo 'ClearLedger ready: http://127.0.0.1:18100 (disposable dev JWT profile)'; exit 0; fi
 sleep 1
done
cat "$PROJECT/target/api.log" >&2
exit 1
