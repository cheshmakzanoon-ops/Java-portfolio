# Operations

Prerequisites: repository Java 25/Maven wrapper, Docker Compose, bash and curl. From the repository root:

```bash
projects/04-pulsestream/scripts/up.sh
projects/04-pulsestream/scripts/demo.sh
projects/04-pulsestream/scripts/reset-demo.sh
projects/04-pulsestream/scripts/down.sh
```

`up` verifies the full project when executable inputs or artifact hashes changed, then starts dependency health checks and the HTTP service on 18400. PostgreSQL is 18401 and Kafka 18402, all loopback. `PULSE_HTTP_PORT`, `PULSE_SQL_PORT` and `PULSE_KAFKA_PORT` override reservations. `down` stops only this project's PID (after checking command identity) and Compose services; volumes survive. Kafka explicitly writes to the named volume mounted at `/var/lib/kafka/data`; PostgreSQL has its own named volume. Both images are pinned to the tested immutable digests. `evidence/container-recreation.json` records retained topic UUID, nonzero SQL offsets and independently verified results after both containers were recreated. `reset-demo` deletes only the last explicitly recorded `demo_<timestamp>` run and its two topics, using parameterized psql variables. It does not touch live runs or global Docker resources. Run reset while dependencies are still healthy, before down, and never while its replay is running.

`/health` exposes processor failure, source lag, bounded queue depth/high-water mark, watermark lag, last checkpoint latency and outbox delivery failures. `/status` reads committed SQL state, checkpoint age, active/idle counts and tenant-filtered late counts. SQL failure stops processing and queue admission eventually pauses; after restoring SQL, restart the process. The durable session deliberately refuses reuse after a failed/ambiguous commit. Restart restores snapshot/dedup before seeking every Kafka partition from SQL's next offset.

`FENCED_OWNER` means another epoch took over or the database lease expired. Stop the obsolete process; never retry its epoch. `TOPIC_ID_CHANGED` means the input topic was deleted/recreated. `SOURCE_POSITION_UNAVAILABLE` means retention erased a needed position, or the source is shorter than the checkpoint. These fail startup visibly. Restore an appropriate source backup, or start a new finite run/topic with explicit loss acknowledgment; there is no silent jump-forward option.

A pane/key/run-event quota produces durable reason codes; append-only diagnostics are additionally capped at six times the run-event quota, and exceeding that cap fails the transaction visibly without moving the source checkpoint. no unfinished state disappears. Raise a run's quota only through a separately designed state migration (existing runs reject changed limits), or archive and start a new run. Dedup is never automatically expired. To archive: stop the processor, retain/export required results/outbox, back up the database, then explicitly remove only that run's history in child-before-owner order (as in the narrowly scoped reset script). This frees both SQL and recovery-memory growth.

Back up PostgreSQL with `pg_dump` plus the input topic's retained source and topic UUID metadata. Restoration requires a matching topic identity and retained range. Preserve transactional snapshot/checkpoint/result/dedup tables together. The current Java snapshot schema requires the same application version. The Kafka offset mirror alone is insufficient for a backup or recovery decision.

Verification: `source scripts/cloud-env.sh; ./mvnw -f projects/04-pulsestream/pom.xml -Dmaven.repo.local="$PWD/.work/A04/m2" -B -ntp verify`; add `-Pintegration` for mandatory real PostgreSQL/Kafka/process/JWT tests. Missing Docker fails integration rather than silently skipping.
