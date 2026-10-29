# Operations and recovery

## Supported environment

Use Java 25, Bash, Python 3 and OpenSSL on a local POSIX/Linux filesystem that supports directory `FileChannel.force` and atomic same-directory rename. Devices/controllers must honor flushes. Network filesystems, unprotected volatile device caches, Windows rename behavior and whole-host survival are outside verified support.

The default runtime is `.work/A03/runtime`. `MAJORITY_RUNTIME` selects another durable directory; `MAJORITY_PORT` selects the first of three consecutive loopback ports (default 18300–18302). `MAJORITY_M2` selects the Maven cache. Never start two clusters on the same member directories or ports.

```bash
source scripts/cloud-env.sh
./scripts/portfolio.sh up majority
./scripts/portfolio.sh down majority
./scripts/portfolio.sh demo majority
```

The fault demo owns a separate fresh directory and terminates its JVMs. Stop the ordinary cluster before running it on the same ports. `down` preserves durable state and verifies process identity before terminating a member; it refuses unrelated PIDs. `reset-demo` is destructive and accepts only the default seeded runtime. It is not a recovery command.

TLS setup stages a disposable certificate/key and role credentials before publication. An interrupted certificate generator cannot publish a completed credential set; retrying a partial older setup preserves existing role credentials. Helpers have enforced deadlines and timed-out children are terminated. These credentials are for trusted local demonstration, not production PKI or remote deployment.

## What client outcomes mean

| Outcome | Interpretation and next action |
| --- | --- |
| `OK` | The mutation has reached a stable majority, the leader persisted its commit position, and the deterministic result was applied. A GET additionally completed a fresh current-term quorum barrier. |
| `CAS_MISMATCH` | A committed failed CAS; its observed value and receipt survive retry/recovery. Advance to the next sequence only after accepting that definitive result. |
| `NOT_LEADER` | Retry using the supplied leader hint when valid. A local status role is not a substitute for a quorum operation. |
| `UNAVAILABLE`, `UNKNOWN_OUTCOME`, timeout or connection loss | An admitted mutation may still have committed. Preserve identity, sequence and the exact payload; retry it. Close/shutdown unavailability does not prove nonexecution. |
| `RESOURCE_EXHAUSTED` | A bound prevented progress. Retain any previously uncertain mutation identity. Investigate client concurrency, pending work, retained state and session capacity before retrying. |
| `IDENTITY_CONFLICT`, `STALE_SEQUENCE`, `SEQUENCE_GAP` | Inspect the client identity journal. Do not invent a replacement sequence or assume that an earlier command was absent. |

The shipped client's retry identity is process-local. Applications needing retry continuity across their own restart must durably preserve the outstanding command externally. Server-side receipts survive snapshots and member restarts; no session expiration or garbage collection is provided.

## Failure matrix

