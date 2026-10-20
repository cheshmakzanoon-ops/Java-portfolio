# Operations

## Start, stop, and retained state

Use the repository launcher `up durableflow`, `demo durableflow`, and `down durableflow`. The project scripts resolve their own locations. `down` stops project processes and its Compose service while preserving the volume. `reset-demo` explicitly deletes that volume and the provider log; it never prunes Docker globally. Port overrides are PORT, PROVIDER_PORT and DURABLEFLOW_DB_PORT. Main runtime logs and PID files are under `target/runtime/`.

## Prepared artifacts

For an isolated measurement window, first run `down durableflow`, then `projects/02-durableflow/scripts/prepare.sh` before taking the exclusive measurement window. Preparation checks Linux live process arguments, including processes with lost PID files, and refuses any owned API/verification runtime before building and before stamping. An old JVM must never be reused as evidence for newly built disk bytes. Preparation always runs ordinary Maven verification and records a receipt only after success with unchanged source/root POM. The receipt binds all six module JARs, both executable JARs and the verification log by SHA-256. Then export `DURABLEFLOW_PREPARED=1` for `up` and `benchmark.sh`: startup validates the receipt before creating containers and runs no Maven. Missing receipts, changed source/dependencies, removed/changed artifacts or altered verification evidence refuse startup. Default `up` still performs the normal build; explicit verify/integration commands always execute their tests. After a source or dependency change, prepare again before the next measurement window. Generated receipts live under target and are local preparation evidence, not substitutes for published source gates.

## Access and health

API health is `GET http://127.0.0.1:18200/health`. The named dev profile has users deployer/operator/viewer/worker and disposable password `disposable-local-only` (override DEMO_PASSWORD). They are separate roles within tenant demo. Protected profile requires OIDC_ISSUER, OIDC_AUDIENCE and a signed token carrying tenant plus matching scope. Do not expose the disposable profile outside loopback.

## Scheduling and recovery

Schedulers restart safely: they reconstruct pending work from PostgreSQL instead of local timers. Workers heartbeat every two seconds for ten-second leases. Claims are bounded to 16, operational workers use four slots, retries stop after four attempts with exponential delay and seeded jitter. A process crash can delay work until its lease expires. Database clock shifts can advance/delay lease and timer expiration; keep the host clock synchronized and monitor lateness rather than relying on wall-clock precision guarantees.

### Reconcile an uncertain effect

For stuck work, inspect workflow state and ordered history as viewer. RUNNING with leased tasks may be an active call; do not manually edit leases. MANUAL_REVIEW means the provider outcome is unknown. Consult the independent receipt/effect log; an authorized operator POSTs `/v1/executions/{id}/resolve` with task, decision APPLIED or NOT_APPLIED, a reason, and `result` (the actual typed result vector for APPLIED, an empty vector for NOT_APPLIED). This appends evidence. UNKNOWN_OUTCOME must never be resolved just because a client timed out. COMPENSATION_FAILED requires correcting the provider/business condition, then POST `/v1/executions/{id}/retry-compensation` with task and reason; this authorizes a new capped retry window and preserves previous attempts.

## Observe resource use

Useful database measurements: queue depth grouped by activity_task.status; lease expirations from attempts marked LEASE_EXPIRED and non-idempotent ambiguities marked UNCERTAIN; attempts per logical task; timer due versus history TIMER_FIRED at; created_at versus final history at; activity_task.created_at versus first dispatched_at. The benchmark writes raw JSON plus completion progress. No high-cardinality task IDs are advertised as metric labels.

## Backup and compatibility

Back up the disposable database with `docker compose -f projects/02-durableflow/infra/compose.yaml exec -T postgres pg_dump -U durableflow -d durableflow > backup.sql`, storing the provider effect log at the same operational checkpoint after stopping workers. Restore into an empty project database using psql, then restart the pinned engine build. Definitions referenced by existing instances and old worker implementations must be retained; incompatible semantics require an explicit migration design, not a startup override. Never remove the provider log while unresolved executions refer to its receipts.

The PostgreSQL adapter uses a Hikari pool capped at four connections per process, with ten-second acquisition and three-second validation budgets. The resource suite explicitly injects expired database deadlines while giving active operations generous leases, so CPU contention cannot silently turn a live successor into the stale worker being tested. The live demo separately waits for real 30-second lease expiry after worker death.

## Interpret execution results

New starts provide a typed root payload plus the exact declared context schema. Signals carry a typed value; worker completions and APPLIED resolutions carry homogeneous typed result vectors. Wrong types fail before history/state commits. A normal timer preserves its arguments; a signal timeout exposes TIMED_OUT, and only an explicit status predicate handles that path. COMPLETED means the finite graph settled, including intentionally skipped/timed-out outcomes; inspect returned outcomes when business completion requires successful values.

## Historical lifecycle observation

The recorded cloud lifecycle preserved104 workflow instances across down/up and reset them to0 with reset-demo. Both fault-demo rounds and smoke passed. On this host, PID1 retains exited background JVMs as zombies; down's bounded five-second-per-PID polling took66seconds after the12-process smoke and42seconds after the7-process demo. The services had already stopped; `scripts/prepared-build.py idle` independently checks live command arguments. Account for this observed command latency when scheduling measurement windows.
