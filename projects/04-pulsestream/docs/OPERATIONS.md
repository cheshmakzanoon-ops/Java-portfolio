# Operations and recovery

## Prepare and run

Requirements: pinned JDK 25, the repository Maven wrapper, Docker with Compose, Bash, Python 3, curl, and Linux with `/proc` and `flock`. From the repository root:

```bash
# Managed workspace: select its JDK, Maven proxy, and CA trust.
source scripts/cloud-env.sh
./scripts/portfolio.sh verify pulsestream
./scripts/portfolio.sh integration pulsestream
./scripts/portfolio.sh up pulsestream
./scripts/portfolio.sh demo pulsestream
```

Startup verifies changed executable inputs and artifact hashes, starts dependency health checks, creates the two live topics, and starts the API/processor. The API binds `127.0.0.1:18400`, PostgreSQL `18401`, and Kafka `18402`. `PULSE_HTTP_PORT`, `PULSE_SQL_PORT`, and `PULSE_KAFKA_PORT` override these loopback reservations. The default local profile trusts `X-Dev-Tenant`; protected configuration is described in [security](SECURITY.md).

Current Compose/integration fixtures pin PostgreSQL 18.6-alpine and Kafka 4.2.2 by digest. Application Kafka clients remain on the compatible 4.2.1 line. Historical measured image identities remain in their original evidence. The current broker image still has seven reported HIGH findings in upstream libexpat, LZ4, and Jackson; the application dependency baseline is a separate scan boundary.

```bash
./scripts/portfolio.sh down pulsestream
```

Shutdown verifies the tracked API's boot/start identity and exact JAR command, sends TERM through a PID file descriptor when available, waits up to 30 seconds, and escalates to KILL with a further five-second wait. It then stops only `java-portfolio-pulsestream` services. Named PostgreSQL and Kafka volumes survive. Kafka explicitly writes `/var/lib/kafka/data`, matching its mounted volume. Historical orderly-recreation evidence proves retained UUID/checkpoints/results for those images; it does not establish physical power-loss durability.

## Local resource ownership and upgrades

[Startup](../scripts/up.sh) and [shutdown](../scripts/down.sh) serialize through a lifecycle lock. Before startup changes dependencies, it records every existing project container ID and the subset already running. Failed Compose, topic creation, process startup, or readiness stops the newly launched JVM, removes only newly created containers, and restores previously stopped containers to stopped. Previously running services and named volumes remain. Cleanup failures are reported beside the original failure status; repeated terminal signals cannot abandon rollback.

The JSON process record in `.work/A04/runtime/service.pid` binds the PID to its kernel start time, boot ID, exact artifact path, and launch hash. A changed JAR or changed verified source fingerprint cannot silently reuse a running older JVM. Stop the service before preparing replacement artifacts; an unhealthy tracked service must also be stopped explicitly. Bare numeric PID files are refused. A healthy untracked service on the configured HTTP port is refused.

Compose starts with `--no-recreate` so failed startup cannot replace a preexisting container. To apply a digest/configuration change, first stop the stack, retain a compatible backup, and explicitly remove only the selected stopped containers before starting again:

```bash
./scripts/portfolio.sh down pulsestream
docker compose -f projects/04-pulsestream/infra/compose.yaml rm -f postgres kafka
./scripts/portfolio.sh up pulsestream
```

This retains named volumes; it does not make an incompatible PostgreSQL major upgrade or Kafka storage-format change safe. Never add `--volumes` for ordinary shutdown or image replacement. Failed startup restores resource ownership, not SQL migrations or already created topic state on preexisting services.

[Bounded commands](../scripts/run-bounded.py) own a fresh process group. Normal command completion, deadline expiry, and cancellation clean up resistant descendants. The leader remains unreaped until the final group signal, preventing PID/group reuse from authorizing a signal to an unrelated group. The original command/deadline/cancellation status survives cleanup.

## Query the executed example

The deterministic demo records its synthetic run and proves the first tumbling result independently. After `demo` succeeds:

```bash
run=$(cat .work/A04/runtime/last-demo-run)
curl --fail-with-body -sS -H 'X-Dev-Tenant: t' \
  "http://127.0.0.1:18400/v1/runs/$run/windows?tenant=t&sensor=a&type=TUMBLING&limit=100"
curl --fail-with-body -sS -H 'X-Dev-Tenant: t' \
  "http://127.0.0.1:18400/v1/runs/$run/status?tenant=t"
curl --fail-with-body -sS -H 'X-Dev-Tenant: t' \
  "http://127.0.0.1:18400/v1/runs/$run/quarantine?tenant=t&after=0&limit=100"
```

The first tumbling row has count 3, sum 35, minimum 5, maximum 20, and `finalized=true`. Status retains one logical duplicate and nonempty SQL next positions. Window pagination uses the last returned `start` as `after`; quarantine uses the last diagnostic `id`. Preserve run, tenant, sensor, and type filters between pages. An unknown/nonmember run returns 404; a tenant-header/token mismatch returns 403. Queries accept no body, including chunked GET bodies.

Kafka input follows [event-v1.schema.json](../contracts/event-v1.schema.json). Producers must use the key `tenantId:sensorId` with Kafka's Murmur2 partitioning and the configured fixed partition count. An omitted `recordType` is a legacy measurement; explicit `PROGRESS` must carry zero value. This is a query service: there is no HTTP ingestion or administrative mutation endpoint.

`reset-demo` removes only the last recorded `demo_<timestamp>` run and its two named topics through parameterized database values:

```bash
./scripts/portfolio.sh reset-demo pulsestream
```

Run reset while dependencies are healthy and before shutdown, with replay stopped. It does not archive arbitrary live runs or remove global Docker resources.

