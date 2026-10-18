#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
cd "$PULSE_ROOT"
./mvnw -f "$PULSE_PROJECT/pom.xml" -Dmaven.repo.local="$PULSE_WORK/m2" -B -ntp -Pbenchmarks verify org.apache.maven.plugins:maven-dependency-plugin:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt
PULSE_CLASSPATH=$(cat "$PULSE_PROJECT/verification/target/runtime-classpath.txt")
java -Xmx512m -cp "$PULSE_PROJECT/verification/target/classes:$PULSE_CLASSPATH" org.openjdk.jmh.Main '.*EngineBenchmark.*' -f 3 -wi 2 -i 3 -w 1s -r 1s -rf json -rff "$PULSE_WORK/runtime/engine-jmh.json"
