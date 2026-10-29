# Operations and recovery

## Prepare and run

Requirements: the pinned JDK 25 and Maven wrapper, Docker with Compose, Bash, Python 3, curl, and Linux `/proc` with `flock` for local process ownership. From the repository root:

```bash
# Managed workspace only: select its pinned JDK, proxy, and CA configuration.
source scripts/cloud-env.sh
./scripts/portfolio.sh verify daybook
./scripts/portfolio.sh integration daybook
./scripts/portfolio.sh up daybook
./scripts/portfolio.sh demo daybook
```

Startup prepares source-bound artifacts before starting PostgreSQL, migrates the database, starts the API, waits for health, and seeds the synthetic tenant. The API binds `127.0.0.1:18100`; PostgreSQL binds `127.0.0.1:18101`. The local Compose project is `java-portfolio-daybook`. Inspect `projects/01-daybook/target/api.log` if readiness fails.

```bash
./scripts/portfolio.sh down daybook
```

Shutdown verifies the recorded boot ID, kernel start time, executable, and exact JAR path before signaling. PID file descriptors target the recorded process where available; TERM waits at most 30 seconds, then KILL waits five seconds. A reused PID or mismatched artifact path is refused. The JSON record at `target/api.pid` is not a bare PID. Only this project's services stop, and database volumes survive. `reset-demo.sh` deliberately removes and reseeds tenant `00000000-0000-0000-0000-000000000101` and dependents; use it only for that disposable fixture. Workload scripts replace only their explicitly named `daybook_benchmark_rN_wN` databases.

Failed startup snapshots both all and running project container IDs. It removes only new containers, returns previously stopped containers to that state, and preserves existing running services and volumes. Cleanup retains the original error even if a secondary cleanup operation fails; repeated terminal signals do not abandon rollback. Process launch/stop records serialize under a file lock, and an early JVM exit fails readiness immediately.

Startup uses `--no-recreate` to preserve preexisting container identity. Image/configuration upgrades are explicit: after a backup and `down`, remove only the stopped service container before `up`; retain its volume. For the PostgreSQL service:

```bash
docker compose -f projects/01-daybook/infra/compose.yaml rm -f postgres
```

A changed JAR cannot reuse an old running JVM. Stop it before preparing/restarting changed artifacts; the recorded old JVM remains eligible for a safe stop even after its JAR bytes change. Do not adopt a legacy numeric or invalid PID record automatically.

## HTTP example with retained identity

`up` explicitly selects the local `dev` profile. The following short-lived token uses its already-public synthetic signing key and must be used only against that loopback fixture. Protected mode instead requires a token from the configured trusted issuer with the appropriate tenant and scopes; never reuse this fixture key there.

```bash
export LEDGER_TOKEN="$(python3 - <<'PY'
import base64, hashlib, hmac, json, time
encode = lambda value: base64.urlsafe_b64encode(value).rstrip(b'=')
header = encode(json.dumps({'alg': 'HS256', 'typ': 'JWT'}).encode())
claims = encode(json.dumps({
    'iss': 'http://127.0.0.1:18109/dev-issuer', 'sub': 'synthetic-operator',
    'aud': ['daybook'], 'exp': int(time.time()) + 900,
    'tenant': '00000000-0000-0000-0000-000000000101',
    'scope': 'ledger.read ledger.write ledger.reconcile ledger.admin ledger.system'
}).encode())
unsigned = header + b'.' + claims
signature = encode(hmac.new(b'daybook-disposable-local-demo-key-32-bytes',
                            unsigned, hashlib.sha256).digest())
print((unsigned + b'.' + signature).decode())
PY
)"

curl --fail-with-body -sS http://127.0.0.1:18100/v1/journals \
  -H "Authorization: Bearer $LEDGER_TOKEN" \
  -H 'Idempotency-Key: ops-example-001' \
  -H 'Content-Type: application/json' \
  --data '{"period":"00000000-0000-0000-0000-000000000102","date":"2026-01-02","currency":"CAD","reference":"ops-example","lines":[{"account":"00000000-0000-0000-0000-000000000103","side":"DEBIT","amount":"1.00"},{"account":"00000000-0000-0000-0000-000000000104","side":"CREDIT","amount":"1.00"}]}'
```

