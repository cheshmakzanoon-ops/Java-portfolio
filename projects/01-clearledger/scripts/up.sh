#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
"${COMPOSE[@]}" up -d --wait --wait-timeout 90
if curl -fsS --max-time 2 http://127.0.0.1:18100/health >/dev/null; then seed_demo; exit 0; fi
prepare_classes
LEDGER_MIGRATE=true nohup java -Xmx256m -jar "$PROJECT/ledger-api/target/clearledger-api-1.0.0-SNAPSHOT-exec.jar" --spring.profiles.active=dev > "$PROJECT/target/api.log" 2>&1 &
echo "$!" > "$PROJECT/target/api.pid"
for ((i=0;i<90;i++)); do
 if curl -fsS --max-time 1 http://127.0.0.1:18100/health >/dev/null 2>&1; then seed_demo; echo 'ClearLedger ready: http://127.0.0.1:18100 (disposable dev JWT profile)'; exit 0; fi
 sleep 1
done
cat "$PROJECT/target/api.log" >&2
exit 1
