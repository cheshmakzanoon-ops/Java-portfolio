#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
mode="${1:-full}"
[[ "$mode" == full || "$mode" == smoke ]] || { echo 'usage: microbenchmark.sh [full|smoke]' >&2; exit 2; }
if [[ "${2:-}" == --prepared ]]; then
 python3 "$PROJECT/scripts/jmh-cache.py" check || { echo 'Prepared JMH source/artifacts do not match; build before reserving a quiet interval.' >&2; exit 1; }
 echo 'Using exact source/artifact-validated JMH runtime without starting Maven.'
elif [[ -z "${2:-}" ]]; then
 before="$(python3 "$PROJECT/scripts/jmh-cache.py" snapshot)"
"${MVN[@]}" -f "$PROJECT/pom.xml" -Pbenchmarks -pl ledger-benchmarks -am install
"${MVN[@]}" -f "$PROJECT/pom.xml" -N org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file -Dfile="$ROOT/pom.xml" -DpomFile="$ROOT/pom.xml" -DgeneratePom=false
rm -rf -- "$PROJECT/ledger-benchmarks/target/dependency"
"${MVN[@]}" -f "$PROJECT/ledger-benchmarks/pom.xml" -Pbenchmarks dependency:copy-dependencies -DincludeScope=runtime
 python3 "$PROJECT/scripts/jmh-cache.py" write "$before"
else
 echo 'usage: microbenchmark.sh [full|smoke] [--prepared]' >&2; exit 2
fi
output="$PROJECT/target/microbenchmark/$mode"
mkdir -p "$output"
python3 "$PROJECT/scripts/capture-environment.py" "$output/environment.json" "$0" "$@"
args=(-wi 3 -i 5 -w 1s -r 1s -f 3)
if [[ "$mode" == smoke ]]; then args=(-wi 1 -i 1 -w 100ms -r 100ms -f 1); fi
java -Xmx256m -cp "$PROJECT/ledger-benchmarks/target/classes:$PROJECT/ledger-benchmarks/target/dependency/*" org.openjdk.jmh.Main '.*AccountingBenchmark.*' "${args[@]}" -prof gc -rf json -rff "$output/results.json" | tee "$output/console.log"