## Interpret health and failures

`GET /health` reports processor failure, SQL availability, source lag, bounded queue occupancy/high-water mark, watermark lag, last checkpoint latency, and output delivery failures. SQL unavailability makes readiness 503 even for a query-only process. Healthy transport does not imply that all source partitions have progressed or all output has been delivered. Run status reads the committed snapshot and tenant-filtered late metrics.

| Observation | Meaning | Recovery |
| --- | --- | --- |
| SQL failure or `SESSION_RESTART_REQUIRED` | Memory cannot safely resolve its last processing decision | Restore database availability and restart; recover from SQL, retaining run and source identity |
| Lost commit acknowledgement | SQL may have committed | Do not reapply the candidate in memory; restart and seek SQL next positions |
| Cleanup warning after acknowledged commit | Commit already succeeded; connection cleanup failed | Retain the known decision and inspect connection health; no callback replay |
| `FENCED_OWNER` | Owner epoch changed or lease expired | Stop the obsolete processor; never retry its epoch |
| `RUN_OWNED` | A live database-time lease or active transaction retains the run | Wait for orderly release/expiry; inspect the owner and active SQL before intervention |
| `TOPIC_ID_CHANGED` / `PARTITION_COUNT_CHANGED` | Source identity or topology no longer matches the run | Restore matching input or establish a new finite run; never silently reinterpret old positions |
| `SOURCE_POSITION_UNAVAILABLE` | Required position is below retention or beyond the source end | Recover the matching source/backup or acknowledge loss explicitly in a new run |
| Output publication failure | Some broker acknowledgements may precede a rolled-back SQL delivery mark | Retry the pending outbox with stable revision keys; downstream deduplicates |
| `OWNER_DID_NOT_STOP` | Cleanup deadline elapsed while an owner remains active | Inspect its stack and SQL/network waits; do not treat resource/lease release as complete |
| `STATE_DECODING` / changed run limits | Stored state is incompatible, corrupt, or exceeds its declared bounds | Preserve bytes and source metadata; restore a compatible version/backup rather than resetting state |
| Diagnostic quota exhausted | Append-only rejections reached `6 × event quota` | Stop, retain evidence, and archive or migrate explicitly; the failed transaction did not advance position |

The engine thread stops after a failed/unknown state commit. The bounded queue can fill and the poll owner pauses new admission. On restart, SQL restores state and dedup, idle flags reset, and the poll owner validates retained source positions before seeking. A broker offset mirror alone cannot reconstruct a decision.

Shutdown interrupts and joins the engine before closing its publisher or releasing its owner, then stops the poller. Cleanup preserves an interrupted caller. The engine join budget is 20 seconds; poll join 15 seconds, consumer close 5 seconds, publisher close 10 seconds. JDBC has a five-second connection budget, 15-second socket/statement budgets, and a two-second readiness validation budget. These sequential budgets are not one end-to-end deadline. The local launcher imposes its own 30-second TERM and five-second KILL wait; escalation can end a JVM before every sequential Java cleanup step finishes. The next process then uses the same SQL-authoritative recovery and lease-expiry rules as any process termination. If owner release fails after termination, a warning is recorded and the database-time lease expires.

## Quotas, backup, and archival

Keys, panes, and identities have finite per-run limits; dedup and admitted keys do not expire automatically. Existing runs reject changed limits. Raising quotas needs a designed state migration or an archived/new run. State transition copying and recovery memory scale with retained history; finite quotas are part of the supported model.

Stop the processor before preparing a recovery point. Back up all PostgreSQL state/checkpoint/result/identity/diagnostic/outbox tables together, retain roles/grants, and retain source topic UUID and partition offset ranges. For the disposable local database:

```bash
mkdir -p projects/04-pulsestream/target/backups
docker compose -f projects/04-pulsestream/infra/compose.yaml exec -T postgres \
  pg_dump -U pulse -d pulse --format=custom \
  > projects/04-pulsestream/target/backups/pulse.dump
```

Restore into a new empty database with the compatible application version. Before processing, verify topic UUID, partition count, and every SQL next position against Kafka's retained beginning/end. Do not restore only window results or only group offsets. Current snapshot/result encoding remains V1; rolling migration and distributed rescaling are unsupported.

Archive only the selected stopped run after retaining required results and pending delivery history. Delete child rows before the owner row, as the narrowly scoped reset script demonstrates. Never manually expire an active owner simply to make a second processor start: an in-progress transaction holds the owner lock and can still commit valid work.

## Verification commands

```bash
source scripts/cloud-env.sh
export PORTFOLIO_TEST_RUN_STARTED="$(date +%s)"
./mvnw -f projects/04-pulsestream/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A04/m2" -B -ntp \
  -Pintegration,mutation verify
python3 scripts/check-tests.py projects/04-pulsestream integration
python3 scripts/check-mutations.py projects/04-pulsestream
python3 projects/04-pulsestream/scripts/test-lifecycle.py
```

The integration profile requires real Kafka/PostgreSQL and executes process termination, ownership, retention, output recovery, protected HTTP, and shutdown fixtures. Missing Docker fails the prerequisite. Mutation targets cover the engine, wire/termination boundary, SQL transaction boundary, and snapshot codec. A timeout remains a non-kill in the strict result. No benchmark is required to exercise the local development/recovery workflow; measured studies require their separately documented host and input controls.

Executable identity is mandatory when publishing a new process record. If a managed procfs later denies only executable dereference with `EACCES`/`EPERM`, boot/start identity and the hash of every exact command argument remain mandatory; a readable executable must still match. Other read failures refuse ownership. Two kernel-stat reads bracket the argument/executable observation, and a changed kernel start identity refuses control. Argument hashes avoid retaining command configuration or secrets in the record.
