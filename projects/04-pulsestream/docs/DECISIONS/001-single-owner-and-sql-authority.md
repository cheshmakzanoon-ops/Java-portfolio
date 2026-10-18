# ADR 001: One owner and SQL recovery authority

Accepted: one manually assigned Kafka partition set per finite run, one poll thread, one engine thread, SQL-authoritative next offsets. Row-locked epochs fence old processors. Kafka group commits are diagnostic mirrors only.

Kafka transactions cannot atomically commit PostgreSQL window state. Calling this arrangement global exactly once would hide the crash between those resources. The chosen guarantee is atomic PostgreSQL-visible results/checkpoints within one fenced run, plus at-least-once outbox publication with a revision identity. A lost commit acknowledgement stops the session; restart asks SQL what committed.

Rejected: Kafka Streams/Flink aggregation (would replace the required original algorithm), a distributed partition-worker pool (would require an additional ownership/rescaling protocol), and offset-based dedup (would miss application retries at different offsets).

Tradeoff: copying state and replacing current pane rows makes rollback and crash reasoning clear, but full snapshots/replacement amplify writes as key count grows. Performance evidence must identify this bottleneck. Incremental snapshot generations or a persistent data structure are possible future changes requiring the same crash tests, not claims about the present implementation.
