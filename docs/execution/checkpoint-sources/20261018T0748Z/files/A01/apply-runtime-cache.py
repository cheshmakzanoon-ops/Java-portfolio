from pathlib import Path
p=Path('projects/01-clearledger');a=p/'scripts/runtime-cache.py';a.write_text(Path('.work/A01/runtime-cache.py').read_text().replace(',subprocess,',','));a.chmod(0o755)
a=p/'scripts/common.sh';s=a.read_text().replace('prepare_classes() {','''prepare_classes() {
 if python3 "$PROJECT/scripts/runtime-cache.py" check; then
  echo 'Reusing prepared runtime artifacts (source and artifact hashes match).'
  return
 fi''').replace(' "${MVN[@]}" -f "$PROJECT/ledger-verification/pom.xml" dependency:copy-dependencies -DincludeScope=runtime',''' rm -rf -- "$PROJECT/ledger-verification/target/dependency"
 "${MVN[@]}" -f "$PROJECT/ledger-verification/pom.xml" dependency:copy-dependencies -DincludeScope=runtime
 python3 "$PROJECT/scripts/runtime-cache.py" write''');a.write_text(s)
a=p/'scripts/benchmark.sh';s=a.read_text().replace('"${COMPOSE[@]}" up -d --wait --wait-timeout 90\nprepare_classes','prepare_classes\n"${COMPOSE[@]}" up -d --wait --wait-timeout 90');a.write_text(s)
a=p/'scripts/microbenchmark.sh';s=a.read_text().replace('"${MVN[@]}" -f "$PROJECT/ledger-benchmarks/pom.xml" -Pbenchmarks dependency:copy-dependencies','rm -rf -- "$PROJECT/ledger-benchmarks/target/dependency"\n"${MVN[@]}" -f "$PROJECT/ledger-benchmarks/pom.xml" -Pbenchmarks dependency:copy-dependencies');a.write_text(s)
