# RouteWeaver operations

The local fleet and travel data are synthetic. Use the repository checkout with JDK 25, the pinned Maven wrapper and Docker Compose. Each cloud task already has its isolated checkout; use it directly. Port 19000 is HTTP, 19001 PostgreSQL; both default to loopback. ROUTEWEAVER_PORT and ROUTEWEAVER_DB_PORT override them. Compose name is java-portfolio-routeweaver with its own named volume.

## Build and lifecycle

From the repository root:

```sh
projects/10-routeweaver/scripts/build.sh
projects/10-routeweaver/scripts/up.sh
projects/10-routeweaver/scripts/demo.sh
projects/10-routeweaver/scripts/restart-check.sh
projects/10-routeweaver/scripts/down.sh
```

build.sh formats and verifies the full four-module reactor, then records a fingerprint. up.sh refuses changed source/root POMs, starts only the pinned PostgreSQL stack, starts the service in explicitly trusted disposable loopback dev mode and waits for /health. It verifies a stored PID's command before signalling a stale service. down.sh stops only that process/Compose project and preserves data. A source stamp establishes which binaries were verified; it does not establish that an unexecuted demo passed.

GET /health returns application liveness. It does not prove a writable database or sufficient solver capacity. Check an actual problem/solve transaction for functional readiness. Logs are .runtime/service.log and .runtime/service-gc.log; source/PID metadata is also in .runtime. Runtime data, JARs, logs and database volumes do not belong in Git.

Repeat seed scenarios only while jobs are idle:

```sh
projects/10-routeweaver/scripts/reset-demo.sh
projects/10-routeweaver/scripts/demo.sh
```

Reset is a deliberate destructive action limited by the service to the demo tenant in dev mode; it refuses outstanding solves. It is unavailable in protected mode. It is not a global volume deletion or Docker prune. Preserve needed evidence before resetting.

## Protected service configuration

Protected mode is the application default. Set ROUTEWEAVER_ISSUER to an OIDC discovery issuer, ROUTEWEAVER_AUDIENCE (default routeweaver), and ROUTEWEAVER_DB_URL/USER/PASSWORD through environment bindings. Provide verified tokens with routeweaver.read for API GET and routeweaver.write for changes. Release launchers also supply ROUTEWEAVER_SOURCE_REVISION from the verified source fingerprint; manual protected startup must supply the correct executing artifact identity, never an old label. The static shell is public so its token entry is reachable; its data requests remain protected. No real secret belongs in logs, screenshots, source or benchmark records. The launcher intentionally selects the disposable dev profile and must remain on loopback.

## Durable solve and recovery

Submit an immutable version, explicit seed, requestId, budgetMs from 1 to 30,000 and steps from 1 to 100,000. The request returns 202 plus a durable UUID; poll the job until terminal. At most 17 outstanding jobs are admitted globally. A service process has one CPU worker; adding replicas changes total CPU capacity and must be budgeted explicitly. JDBC pool is eight, HTTP worker limit eight, connection bound 32 and accept queue 16. JDBC transaction statement timeout is ten seconds, lock timeout five seconds; connection acquisition is bounded too.

| Observation | Meaning and action |
| --- | --- |
| 400 INVALID_INPUT | Correct the bounded typed problem/request. Fractional units and scalar coercion are rejected. |
| 401/403 | Check signature/issuer/audience/expiry/claim types and required scope; do not weaken authorization to retry. |
| 404 | Identifier is absent in the caller's tenant. Do not search another tenant. |
| 409 CONFLICT | Refresh active input and plan revision, or use the original idempotent body. An obsolete solve cannot be published. |
| 429 SOLVER_CAPACITY | Outstanding admission is full. Back off and inspect active jobs; do not create unlimited client retries. |
| CANCEL_REQUESTED | Cooperative acknowledgement is pending; a valid incumbent is retained. Completion may have serialized first. |
| RUNNING after worker loss | Wait for its 15-second database-clock lease to expire. Startup/poll recovery requeues with a new fence. |
| Failed/blocked residual | Inspect frozen/cargo constraints; audit release only unstarted failed commitments. Never reassign stranded onboard cargo. |

Heartbeats renew the live lease every three seconds. Cancellation/fence reads are sampled every 100 ms to keep CPU search from opening a transaction per trial. Final writes always recheck current state/fence/lease under a lock. On restart, expired RUNNING becomes QUEUED with a newer fence and retained incumbent; expired CANCEL_REQUESTED becomes CANCELLED. No recovery path publishes automatically. Each recovered/queued claim must match the executing binary's configured source identity. A mismatch terminates through the fenced transaction with SOURCE_REVISION_MISMATCH, preserves the old incumbent as historical and emits no new candidate; a prior cancellation keeps its normal acknowledgement precedence. Drain/cancel old jobs before a rolling/source upgrade, retain the exact old artifact to resume them, or submit a new job under the current fingerprint. Even UI-only changes are conservatively incompatible. Do not relabel a new artifact with an old source hash to bypass this check. A recovered attempt receives its configured solve budget again; queue/recovery time is additional wall time. Do not edit generation/state columns manually to bypass authority checks.

restart-check.sh verifies an actual SIGKILL of its own service after a durable incumbent exists, restarts against the same database and asserts a newer fence, same-input nondegradation, exact partition, terminal completion and no automatic publication. Its result artifact is .runtime/restart-result.json; execution status is recorded separately in evidence/STATUS.

## Backup and restore

Use a PostgreSQL logical backup with immutable input, candidate, job, plan and event tables together. The development database and credentials are disposable. For a consistent logical export of this project only:

```sh
docker compose -f projects/10-routeweaver/infra/compose.yaml exec -T postgres pg_dump -U routeweaver -d routeweaver --no-owner --no-acl > /tmp/routeweaver-backup.sql
```

Record a checksum and source/config identity beside the dump and protect it as tenant data. Restore first to a new isolated database with psql -v ON_ERROR_STOP=1; retain the original database. On a production restore, provision a least-privilege role and TLS separately, then start one service with the matching executing source against the restored database. An upgrade requires the source-compatibility policy above; old queued jobs are not silently relabeled. Independently validate stored published candidates against their exact restored problem snapshots before serving dispatches. Preserved lease timestamps remain subject to normal database-clock recovery; do not manufacture terminal states or transfer cargo.

PostgresIT.backupRestorePreservesImmutableInputsValidatedPlanAndDurableQueue exercises pg_dump and psql into a separate database, equality of source hash and published plan, reference validation, durable queued/idempotent work and restored immutability triggers. Its observed pass is required before treating this protocol as certified; see evidence/integration.json for the exact tested run. High availability, WAL archival/PITR and a production secret/TLS deployment are outside the local portfolio runtime.

## Release checks

Use -Pintegration verify for real PostgreSQL and -Pmutation verify for selected original Validator mutation checks. Docker absence must fail integration. scripts/benchmark.sh requires a separately scheduled quiet reference host and current binaries; do not run it beside builds or other stress. It writes raw corpus/JMH/resource/GC files. docs/RESUME.md and STATUS.md distinguish historical passes from current-source gates.
