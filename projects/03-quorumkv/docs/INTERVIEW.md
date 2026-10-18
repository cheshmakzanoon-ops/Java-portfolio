# QuorumKV walkthrough

Start with the guarantee: three fixed, mutually authenticated members replicate a byte-key register with compare-and-set and persistent client identities. A successful mutation has a durable majority prefix. Reads require a fresh majority exchange in the current term; an isolated former leader cannot serve a successful read. This is a bounded protocol laboratory on one Linux host, with shared kernel and storage failure risks.

## Follow one request

1. `kv-client/KvClient` keeps an outstanding mutation identity until a definitive result resolves it. An admission rejection after a timeout does not erase that identity. A retry must preserve both sequence and payload.
2. `raft-core/Raft` owns the protocol state on one thread. It emits explicit effects rather than doing network or disk I/O. Explain the term, vote, log, commit and applied positions before describing the happy path.
3. A `Persist` effect suspends dependent messages and application until the corresponding storage acknowledgment. Show the named regression for applying an entry before its persistence acknowledgment and the FIFO deferred-event drain test.
4. `raft-storage` appends checksummed transactions, forces them, and acknowledges the effect. Recovery validates the durable prefix; corruption is visible and is not silently treated as a torn tail.
5. The leader advances commit only with the required quorum and current-term condition. `Registers` applies the entry and retains the client sequence, command digest and result in the replicated snapshot state.
6. A read collects current-term majority confirmation, waits for the required applied position and returns the committed register value. Pending reads expire with `UNAVAILABLE`; expiry also releases their capacity.

## Explain recovery without hand-waving

A snapshot contains the last included index and term, fixed membership, register contents and deduplication sessions. Publication writes and verifies a temporary snapshot, flushes the new recovery artifacts, then atomically publishes and flushes the manifest under the documented POSIX filesystem contract. The old recovery path remains available until the new one is durable. Chunk transfer validates offsets and identity before activation. A newer snapshot resets per-peer transfer state; stale responses cannot advance the replacement transfer.

The real process demonstration partitions the leader, checks unavailable reads and unknown write outcomes, writes through the majority, heals, compares digests, snapshots, kills and restarts all members, then checks acknowledged data and duplicate receipts. Show the actual demo result envelope and its source identity. Shared-host recovery timing is diagnostic until measured in an isolated workload run.

## Discuss the bugs that shaped the design

- Seed 27 exposed application before disk acknowledgment. The regression makes the persistence barrier observable.
- Seed 417 exposed rejected vote requests resetting the election deadline. A rejected request must not indefinitely postpone a legitimate election.
- A snapshot replacement could retain the old transfer offset. Named two-snapshot and follower-restart cases now exercise reset and stale-response behavior.
- A timeout followed by pre-admission overload could erase a client's uncertain mutation identity. `MutationIdentity` keeps the retry stable.
- The independent history checker once conflated absent values with the literal bytes for `null`. Its memo key now represents these states separately.
- The standalone stop path encountered operating-system truncation of long Java command lines. It now verifies owned process identity using full argument metadata or a bounded Linux `/proc` fallback; it refuses unrelated PIDs.

## Show evidence and its limits

Use `docs/STATUS.md` and the linked evidence rather than quoting a stale count from this walkthrough. The extended simulator uses 1,000 deterministic seeds and checks invariants after each of 180 fault actions per seed. Generated register histories, WAL/corruption tests, actual TLS processes and an independent bounded checker exercise different failure surfaces. These observations do not prove all possible schedules safe. Keep surviving and timed-out mutations visible, including the selected classes and denominator.

The system workload is 100,000 operations with 1-KiB values, separate read-heavy and mixed profiles, 1/8/32 clients and three runs. Explain the measured TLS/replicated-fsync/quorum-read boundary, closed-loop coordinated omission, storage assumptions, replication lag and recovery measurements. An engine timing is not an end-to-end service result.

Finish with deliberate limits: fixed membership, no Byzantine tolerance, no lease reads, no cross-key transactions, no client-session garbage collection and no process-independent automatic retry identity. Callers must durably retain an outstanding identity across their own restart. Keys, values, sessions, retained data, queues and frames all have explicit caps; capacity exhaustion is visible.
