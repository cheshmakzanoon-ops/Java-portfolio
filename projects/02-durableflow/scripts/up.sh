#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
for command in java docker curl; do command -v "$command" >/dev/null || { echo "Required: $command" >&2; exit 1; }; done
docker compose -f "$PROJECT/infra/compose.yaml" up -d --wait --wait-timeout 90
api_running=false
if [[ -f "$PROJECT/target/runtime/api.pid" ]]; then
  api_pid=$(cat "$PROJECT/target/runtime/api.pid")
  if [[ "$api_pid" =~ ^[0-9]+$ ]] && kill -0 "$api_pid" 2>/dev/null; then
    api_command=$(ps -p "$api_pid" -o args= || true)
    if [[ "$api_command" == *"$PROJECT/workflow-api/target/durableflow-workflow-api-1.0.0-SNAPSHOT-exec.jar"* ]]; then api_running=true; fi
  fi
fi
if [[ "$api_running" == false ]]; then
  build
  nohup java -Xmx256m "-Xlog:gc*:file=$PROJECT/target/runtime/api-gc.log:time,uptime,level,tags" -jar "$PROJECT/workflow-api/target/durableflow-workflow-api-1.0.0-SNAPSHOT-exec.jar" --spring.profiles.active=dev > "$PROJECT/target/runtime/api.log" 2>&1 & echo $! > "$PROJECT/target/runtime/api.pid"
fi
launch provider io.github.cheshmakzanoon.portfolio.durableflow.RuntimeMain provider
for n in 1 2; do launch "scheduler-$n" io.github.cheshmakzanoon.portfolio.durableflow.RuntimeMain scheduler; done
for n in 1 2 3; do launch "worker-$n" io.github.cheshmakzanoon.portfolio.durableflow.RuntimeMain worker "worker-$n"; done
for n in $(seq 1 120); do if curl -fsS "http://127.0.0.1:${PORT:-18200}/health" >/dev/null && curl -fsS "http://127.0.0.1:${PROVIDER_PORT:-18201}/health" >/dev/null; then echo "DurableFlow ready on http://127.0.0.1:${PORT:-18200}"; exit 0; fi; sleep 1; done
echo "Startup timed out; inspect $PROJECT/target/runtime/*.log" >&2
exit 1
