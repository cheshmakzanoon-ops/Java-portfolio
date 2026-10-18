# QuorumKV

An educational, original Raft implementation with three fixed members, durable byte-key registers, compare-and-set, replay-safe client identities, and linearizable quorum reads. This is a protocol laboratory, not a production database. No consensus or database engine library implements its core.

Use Java 25 and the repository wrapper:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/03-quorumkv/pom.xml -Dmaven.repo.local="$PWD/.work/A03/m2" -B -ntp verify
./mvnw -f projects/03-quorumkv/pom.xml -Dmaven.repo.local="$PWD/.work/A03/m2" -B -ntp -Pextended verify
./projects/03-quorumkv/scripts/up.sh
./projects/03-quorumkv/scripts/demo.sh
./projects/03-quorumkv/scripts/down.sh
```

`up` starts three independent JVMs on loopback TLS ports 18300–18302. `demo` uses its own fresh, ignored data directory, makes real RPCs, partitions the leader, asserts that isolated reads and writes fail, writes through the remaining majority, heals and compares state digests, snapshots, kills all JVMs, restarts them, and checks acknowledged data and retry deduplication. It also runs an independent bounded history checker. `down` preserves data; `reset-demo` removes only the default seeded runtime. Set `QUORUMKV_PORT` to move the three-port range. OpenSSL is needed to generate disposable local TLS credentials.

The core emits `Persist`, `Send`, `Reply` and `Applied` effects. Persistence effects suspend dependent messages and application until the matching disk acknowledgment. A virtual-time simulator controls independent network and stable-storage schedules, including loss, duplication, reordering, partitions, pauses, crash and restart. WAL transactions replace suffixes atomically through append-only checksummed records. Snapshot publication flushes a verified temporary snapshot and a fresh WAL generation, then atomically replaces and flushes the generation manifest.

Read [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [operations](docs/OPERATIONS.md), [security](docs/SECURITY.md), [performance methodology](docs/PERFORMANCE.md), and the current [evidence/status](docs/STATUS.md). Tests are bounded exploration, not a proof of universal consensus correctness. Three JVMs on one host share host, kernel and physical storage failure risks.

Limits: keys 256 bytes; values 4 KiB; retained key/value bytes 16 MiB and 16,384 keys; 1,024 persistent client identities; 256 pending mutations/reads/events; 32 entries per append; 512 KiB frames; 16 KiB snapshot chunks; 24 MiB snapshot envelope; 64 inbound TLS connections per member. Sessions have no silent expiration or garbage collection. No multi-key transactions, TTL, dynamic membership, Byzantine tolerance, clock leases or cross-region claims.

Performance is measured through `scripts/benchmark.sh` (100,000 1-KiB operations per combination, 1/8/32 clients, read-heavy/mixed, three runs). `--smoke` is a separate 100-operation functional workload and cannot satisfy the prescribed performance workload. Consult the evidence status for what has actually run.
