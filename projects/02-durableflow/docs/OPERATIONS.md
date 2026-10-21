# Operations

## Start, stop, and retained state

Use the repository launcher `up durableflow`, `demo durableflow`, and `down durableflow`. The project scripts resolve their own locations. `down` stops project processes and its Compose service while preserving the volume. `reset-demo` explicitly deletes that volume and the provider log; it never prunes Docker globally. Port overrides are PORT, PROVIDER_PORT and DURABLEFLOW_DB_PORT. Main runtime logs and PID files are under `target/runtime/`.

Startup builds before acquiring containers. It snapshots existing Compose container identities and running state, uses `--no-recreate`, then acquires missing runtime roles. A failed build, provider exit, or readiness check preserves the original failure status, stops only processes started by that invocation, stops newly started containers, and removes only newly created containers. Previously running processes/services and all volumes are preserved. Existing stopped containers return to their stopped state. An existing container is deliberately not replaced automatically: stop the project and explicitly plan its image replacement when changing the pinned image, retaining the named volume.

Each process has a numeric `.pid` plus a `.process.json` record binding the Linux boot ID, kernel start tick, executable, exact role/argument list, artifact path, and starting artifact hash. A live process with incomplete or mismatched records refuses startup/shutdown; inspect its identity instead of deleting records to force adoption. Shutdown verifies identity before and after opening a Linux pidfd, signals all selected roles before waiting, and uses a global bounded drain. Readiness verifies both responses and listening-socket ownership. These checks require Linux procfs and pidfd support. Some managed environments deny executable/FD dereference after an owned JVM is reparented. EACCES/EPERM on executable observation leaves the boot/start/exact-argument fences mandatory; a newly started process still requires executable proof. If an existing listener's FD ownership is hidden, repeat `up` fails closed with `run down, then up`, preserving existing resources. Use that stop/start sequence; do not remove ownership records or accept an unverified listener. The normal `up` → `demo` → `down` sequence is supported.

## Prepared artifacts

For an isolated measurement window, first run `down durableflow`, then `projects/02-durableflow/scripts/prepare.sh` before taking the exclusive measurement window. Preparation checks Linux live process arguments, including processes with lost PID files, and refuses any owned API/verification runtime before building and before stamping. An old JVM must never be reused as evidence for newly built disk bytes. Preparation always runs ordinary Maven verification and records a receipt only after success with unchanged source/root POM. The receipt binds all six module JARs, both executable JARs and the verification log by SHA-256. Then export `DURABLEFLOW_PREPARED=1` for `up` and `benchmark.sh`: startup validates the receipt before creating containers and runs no Maven. Missing receipts, changed source/dependencies, removed/changed artifacts or altered verification evidence refuse startup. Default `up` performs the normal build when no owned runtime is active; an already-running project reuses its verified artifact only when its recorded artifact hash still matches disk. Ownership-check failures never trigger a rebuild. explicit verify/integration commands always execute their tests. After a source or dependency change, prepare again before the next measurement window. Generated receipts live under target and are local preparation evidence, not substitutes for published source gates.

## Access and health

API health is `GET http://127.0.0.1:18200/health`. The named dev profile has users deployer/operator/viewer/worker and disposable password `disposable-local-only` (override DEMO_PASSWORD). They are separate roles within tenant demo. Protected profile requires OIDC_ISSUER, OIDC_AUDIENCE and a signed token carrying tenant plus matching scope. Do not expose the disposable profile outside loopback.

Configure ingress body limits to admit the four worker acknowledgement routes at 528 KiB while retaining the public 8 KiB bound. Dispatch and heartbeat send the exact issued `Claim`; completion wraps it with a typed result vector, and failure wraps it with a definitive-outcome flag and reason. Do not reconstruct or normalize its `parameters` string: the store verifies exact agreement with the current leased task, along with generation, credential, worker, and database-time deadline. A 409 means that claim can no longer authorize the requested operation; obtain current history and lease evidence rather than changing claim fields to force acceptance.

## Scheduling and recovery

Schedulers restart safely: they reconstruct pending work from PostgreSQL instead of local timers. Workers heartbeat every two seconds for ten-second leases. Claims are bounded to 16, operational workers use four slots, retries stop after four attempts with exponential delay and seeded jitter. A process crash can delay work until its lease expires. Database clock shifts can advance/delay lease and timer expiration; keep the host clock synchronized and monitor lateness rather than relying on wall-clock precision guarantees.

### Reconcile an uncertain effect

For stuck work, inspect workflow state and ordered history as viewer. RUNNING with leased tasks may be an active call; do not manually edit leases. MANUAL_REVIEW means the provider outcome is unknown. Consult the independent receipt/effect log; an authorized operator POSTs `/v1/executions/{id}/resolve` with task, decision APPLIED or NOT_APPLIED, a reason, and `result` (the actual typed result vector for APPLIED, an empty vector for NOT_APPLIED). This appends evidence. UNKNOWN_OUTCOME must never be resolved just because a client timed out. COMPENSATION_FAILED requires correcting the provider/business condition, then POST `/v1/executions/{id}/retry-compensation` with task and reason; this authorizes a new capped retry window and preserves previous attempts.