| Failure | Expected behavior | Operational response |
| --- | --- | --- |
| Startup cannot load TLS or bind its port | Construction/startup reports the original failure and attempts all owned cleanup; later cleanup errors are suppressed. | Resolve the certificate/port conflict, inspect any suppressed cleanup failure, then retry. |
| One member stops or is partitioned | A timely majority can elect/commit. The isolated leader cannot acknowledge new quorum operations and steps down within about 500 ms; an isolated follower keeps its term (pre-vote), so it joins quietly on return. | Restore connectivity or restart the member; confirm quorum operations, not only status. |
| Planned leader maintenance | `transfer` hands leadership to a named, caught-up member in tens of milliseconds, with exactly one new term. Writes pause for that interval. | Run the transfer, wait for its OK, then stop the old leader. A `TRANSFER_TIMEOUT` changes nothing; diagnose the target and retry. |
| Reply is lost after commit | Client sees uncertainty; the repeated latest identity returns its committed result. | Retry the unchanged command. |
| Snapshot reply takes several heartbeat periods | The same outstanding chunk is retried; delayed/duplicate replies remain correlated. | Allow transfer to finish; inspect storage/network errors if it cannot progress. |
| Member crashes during a snapshot | Manifest still selects the old complete generation or the newly published complete generation. Partial transfer state is never active. | Restart against the retained directory. Preserve all artifacts on any validation failure. |
| Incomplete final WAL header/body | Recovery truncates only the structurally incomplete tail. | Retain the recovery diagnosis; do not generalize this rule to complete corrupt records. |
| Complete record CRC, manifest or snapshot mismatch | Startup refuses the ambiguous state. | Stop and preserve the directory. Rejoin from a healthy majority using a fresh member directory. |
| Disk write/flush fails | The journal is poisoned; queued protocol work cannot resume after terminal failure. | Restore capacity/storage health, close the failed node and reopen/recover. No in-process retry on the failed writer. |
| Shutdown overlaps persistence | Admission closes; queued work is abandoned, admitted writes finish and no late success is emitted. | Treat interrupted client outcomes as unknown and retry after restart. |
| TLS peers connect without authenticating | Handshake/first authenticated frame must complete within three seconds; stalled channels release inbound capacity. | Inspect local resource pressure or credential mismatch; do not disable certificate verification. |
| Shutdown deadline or process identity check fails | Cleanup reports failure instead of claiming the member stopped. | Inspect that exact owned process and its logs; never kill an unrelated PID or delete a live member's files. |

## Rejoin one damaged member

This example preserves the failed member and rejoins node 1 from the two healthy retained members. It requires a verified healthy majority; it is not a method for reconstructing data after losing the majority.

```bash
./scripts/portfolio.sh down majority
runtime="$PWD/.work/A03/runtime"
# Preserve the entire failed member directory; choose an unused archive name.
mv "$runtime/node-1" "$runtime/node-1.failed-$(date -u +%Y%m%dT%H%M%SZ)"
./scripts/portfolio.sh up majority
```

Keep `tls/` and the healthy node directories unchanged. A new node-1 directory starts empty and catches up through authenticated snapshot/append replication. Do not delete a corrupt record, copy a selected WAL suffix, or mark retained entries committed manually. If more than one member is damaged, stop and assess a coordinated backup rather than treating an arbitrary surviving suffix as authoritative.

## Storage and diagnosis

[Journal](../raft-storage/src/main/java/io/github/cheshmakzanoon/portfolio/majority/Journal.java) holds one directory lock. Each transaction calls `force(true)`. Snapshot publication verifies/flushed temporary content, publishes a fresh WAL generation, flushes the directory, then atomically publishes and flushes the manifest. Old recovery artifacts retire only after publication is durable. WAL generations rotate after 8 MiB; records are capped at 64 MiB. Monitor free space before workloads and preserve complete generations when backing up.

Administrative requests use the separate administration role credential (the demo's `ClusterTool` shows the calls); `status`, `snapshot`, `partition`, `heal` and `transfer <member>` are the actions. This revision adds RPC tags 7–9 to wire version 1 and needs no storage migration, but members must be upgraded together: an older member rejects the new messages and drops the connection.

Status exposes node/role/leader/term/commit/applied/snapshot positions, state digest, disk barrier state, pending requests and snapshot/flush counters. `commit` and `applied` should converge after recovery, but digests/status alone cannot establish current read authority. `FAIL_CLOSED` identifies a terminal protocol or persistence failure; diagnose the first message and corresponding member log before restarting.

Back up a stopped member's complete directory or a coordinated stable generation. Copying a live WAL without synchronization is unsupported. The storage and wire versions remain 1, so this revision needs no data migration. New validation may refuse previously accepted malformed or internally inconsistent state; preserve it for diagnosis rather than editing it to pass.

The workload accepts 1–100,000 operations and one to three runs, with a fresh output directory. Its launcher creates that directory automatically. Existing measurement metadata/results are never overwritten by a new direct invocation. Full isolated measurements remain separate from functional verification; see [performance](PERFORMANCE.md).
