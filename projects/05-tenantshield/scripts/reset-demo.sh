#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
for service in decision-service demo-resource-server; do stop_service "$service" "$PROJECT_DIR/$service/target/tenantshield-$service-1.0.0-SNAPSHOT-exec.jar"; done
compose exec -T postgres psql -v ON_ERROR_STOP=1 -U migration -d tenantshield <<'SQL'
BEGIN;
ALTER TABLE policy_version DISABLE TRIGGER immutable_version;
DELETE FROM active_policy WHERE tenant_id IN ('harbor','orchard');
DELETE FROM audit WHERE tenant_id IN ('harbor','orchard');
DELETE FROM role_edge WHERE tenant_id IN ('harbor','orchard');
DELETE FROM "grant" WHERE tenant_id IN ('harbor','orchard');
DELETE FROM role WHERE tenant_id IN ('harbor','orchard');
DELETE FROM policy_version WHERE tenant_id IN ('harbor','orchard');
DELETE FROM membership WHERE tenant_id IN ('harbor','orchard');
DELETE FROM resource WHERE tenant_id IN ('harbor','orchard');
DELETE FROM tenant WHERE tenant_id IN ('harbor','orchard');
ALTER TABLE policy_version ENABLE TRIGGER immutable_version;
COMMIT;
SQL
echo 'Only harbor/orchard seeded data removed. Run up to seed and start again.'