For an independently confirmed synthetic charge receipt, the resolution body is:

```json
{
  "task": "charge",
  "decision": "APPLIED",
  "reason": "Reconciled against the provider receipt record",
  "result": [{"type": "TEXT", "data": "receipt-42"}]
}
```

Use the actual provider result required by that pinned activity contract. To record independently confirmed absence of an effect, use `"decision": "NOT_APPLIED"` and `"result": []`; that records a failure requiring the normal business recovery path. The authenticated operator identity is recorded separately from the supplied reason. Re-read history before repeating a resolution: it is a state transition, and a second application is rejected once the unknown outcome has settled.

## Observe resource use

Useful database measurements: queue depth grouped by activity_task.status; lease expirations from attempts marked LEASE_EXPIRED and non-idempotent ambiguities marked UNCERTAIN; attempts per logical task; timer due versus history TIMER_FIRED at; created_at versus final history at; activity_task.created_at versus first dispatched_at. The benchmark writes raw JSON plus completion progress. No high-cardinality task IDs are advertised as metric labels.

## Backup and compatibility

Back up the disposable database with `docker compose -f projects/02-durableflow/infra/compose.yaml exec -T postgres pg_dump -U durableflow -d durableflow > backup.sql`, storing the provider effect log at the same operational checkpoint after stopping workers. Restore into an empty project database using psql, then restart the pinned engine build. Definitions referenced by existing instances and old worker implementations must be retained; incompatible semantics require an explicit migration design, not a startup override. Never remove the provider log while unresolved executions refer to its receipts.

The PostgreSQL adapter uses a Hikari pool capped at four connections per process, with ten-second acquisition and three-second validation budgets. The resource suite explicitly injects expired database deadlines while giving active operations generous leases, so CPU contention cannot silently turn a live successor into the stale worker being tested. The live demo separately waits for real 30-second lease expiry after worker death.

## Interpret execution results

New starts provide a typed root payload plus the exact declared context schema. Signals carry a typed value; worker completions and APPLIED resolutions carry homogeneous typed result vectors. Wrong types fail before history/state commits. A normal timer preserves its arguments; a signal timeout exposes TIMED_OUT, and only an explicit status predicate handles that path. COMPLETED means the finite graph settled, including intentionally skipped/timed-out outcomes; inspect returned outcomes when business completion requires successful values.

## Historical lifecycle observation

The recorded cloud lifecycle preserved 104 workflow instances across down/up and reset them to 0 with reset-demo. Both fault-demo rounds and smoke passed. The earlier script treated retained zombie PIDs as live, so its per-PID polling took 66 seconds after the 12-process smoke and 42 seconds after the 7-process demo. The services had already stopped. The current script distinguishes zombie state and uses a shared shutdown deadline; `scripts/prepared-build.py idle` independently checks live command arguments. Account for this observed command latency when scheduling measurement windows.

## Diagnose scheduler failures without stopping unrelated executions

Schedulers isolate each execution transaction. An instance that cannot transition receives a five-second retry delay; the next batch can process other due instances. The cumulative failure count survives process restart. Inspect this metadata alongside domain state:

```sql
SELECT id, tenant, state, version, scheduler_failures,
       last_scheduler_error, scheduler_retry_at
FROM workflow_instance
WHERE last_scheduler_error IS NOT NULL
ORDER BY scheduler_retry_at, id;
```

`HISTORY_CAPACITY` means the 10,000-event bound prevented a new event. Export the execution and investigate its event volume; an approved retention/continuation migration is required to extend that bound safely. Repeatedly resetting the retry timestamp does not create capacity. `INSTANCE_TRANSITION_FAILED` requires inspecting the definition pin and ordered history against the current/frozen interpreter contract. The stored category intentionally excludes SQL text, credentials, and arbitrary exception messages.

A known recoverable storage failure is retried by the scheduler loop. Workers retry recoverable claim acquisition with interruptible exponential delays from 100 ms to one second, resetting after a successful acquisition. Terminal SQL schema/integrity/authentication errors and invalid worker configuration terminate visibly; correct the deployment or storage contract before restarting that runtime. SQL failures are never disguised as instance scheduling errors. An unacknowledged claim never authorizes a local activity. HTTP storage/commit failures return 503 rather than a business-state conflict. Retry start or signal delivery with the original idempotency key and payload, because a lost commit acknowledgement does not establish whether the transaction committed. Failure metadata from an older scheduler snapshot cannot delay a subsequently advanced history.

