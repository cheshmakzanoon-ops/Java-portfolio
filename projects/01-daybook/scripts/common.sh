#!/usr/bin/env bash
set -euo pipefail
umask 077
PROJECT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd -- "$PROJECT/../.." && pwd)"
if [[ -d /workspace/.tools/jdk-25 ]]; then source "$ROOT/scripts/cloud-env.sh"; fi
CACHE="$ROOT/.work/A01/m2"
MVN=("$ROOT/mvnw" -Dmaven.repo.local="$CACHE" -B -ntp)
COMPOSE=(docker compose -f "$PROJECT/infra/compose.yaml")
for tool in java docker python3 curl flock; do command -v "$tool" >/dev/null || { echo "Required tool missing: $tool" >&2; exit 1; }; done
mkdir -p "$PROJECT/target"
export LEDGER_URL="${LEDGER_URL:-jdbc:postgresql://127.0.0.1:${DAYBOOK_DB_PORT:-18101}/daybook}"
CP="$PROJECT/ledger-verification/target/classes:$PROJECT/ledger-domain/target/classes:$PROJECT/ledger-persistence/target/classes:$PROJECT/ledger-verification/target/dependency/*"
PROCESS_HELPER="$PROJECT/scripts/runtime-process.py"
API_JAR="$PROJECT/ledger-api/target/daybook-api-1.0.0-SNAPSHOT-exec.jar"
API_RECORD="$PROJECT/target/api.pid"
owned_api_running() {
 python3 "$PROCESS_HELPER" alive "$API_RECORD" "$API_JAR"
}
current_api_running() {
 python3 "$PROCESS_HELPER" current "$API_RECORD" "$API_JAR"
}
stop_owned_api() {
 python3 "$PROCESS_HELPER" stop "$API_RECORD" "$API_JAR"
}
prepare_classes() {
 if python3 "$PROJECT/scripts/runtime-cache.py" check; then
  echo 'Reusing prepared runtime artifacts (source and artifact hashes match).'
  return
 fi
 python3 "$PROJECT/scripts/runtime-cache.py" idle
 python3 "$PROJECT/scripts/test-runtime-cache.py"
 local before; before="$(python3 "$PROJECT/scripts/runtime-cache.py" snapshot)"
 "${MVN[@]}" -f "$PROJECT/pom.xml" install
 "${MVN[@]}" -f "$PROJECT/pom.xml" -N org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file -Dfile="$ROOT/pom.xml" -DpomFile="$ROOT/pom.xml" -DgeneratePom=false
 rm -rf -- "$PROJECT/ledger-verification/target/dependency"
 "${MVN[@]}" -f "$PROJECT/ledger-verification/pom.xml" dependency:copy-dependencies -DincludeScope=runtime
 python3 "$PROJECT/scripts/runtime-cache.py" write "$before"
}
seed_demo() {
 "${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d daybook <<'SQL'
INSERT INTO ledger.tenant VALUES('00000000-0000-0000-0000-000000000101') ON CONFLICT DO NOTHING;
INSERT INTO ledger.accounting_period VALUES('00000000-0000-0000-0000-000000000101','00000000-0000-0000-0000-000000000102','2026-01-01','2026-12-31',false) ON CONFLICT DO NOTHING;
INSERT INTO ledger.account VALUES('00000000-0000-0000-0000-000000000101','00000000-0000-0000-0000-000000000103','CAD','DEBIT','Synthetic cash'),('00000000-0000-0000-0000-000000000101','00000000-0000-0000-0000-000000000104','CAD','CREDIT','Synthetic revenue') ON CONFLICT DO NOTHING;
SQL
}