The response contains `journalId`. Repeating the exact command returns that ID; submitting a different balanced journal under that key conflicts. After an interrupted or lost response, query the retained receipt:

```bash
curl --fail-with-body -sS \
  -H "Authorization: Bearer $LEDGER_TOKEN" \
  http://127.0.0.1:18100/v1/requests/ops-example-001
```

To reverse, POST `{"date":"2026-01-02"}` to `/v1/journals/{journalId}/reversals` with a new retained key such as `ops-reversal-001`. Retry that reversal with its same key and date if its outcome is unknown. The adapter uses the original period; a date outside it or a closed period is rejected. An adjustment into another period requires a separate journal.

To import a synthetic statement, send UTF-8 CSV with `Content-Type: text/csv` to `/v1/statements`:

```text
source_id,reference,currency,amount
ops-row-001,ops-example,CAD,1.00
```

The returned `importId` identifies `/v1/reconciliation/{importId}`. Reimporting identical bytes under the same tenant returns the same identity and decisions. Different encodings/line endings are different content hashes. Malformed UTF-8 and NUL reject the whole file before database access; invalid rows in a valid file retain their row-specific reasons.

`GET /v1/accounts/{accountId}/statement?limit=2` returns `snapshot`, `boundary`, and a final-row cursor. Reuse all of them for the next page; use curl's `--get --data-urlencode` for the snapshot and timestamp. Resetting the snapshot between pages changes the view. Statements use immutable source, not reporting balances.

## Failure and retry decisions

| Observation | Meaning | Recovery |
| --- | --- | --- |
| HTTP timeout/disconnect during posting | Commit may have happened | Retain tenant, operation, key, and exact intent; query the receipt or resend identically |
| `409 IDEMPOTENCY_CONFLICT` | Key already records different intent | Do not overwrite or guess success; reconcile client intent with the stored result |
| `422 PERIOD_CLOSED_OR_DATE` | New intent is outside the locked period boundary | Correct the intended date/period; existing matching receipts remain retrievable after closure |
| Known serialization/deadlock/lock race | Current transaction was rejected | Server retries the complete callback after releasing the connection, at most 12 attempts |
| `503` temporary/unknown database failure | Retry budget exhausted or outcome needs caller recovery | Bounded same-key retry with backoff; never issue a new key merely because a response was lost |
| Connection cleanup warning after acknowledged commit | Commit already succeeded; pool return failed | Returned result remains authoritative; inspect connection/pool health without replaying the business callback |
| Projection process terminates mid-event | Membership and deltas either both committed or neither did | Restart the projector; durable membership makes redelivery safe |
| Shadow comparison rejects promotion | Candidate generation does not match source in its comparison snapshot | Keep the active generation; retry replay into a new generation after investigating active writers |
| Manual reconciliation loses a candidate race | Another source row reserved the posting | Refresh review rows and select valid evidence; the losing decision creates no second match or audit record |

The default pool has 32 connections and a 15-second checkout budget. Each attempt sets 15-second statement and 10-second lock timeouts. Eleven jitter pauses request at most 1,532 ms in total, but SQL, checkout, locks, and scheduling add time. These settings are not an end-to-end 15-second API deadline. Workload clients have a separate six-call policy; that is not an unlimited service retry guarantee.

Unexpected application null dereferences are server defects, not missing-field responses. Required DTO/domain fields fail explicitly before persistence. Application logs preserve primary and suppressed rollback diagnostics; HTTP errors return known codes without JDBC messages or stack traces.

## Reporting lifecycle

```bash
projects/01-daybook/scripts/projector.sh
```

Set `LEDGER_URL` and `LEDGER_PASSWORD` through secure runtime configuration for another database. The projector may stop/restart; its applied-event membership and deltas are atomic. It rereads pending membership rather than relying on a maximum event ID.

