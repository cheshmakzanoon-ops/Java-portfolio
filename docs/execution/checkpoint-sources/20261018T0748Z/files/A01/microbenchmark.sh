#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
mode="${1:-full}"
[[ "$mode" == full || "$mode" == smoke ]] || { echo 'usage: microbenchmark.sh [full|smoke]' >&2; exit 2; }
"${MVN[@]}" -f "$PROJECT/pom.xml" -Pbenchmarks -pl ledger-benchmarks -am install
"${MVN[@]}" -f "$PROJECT/pom.xml" -N org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file -Dfile="$ROOT/pom.xml" -DpomFile="$ROOT/pom.xml" -DgeneratePom=false
"${MVN[@]}" -f "$PROJECT/ledger-benchmarks/pom.xml" -Pbenchmarks dependency:copy-dependencies -DincludeScope=runtime
output="$PROJECT/target/microbenchmark/$mode"
mkdir -p "$output"
python3 "$PROJECT/scripts/capture-environment.py" "$output/environment.json"
args=(-wi 3 -i 5 -w 1s -r 1s -f 3)
if [[ "$mode" == smoke ]]; then args=(-wi 1 -i 1 -w 100ms -r 100ms -f 1); fi
java -Xmx256m -cp "$PROJECT/ledger-benchmarks/target/classes:$PROJECT/ledger-benchmarks/target/dependency/*" org.openjdk.jmh.Main '.*AccountingBenchmark.*' "${args[@]}" -prof gc -rf json -rff "$output/results.json" | tee "$output/console.log"
