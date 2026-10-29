#!/usr/bin/env bash
# Design-time definition tools: lint, graph and simulate. No database, service or Docker needed.
# Usage: scripts/definition.sh lint|graph|simulate FILE [...]   (see docs/OPERATIONS.md)
set -euo pipefail
PROJECT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
ROOT=$(cd -- "$PROJECT/../.." && pwd)
if [[ -f "$ROOT/scripts/cloud-env.sh" && -d /workspace/.tools/jdk-25 ]]; then source "$ROOT/scripts/cloud-env.sh"; fi
JAR="$PROJECT/workflow-verification/target/handoff-workflow-verification-1.0.0-SNAPSHOT-exec.jar"
if [[ ! -f $JAR ]]; then
  echo "Build the project first: ./mvnw -f projects/02-handoff/pom.xml -B -ntp verify" >&2
  exit 2
fi
exec java -Xmx256m \
  -Dloader.main=io.github.cheshmakzanoon.portfolio.handoff.DefinitionTool \
  -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
