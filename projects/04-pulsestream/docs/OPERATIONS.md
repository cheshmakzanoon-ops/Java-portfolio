# Operations

## Local lifecycle

Requirements: JDK 25, the repository Maven wrapper, Docker with Compose, Bash, and curl. From the repository root:

```bash
./scripts/portfolio.sh up pulsestream
./scripts/portfolio.sh demo pulsestream
./scripts/portfolio.sh reset-demo pulsestream
./scripts/portfolio.sh down pulsestream
```

Startup verifies the project when executable inputs or artifact hashes change, checks dependency health, and starts the query API on loopback port `18400`. PostgreSQL uses `18401`; Kafka uses `18402`. `PULSE_HTTP_PORT`, `PULSE_SQL_PORT`, and `PULSE_KAFKA_PORT` override these reservations.

Shutdown verifies the API PID's command identity and stops only this project's services. Volumes survive. Kafka writes to the named volume at `/var/lib/kafka/data`; PostgreSQL has a separate volume. Image digests are pinned to the tested versions. `evidence/container-recreation.json` records retained topic identity, nonzero SQL checkpoints, and verified results after orderly recreation.

Reset removes only the last recorded `demo_<timestamp>` run and its two topics through parameterized database values. It does not touch live runs or global Docker resources. Reset while dependencies are healthy and before shutdown; never reset during replay.

## Health and recovery

`GET /health` exposes processor failure, source lag, bounded queue occupancy/high-water mark, watermark lag, last checkpoint latency, and outbox delivery failures. Run status reads committed SQL state, checkpoint age, partition activity, and tenant-filtered late counts.

A SQL failure stops processing. The bounded queue eventually causes the Kafka poller to pause admission. Restore database availability and restart the process; a failed or ambiguous commit poisons its durable session. Restart restores snapshot and dedup state, then seeks each Kafka partition to the SQL next-offset checkpoint. Kafka's mirrored offsets are not recovery authority.

| Failure | Meaning and action |
| --- | --- |
| `FENCED_OWNER` | Another epoch took over or the lease expired. Stop the obsolete processor; never retry its epoch. |
| `TOPIC_ID_CHANGED` | The source topic was deleted and recreated. Restore the matching source or start a new finite run. |
| `SOURCE_POSITION_UNAVAILABLE` | Retention erased a required position, or the source ends before its checkpoint. Recover an appropriate backup or acknowledge loss explicitly in a new run. |
| `SESSION_RESTART_REQUIRED` | The process cannot resolve its last commit outcome safely in memory. Restart from SQL. |

Startup never silently skips missing source positions.

## Quotas and archival

Pane, key, and event quotas create explicit durable rejection reasons. Append-only diagnostics have a separate cap of six times the run-event quota. Exceeding it fails the transaction without advancing the source checkpoint; unfinished state does not disappear.

Existing runs reject changed limits. Raising a quota requires a separately designed state migration, or archival followed by a new finite run. Deduplication entries never expire automatically.

Before archiving, stop the processor, retain required results and outbox history, and back up the database. Remove only the selected run in child-before-owner order, as the narrowly scoped reset script demonstrates. This releases both database and recovery-memory growth.

## Backups

Back up PostgreSQL together with the retained input topic and its UUID metadata. Restore the transactional state, checkpoint, result, and deduplication tables together. The restored topic identity and retained offset range must match. A Kafka offset mirror alone cannot reconstruct a committed SQL decision.

The current Java snapshot schema requires the same application version. Rolling snapshot migration and distributed rescaling are not supported.

## Verification

In the managed workspace, source the toolchain configuration, then run the standalone project build:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/04-pulsestream/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A04/m2" -B -ntp verify
```

Add `-Pintegration` to exercise real PostgreSQL, Kafka, process-crash, retention, and protected-token scenarios. Missing Docker fails the integration suite rather than silently skipping it.