| Symptom | Inspect | Recovery |
| --- | --- | --- |
| RUNNING but no work progresses | Lease deadline, attempt, dispatch intent, scheduling error columns | Restore storage/worker availability; allow lease recovery; reconcile a dispatched non-idempotent uncertainty |
| MANUAL_REVIEW | Independent provider receipt/effect log and recorded task parameters | Submit an audited APPLIED/NOT_APPLIED resolution with the actual typed result |
| COMPENSATION_FAILED | Prior attempts, provider refusal, outstanding obligation | Correct the business/provider condition, then authorize a bounded compensation retry |
| Repeated scheduler deferral | Failure category, history size/order, pinned definition and semantics | Repair the diagnosed cause or plan a compatibility-preserving migration; other instances continue |
| Worker stopped during provider I/O | Independent effect evidence and subsequent lease classification | Never infer no effect from shutdown or an absent HTTP acknowledgement |

## Graceful shutdown and forced recovery

Send SIGTERM to the owned runtime process, then allow its cleanup window to finish. Worker shutdown closes admission, drains accepted work while heartbeats remain active, then closes the provider and store. The graceful phase is bounded at 15 seconds, followed by a five-second interruption window; the process hook allows 30 seconds for owner cleanup. The launcher signals all selected roles, allows a shared 35-second graceful deadline, then bounds forced termination to five seconds.

A forced kill bypasses this path. Idempotent calls retain their stable provider key across reassignment. A dispatched non-idempotent call without a definitive result becomes UNKNOWN_OUTCOME when its database lease expires. Treat the provider's independent record as reconciliation evidence; process termination itself is not evidence that an effect did or did not occur.

[Migration V3](../workflow-persistence/src/main/resources/db/migration/V3__scheduler_failure_isolation.sql) is additive and must run before the updated scheduler or worker starts. It adds scheduling retry metadata; it does not rewrite historical inputs, event results, definition hashes, or interpreter semantics. Old semantics 1/2 fixtures and current semantics 3 histories remain covered by integration tests.

## Committed outcomes and pool cleanup

An acknowledged database commit remains successful if returning its connection to the pool later fails. The transaction boundary emits a cleanup warning and preserves the committed result; it does not report a retryable activity failure. A commit that loses its acknowledgement still fails as an unknown storage outcome and follows the existing lease/idempotency recovery contract. Domain, commit, and rollback failures retain their original cause and suppressed cleanup failures.

The public JSON adapter rejects duplicate keys, trailing JSON values, floating-point truncation into integer fields, null primitive fields, numeric enum ordinals, and number/boolean coercion into strings. Valid named enums and typed request fields retain their existing wire shape.

## Export a stable history without blocking workflow writers

Use the viewer-authenticated page endpoint for dashboards and diagnostic exports:

```text
GET /v1/executions/{id}/history/page?limit=100
GET /v1/executions/{id}/history/page?limit=100&after={nextAfter}&snapshotVersion={snapshotVersion}
```

Keep the first response's `snapshotVersion` for the entire export and use each
response's `nextAfter` in the next request. Stop when `hasMore` is false. Each page
contains at most 200 ordered events and checks the authenticated tenant again.
The first page defaults to 100 events; `after` defaults to zero. New events can
continue to commit while the export runs, but they cannot enter its frozen
prefix. Start a new export without `snapshotVersion` to include newer events.

If `after` equals the snapshot version, the endpoint returns an empty page with
`hasMore: false`. Negative offsets, out-of-range page sizes, future snapshots,
and cursors beyond the snapshot are rejected. Existing clients may continue to
use `/history`, which returns the original array shape up to the unchanged
10,000-event limit. This full-history compatibility route has a larger memory and
response cost than a page. An execution view also replays the complete bounded
history to derive values.

Viewer inspection does not advance timers, reschedule work, or repair missing
tasks. Use the scheduler and normal command paths for progress; repeatedly
refreshing the execution endpoint is not a recovery action. A history integrity
failure requires investigation of retained database rows and backups. Do not
reset the sequence/version to make a corrupt export appear complete.

All three integration classes normally use the pinned PostgreSQL container. To
run the complete integration suite against a disposable native PostgreSQL 18
database, set all three variables `DURABLEFLOW_TEST_JDBC_URL`,
`DURABLEFLOW_TEST_DB_USER`, and `DURABLEFLOW_TEST_DB_PASSWORD`, then run the same
`./mvnw -f projects/02-durableflow/pom.xml -B -ntp -Pintegration verify` command.
The JDBC URL must include an explicit loopback host, port, and database name; URL
parameters and caller-selected schemas are rejected. Each test class creates its
own randomly named schema, applies migrations there, and drops only that owned
schema on completion. The configured role requires schema-creation permission.
Never configure a valuable database for destructive integration tests.

Absent settings select the original pinned container. Partial settings, unsupported
servers, invalid endpoints, and failed connections fail setup rather than skipping
tests or substituting another backend. Native execution covers the same SQL,
process-crash, authenticated HTTP, and concurrency test methods. It does not test
container startup, the Alpine image's libraries, or image vulnerability status;
record the actual PostgreSQL version and artifact identity with the results.
