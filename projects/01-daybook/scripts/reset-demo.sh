#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
"${COMPOSE[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d daybook <<'SQL'
BEGIN;
CREATE TEMP TABLE demo_tenant AS SELECT '00000000-0000-0000-0000-000000000101'::uuid id;
DELETE FROM ledger.projection_checkpoint WHERE event_id IN(SELECT id FROM ledger.outbox_event WHERE tenant_id IN(SELECT id FROM demo_tenant));
DELETE FROM ledger.reporting_balance WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.outbox_event WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.reconciliation_decision WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.reconciliation_match WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.statement_row WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.statement_import WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.idempotency_receipt WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.posting WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.journal WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.account_balance WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.account WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.accounting_period WHERE tenant_id IN(SELECT id FROM demo_tenant);
DELETE FROM ledger.tenant WHERE id IN(SELECT id FROM demo_tenant);
COMMIT;
SQL
seed_demo
