# Majority

A three-member replicated key-value store built around a Raft state machine. It supports byte-key registers, compare-and-set, persistent retry identities and linearizable quorum reads. The project concentrates on persistence ordering, recovery and failure testing; it is a bounded protocol laboratory rather than a production database.

## Development

Requires Java 25, Bash, Python 3 and OpenSSL. Use the checksum-pinned Maven wrapper from the repository root. Docker and external services are not required.

The ordinary build runs unit, property, storage and protocol tests. `extended` increases deterministic simulation to 1,000 seeds; `integration` adds real TLS-process fault tests.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/03-majority/pom.xml -Dmaven.repo.local="$PWD/.work/A03/m2" -B -ntp -Pextended,integration verify
```

Run the local lifecycle separately:

```bash
./projects/03-majority/scripts/up.sh
./projects/03-majority/scripts/down.sh
./projects/03-majority/scripts/demo.sh
```

The fault demo owns its cluster. Stop an existing cluster on the selected ports before running `demo`.

`up` starts three independent JVMs on loopback TLS ports 18300–18302. `demo` uses its own fresh, ignored data directory, makes real RPCs, partitions the leader, asserts that isolated reads and writes fail, writes through the remaining majority, heals and compares state digests, snapshots, kills all JVMs, restarts them, and checks acknowledged data and retry deduplication. It also runs an independent bounded history checker. `down` preserves data; `reset-demo` removes only the default seeded runtime. Set `MAJORITY_PORT` to move the three-port range. OpenSSL is needed to generate disposable local TLS credentials.

## Design

| Module | Responsibility |
| --- | --- |
| `raft-core` | Deterministic protocol transitions, register state and fault simulator |
| `raft-storage` | Checksummed WAL transactions and atomic snapshot generations |
| `raft-transport` | Bounded binary framing, TLS and role authentication |
| `kv-server` | Event ownership, persistence scheduling and administration |
| `kv-client` | Request correlation and stable mutation retries |
| `raft-verification` | Independent history checker and real-process fault driver |

The core emits `Persist`, `Send`, `Reply` and `Applied` effects. Snapshot assembly and protocol/disk execution have separate owners, making retransmission state and terminal failure barriers independently testable. Persistence effects suspend dependent messages and application until the matching disk acknowledgment. A virtual-time simulator controls independent network and stable-storage schedules, including loss, duplication, reordering, partitions, pauses, crash and restart. WAL transactions replace suffixes atomically through append-only checksummed records. Snapshot publication flushes a verified temporary snapshot and a fresh WAL generation, then atomically replaces and flushes the generation manifest.

Members run with **pre-vote** (an unreachable member cannot inflate the cluster's term or depose a healthy leader when it returns), **check-quorum** (a leader that loses its majority steps down instead of lingering) and an operator-driven **leadership transfer** (`transfer <member>` hands over to a caught-up follower with exactly one new term and a brief write pause). The simulator runs every seeded fault schedule in both the classic and the hardened mode, each hardened scenario has a classic control that shows the behaviour removed, and the three-JVM demo asserts all of it over real TLS; see [architecture](docs/ARCHITECTURE.md#liveness-hardening-pre-vote-check-quorum-and-leadership-transfer).

Read [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [operations](docs/OPERATIONS.md), [security](docs/SECURITY.md), [performance methodology](docs/PERFORMANCE.md), and the current [evidence/status](docs/STATUS.md). Tests are bounded exploration, not a proof of universal consensus correctness. Three JVMs on one host share host, kernel and physical storage failure risks.

## Operating limits

Keys 256 bytes; values 4 KiB; retained key/value bytes 16 MiB and 16,384 keys; 1,024 persistent client identities; 256 pending mutations/reads/events; 32 entries per append; 512 KiB frames; 16 KiB snapshot chunks; 24 MiB snapshot envelope; 64 inbound TLS connections per member. Sessions have no silent expiration or garbage collection. No multi-key transactions, TTL, dynamic membership, Byzantine tolerance, clock leases or cross-region claims.

## Performance

The prescribed workload runs through `scripts/benchmark.sh` (100,000 1-KiB operations per combination, 1/8/32 clients, read-heavy/mixed, three runs). `--smoke` is a separate 100-operation functional workload and cannot satisfy the prescribed performance workload. Consult the evidence status for what has actually run.
