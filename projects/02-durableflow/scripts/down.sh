#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
shopt -s nullglob
owned=()
for file in "$PROJECT"/target/runtime/*.pid; do
  pid=$(cat "$file")
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    command_line=$(ps -p "$pid" -o args= || true)
    if [[ "$command_line" == *"$JAR"* || "$command_line" == *"$PROJECT/workflow-api/target/durableflow-workflow-api-1.0.0-SNAPSHOT-exec.jar"* ]]; then
      kill "$pid"
      owned+=("$pid")
    fi
  fi
  rm -f "$file"
done
for pid in "${owned[@]}"; do
  for attempt in $(seq 1 50); do
    if ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 0.1
  done
  if kill -0 "$pid" 2>/dev/null; then
    command_line=$(ps -p "$pid" -o args= || true)
    if [[ "$command_line" == *"$JAR"* || "$command_line" == *"$PROJECT/workflow-api/target/durableflow-workflow-api-1.0.0-SNAPSHOT-exec.jar"* ]]; then kill -KILL "$pid"; fi
  fi
done
docker compose -f "$PROJECT/infra/compose.yaml" stop