Passing `replay` builds a separate generation and promotes only after source comparison. HTTP replay requires platform `ledger.system`; tenant reporting requires `ledger.admin`. `GET /v1/reports/freshness` returns tenant-scoped pending events. Reporting responses identify their generation, snapshot, and `authoritative=false`; `GET /health` runs a bounded PostgreSQL transaction and returns `200 {"status":"UP"}` only when storage is reachable; storage or closed-pool failures return `503 {"status":"DOWN"}`. This checks storage availability, not projection freshness. The check uses the 15-second connection/statement budgets and is not a low-latency liveness probe.

## Schema upgrade, backup, and restore

Before upgrading an existing local installation, stop the API/projector and retain a PostgreSQL backup. From `projects/01-daybook` while its database is available:

```bash
docker compose -f infra/compose.yaml exec -T postgres \
  pg_dump -U postgres -d daybook --no-owner > target/backup.sql
```

`up` applies Flyway migrations through its configured administrative connection. V1/V2 checksums and stored journal/receipt bytes remain unchanged. V3 validates new operation identity before receipt lookup and adds the `POST`/`REVERSE` operation constraint. A V2-to-V3 integration regression posts before migration, then verifies original receipts, balances, source audit, and runtime permissions afterward. If existing receipts contain unsupported operation values, migration fails for investigation; do not rewrite financial history to force it through.

Restore into a **new empty database** using `psql -v ON_ERROR_STOP=1`. Provision the runtime role through secure configuration first, then restore original ACLs. Do not strip grants or assume an already-recorded migration recreates them. The integration suite restores a second instance, audits source/totals, replays reporting, and proves the restored runtime role still cannot delete postings.

Run the source audit from `projects/01-daybook`:

```bash
docker compose -f infra/compose.yaml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U postgres -d daybook \
  < ledger-verification/src/main/resources/daybook/source-audit.sql
```

A zero-row audit establishes the documented journal predicates in that snapshot. Independently recompute grouped posting totals and compare both debit and credit totals with authoritative balances. Investigate mismatches before accepting further writes. A backup without its roles/grants and retained source is not a verified recovery plan.

## Integration tests without Docker

The integration classes use the pinned PostgreSQL container by default. To run the same methods against a disposable native PostgreSQL 16 or later server instead, set all three of `DAYBOOK_TEST_JDBC_URL`, `DAYBOOK_TEST_DB_USER` and `DAYBOOK_TEST_DB_PASSWORD`, then run `./mvnw -f projects/01-daybook/pom.xml -B -ntp -Pintegration verify`. The URL must name an explicit loopback host, port and maintenance database (for example `jdbc:postgresql://127.0.0.1:55418/postgres`) with no parameters or credentials, and the role needs permission to create databases and roles because the migration creates `daybook_runtime`. Each test class creates its own randomly named database and drops only that database. Absent settings select the container; partial settings, a server older than 16, an invalid endpoint or a failed connection fail setup and never skip a test or substitute another backend.

The backup/restore test needs `pg_dump` and `psql` of the server's major version, which a native run cannot assume, so name their directory in `DAYBOOK_TEST_PG_BIN`; without it that test fails with an explanatory message instead of being skipped. A native run does not exercise container startup, the pinned image's libraries or its vulnerability status. Record the server version it prints with the results; the pinned container remains the reference environment.

## Artifact ownership

Launchers reuse artifacts only when input, POM, dependency, and output hashes match a receipt written after verified preparation. A source/artifact change requires preparation with owned Java processes stopped. An untracked healthy port, unhealthy tracked API, or lost PID file is refused rather than silently reused. Inspect the exact reported PID before terminating it. This cache is confined to lifecycle commands: ordinary, integration, and mutation verification still execute their checks.

Process/control regressions run without Docker:

```bash
python3 projects/01-daybook/scripts/test-lifecycle.py
```

They use real child processes and controlled Compose/readiness commands; live PostgreSQL lifecycle verification remains a separate check.

Executable identity is mandatory when publishing a new process record. If a managed procfs later denies only executable dereference with `EACCES`/`EPERM`, boot/start identity and the hash of every exact command argument remain mandatory; a readable executable must still match. Other read failures refuse ownership. Two kernel-stat reads bracket the argument/executable observation, and a changed kernel start identity refuses control. Argument hashes avoid retaining command configuration or secrets in the record.
