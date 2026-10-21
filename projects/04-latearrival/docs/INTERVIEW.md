# Engineering discussion guide

Walk through one late measurement using `Engine.transition`: membership uses half-open event-time windows; a measurement may update some sliding windows while being too late for an older one. Explain why a pane must remain until its latest dependent window finalizes, and why count/sum overflow must reject the entire contribution before any aggregate changes.

Trace a restart through `DurableSession` and `PostgresStore`. SQL owns the next source offsets, event hashes, state, result revisions and outbox in one transaction. Kafka committed offsets are an operational mirror. A lost SQL acknowledgement poisons the session; restoring SQL state resolves the uncertainty. The process-kill tests exercise both sides of the commit boundary.

Explain the ownership argument: acquiring and writing lock the same owner row; epochs reject obsolete owners and database time bounds idle ownership. An active transaction retains that row lock, preventing takeover during publication. It renews before commit so a slow successful delivery does not immediately fence its own next transaction. Lease expiry during a process pause permits takeover, after which the old epoch cannot resume writes.

Use the source-progress and queue fixtures to explain watermark safety. An empty Kafka poll is insufficient evidence of inactivity: broker lag, paused fetching, queued batches and an in-flight batch all matter. An unseen partition blocks progress, all-idle holds the watermark, and restoration restarts silence classification. Compare the independent recorded-control oracle rather than feeding the engine watermark into the expected result.

Demonstrate an outbox crash after Kafka accepts a revision but before SQL marks delivery. A repeated durable `(run, window, revision)` key is expected. Consumers must tolerate that repetition; the implementation offers at-least-once publication. A failed delivery leaves its SQL row pending and never advances source state independently.

Discuss the measured workload separately from the engine microbenchmark. The full driver includes SQL snapshots, checkpoints and outbox insertion; it excludes Kafka result publication and reports input preparation separately. Dedup-map copying, snapshot compression and pane replacement are explicit costs to measure. Current evidence and unfinished experiments are recorded in [STATUS](STATUS.md); no unexecuted workload establishes performance.

Review finite quotas and recovery assumptions: run archival owns dedup retirement; unfinished panes are never silently evicted; source retention gaps and topic identity changes fail startup. Snapshots currently require the same application schema/version. PostgreSQL/Kafka process recovery on one host does not establish host or physical-storage fault independence.
