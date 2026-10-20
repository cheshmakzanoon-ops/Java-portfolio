# Operations

## Local lifecycle

From the repository root:

```bash
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger
```

The scripts resolve their own paths and isolate Compose project `java-portfolio-clearledger`. Only loopback ports `18100` and `18101` are published. The API log and PID file are under `projects/01-clearledger/target/`. Shutdown verifies the tracked PID's command before signaling it and preserves database volumes.

`reset-demo.sh` removes and reseeds only synthetic tenant `00000000-0000-0000-0000-000000000101` and its dependent records. Benchmark scripts replace only their explicitly named `clearledger_benchmark_rN_wN` databases. Neither lifecycle operation authorizes deleting unrelated data.

## Health and reporting

`GET /health` checks adapter readiness. An `UP` adapter alone does not prove database availability; database operations independently enforce timeout budgets.

`GET /v1/reports/freshness` requires `ledger.admin` and reports pending events for the authenticated tenant. Reporting is a derived view, not the authoritative current balance. Global shadow replay requires the separate platform scope `ledger.system`.

The projector has a supported entry point:

```bash
projects/01-clearledger/scripts/projector.sh
```

Set `LEDGER_URL` and `LEDGER_PASSWORD` for the intended database through runtime configuration. Each event application and membership update commits atomically, so the process can restart safely. Passing `replay` builds a separate generation; a comparison failure preserves the currently active generation.

## Ambiguous outcomes and retries

Keep the original `Idempotency-Key` and economic intent after an HTTP timeout. Query `GET /v1/requests/{key}` under the same tenant and operation, or resend the identical request. A lost response does not prove rollback.

| Response | Client action |
| --- | --- |
| `503` | Retry within a bounded budget, with backoff and the original key and payload |
| `422` | Correct the terminal input error; do not retry unchanged input indefinitely |
| `409` | Investigate changed intent under a retained key or an already reversed source |

## Backup and restore

Run the following from `projects/01-clearledger` against the disposable local stack:

```bash
docker compose -f infra/compose.yaml exec -T postgres \
  pg_dump -U postgres -d clearledger --no-owner > target/backup.sql
```

Restore into a new, empty database with `psql -v ON_ERROR_STOP=1`; do not overwrite live tables. Provision the runtime login through secure configuration before restoration, then restore the original ACLs. Stripping grants or assuming an already-recorded Flyway migration will recreate them invalidates the permission boundary.

The PostgreSQL integration suite restores into a second clean instance, compares source and balances, replays reporting, and proves the restored runtime role still cannot delete postings.

## Source audit

From `projects/01-clearledger`:

```bash
docker compose -f infra/compose.yaml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U postgres -d clearledger \
  < ledger-verification/src/main/resources/clearledger/source-audit.sql
```

The audit starts from every journal, including journals with no postings. It checks 2–100 positive postings, bounded balanced totals, account currency, period dates, and exact side-swapped reversal multisets. No returned rows means those predicates hold in that snapshot. It does not establish every possible accounting invariant.

Separately recompute grouped account totals and compare them with authoritative balances. Shadow promotion performs that comparison before switching generations. Investigate any mismatch before accepting further writes. Corruption regression fixtures deliberately use administrator powers unavailable to the runtime role.

## Artifact preparation and recovery

Launchers reuse prepared artifacts only when module inputs, root/project POMs, and generated runtime bytes match the receipt written after successful preparation. Source or artifact changes require ordinary verified preparation after owned Java processes stop. Input hashes must remain unchanged across preparation; stale dependency copies are cleared.

Run `down` before rebuilding an active API. Preparation refuses a live owned Java process even if its PID file is missing. An untracked healthy port and an unhealthy tracked API are refused rather than silently reused. Inspect the exact reported process with `ps -p PID -o args=` before terminating it. Shutdown deliberately signals only the tracked API and stops its own Compose project.

The cache is confined to lifecycle launchers. Ordinary verification and integration commands still execute their checks. Benchmark preparation occurs before starting PostgreSQL, so queued Maven work does not reserve an idle database stack.
