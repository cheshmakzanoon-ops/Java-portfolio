#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
command -v docker >/dev/null || { echo 'Docker with Compose is required' >&2; exit 1; }
command -v java >/dev/null || { echo 'JDK 25 is required' >&2; exit 1; }
"$PROJECT_DIR/scripts/prepare-runtime.sh"
existing_containers="$(compose ps -a -q)"
started_services=()
started_pids=()
cleanup_failed_start() {
  local status=$? index service jar container
  trap - EXIT
  if ((status != 0)); then
    set +e
    for index in "${!started_pids[@]}"; do
      service="${started_services[$index]}"
      jar="$PROJECT_DIR/$service/target/tenantshield-$service-1.0.0-SNAPSHOT-exec.jar"
      stop_process "${started_pids[$index]}" "$jar"
      if [[ -f "$RUNTIME_DIR/$service.pid" && "$(cat "$RUNTIME_DIR/$service.pid")" == "${started_pids[$index]}" ]]; then
        rm -f "$RUNTIME_DIR/$service.pid"
      fi
    done
    while IFS= read -r container; do
      [[ -n "$container" ]] || continue
      case $'\n'"$existing_containers"$'\n' in
        *$'\n'"$container"$'\n'*) ;;
        *) docker rm -f "$container" >/dev/null || echo 'Failed to remove a container from incomplete startup' >&2 ;;
      esac
    done < <(compose ps -a -q 2>/dev/null || true)
  fi
  exit "$status"
}
trap cleanup_failed_start EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
compose up -d --wait --wait-timeout 420
export TENANTSHIELD_MIGRATION_URL=jdbc:postgresql://127.0.0.1:18503/tenantshield
export TENANTSHIELD_MIGRATION_USER=migration
export TENANTSHIELD_MIGRATION_PASSWORD=local-migration-only
bounded 300 java -Xmx256m -cp "$(classpath)" io.github.cheshmakzanoon.portfolio.tenantshield.StoreAdmin migrate
unset TENANTSHIELD_MIGRATION_USER TENANTSHIELD_MIGRATION_PASSWORD TENANTSHIELD_MIGRATION_URL
compose exec -T postgres psql -v ON_ERROR_STOP=1 -U migration -d tenantshield < "$PROJECT_DIR/demo/seed.sql"
export TENANTSHIELD_DB_URL=jdbc:postgresql://127.0.0.1:18503/tenantshield
export TENANTSHIELD_DB_PASSWORD=local-tenantshield-only
export TENANTSHIELD_OIDC_ISSUER=http://127.0.0.1:18502/realms/tenantshield
export TENANTSHIELD_OIDC_JWKS="$TENANTSHIELD_OIDC_ISSUER/protocol/openid-connect/certs"
for service in decision-service demo-resource-server; do
  jar="$PROJECT_DIR/$service/target/tenantshield-$service-1.0.0-SNAPSHOT-exec.jar"
  stop_service "$service" "$jar"
  nohup java -Xmx256m -jar "$jar" > "$RUNTIME_DIR/$service.log" 2>&1 &
  started_pid=$!
  started_services+=("$service")
  started_pids+=("$started_pid")
  echo "$started_pid" > "$RUNTIME_DIR/$service.pid"
done
wait_for_services
fingerprint > "$RUNTIME_DIR/services.fingerprint"
echo 'TenantShield ready at http://127.0.0.1:18500; protected documents :18501; local Keycloak :18502'
