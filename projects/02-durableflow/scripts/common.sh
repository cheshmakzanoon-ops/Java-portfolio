#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ROOT=$(cd -- "$PROJECT/../.." && pwd)
if [[ -f "$ROOT/scripts/cloud-env.sh" && -d /workspace/.tools/jdk-25 ]]; then source "$ROOT/scripts/cloud-env.sh"; fi
export DB_USER=${DB_USER:-durableflow}
export DB_PASSWORD=${DB_PASSWORD:-disposable-local-only}
export DB_URL=${DB_URL:-jdbc:postgresql://127.0.0.1:${DURABLEFLOW_DB_PORT:-18202}/durableflow}
export PROVIDER_URL=${PROVIDER_URL:-http://127.0.0.1:${PROVIDER_PORT:-18201}/effects}
export EFFECT_LOG=${EFFECT_LOG:-$PROJECT/target/runtime/provider-effects.log}
export TENANT=${TENANT:-demo}
mkdir -p "$PROJECT/target/runtime"
JAR="$PROJECT/workflow-verification/target/durableflow-workflow-verification-1.0.0-SNAPSHOT-exec.jar"
build() { (cd "$ROOT" && ./mvnw -f "$PROJECT/pom.xml" -Dmaven.repo.local="$ROOT/.work/A02/m2" -B -ntp verify); }
launch() {
  local name=$1 main=$2; shift 2
  local pid_file="$PROJECT/target/runtime/$name.pid"
  if [[ -f "$pid_file" ]]; then
    local existing; existing=$(cat "$pid_file")
    if [[ "$existing" =~ ^[0-9]+$ ]] && kill -0 "$existing" 2>/dev/null; then
      local command_line; command_line=$(ps -p "$existing" -o args= || true)
      if [[ "$command_line" == *"$JAR"* && "$command_line" == *"$main"* && "$command_line" == *"-Ddurableflow.process.name=$name "* ]]; then return 0; fi
    fi
  fi
  nohup java -Xmx128m "-Ddurableflow.process.name=$name" "-Xlog:gc*:file=$PROJECT/target/runtime/$name-gc.log:time,uptime,level,tags" -Dloader.main="$main" -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@" > "$PROJECT/target/runtime/$name.log" 2>&1 & echo $! > "$pid_file"
}
run_main() { (cd "$PROJECT" && java -Xmx512m "-Xlog:gc*:file=$PROJECT/target/runtime/$1-gc.log:time,uptime,level,tags" -Dloader.main="io.github.cheshmakzanoon.portfolio.durableflow.$1" -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "${@:2}"); }
