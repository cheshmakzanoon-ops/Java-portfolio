# Operations

Run `scripts/up.sh`, `scripts/demo.sh`, `scripts/down.sh` from this project or use the root launcher. Scripts resolve their own paths, bound readiness waits, preserve failures, and isolate Compose project `java-portfolio-clearledger`. Only loopback18100/18101 are published. `target/api.log` and `target/api.pid` hold local process output/state; down verifies the PID command before stopping it and preserves volumes.

Health: GET `/health` reports adapter readiness. Database-affecting operations independently enforce timeout budgets; an UP adapter alone does not prove PostgreSQL readiness. Reporting: GET `/v1/reports/freshness` with `ledger.admin` reports pending events. Do not use reporting as the authoritative current balance.

Run the projector independently using `java -cp '<domain classes>:<persistence classes>:<runtime dependencies>/*' io.github.cheshmakzanoon.portfolio.clearledger.Projector`; set LEDGER_URL/LEDGER_PASSWORD. It can be terminated/restarted; every applied event/checkpoint is atomic. To rebuild, pass `replay`; a mismatch fails promotion and preserves the active generation.

Ambiguous timeout recovery: retain the same Idempotency-Key, query GET `/v1/requests/{key}` under the same tenant and operation, or resend the identical request.503 permits a bounded client backoff with the original key;422 requires input correction;409 means changed intent or an already reversed source.

Backup example: `docker compose -f infra/compose.yaml exec -T postgres pg_dump -U postgres -d clearledger --no-owner > target/backup.sql`. Restore into a new empty database using psql `-v ON_ERROR_STOP=1`; do not overwrite live tables. The integration suite restores a dump into a second clean PostgreSQL instance and rechecks balance/source equality and replay. Provision the runtime login separately before restoration (using securely injected credentials), then restore the dump with its original ACLs. The restore test also proves the restored runtime can replay and still cannot directly delete postings. Do not strip ACLs or assume rerunning an already-recorded Flyway migration will recreate them.

Audit: group ledger.posting by `(tenant_id,journal_id)` and require count≥2 and signed sum zero; recompute grouped account debit/credit sums and compare with ledger.account_balance. Investigate any mismatch before accepting further writes. Restrict DBA credentials to administration. The demo admin and runtime passwords are explicitly disposable local fixtures; protected deployments must inject different secrets via environment settings.

`reset-demo.sh` deliberately removes only fixture tenant00000000-0000-0000-0000-000000000101 and dependents, then reseeds it. Benchmark scripts replace only explicitly named `clearledger_benchmark_rN_wN` databases. No global prune or destructive live reporting replay exists.
