#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/common.sh"
for service in decision-service demo-resource-server; do stop_service "$service" "$PROJECT_DIR/$service/target/tenantshield-$service-1.0.0-SNAPSHOT-exec.jar"; done
compose exec -T postgres psql -v ON_ERROR_STOP=1 -U migration -d tenantshield <<'SQL'
BEGIN;
ALTER TABLE policy_version DISABLE TRIGGER immutable_version;
DELETE FROM active_policy WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
DELETE FROM audit WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
DELETE FROM role_edge WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
DELETE FROM "grant" WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
DELETE FROM role WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
DELETE FROM policy_version WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
DELETE FROM membership WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
DELETE FROM resource WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
DELETE FROM tenant WHERE tenant_id IN (SELECT 'load-'||n FROM generate_series(0,49) n);
ALTER TABLE policy_version ENABLE TRIGGER immutable_version;
COMMIT;
SQL
echo 'Only load-0..load-49 workload tenants removed; run up and benchmark to regenerate.'
