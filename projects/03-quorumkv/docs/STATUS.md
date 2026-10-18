# QuorumKV execution status

The original Raft core, durable storage, TLS processes and failure demonstrations are implemented and verified. Full isolated workload measurements and final release validation remain open. This is not a completed project release.

The coordinator owns the write-paused project. The verified executable/build fingerprint is `9def7ecf9345245cbe65d47303ffa6a72981dcf140cf2974d98f2a1e390ad2eb`, computed by `scripts/source-fingerprint.py`. [Current evidence](../evidence/verification-current.json) records exact commands, source identity, reports and limits.

| Acceptance | State | Evidence |
| --- | --- | --- |
| P03-A1 | PASS | Named safety regressions; 1,000 seeded schedules with 180 fault actions each and invariant checks after every event; 200 generated register cases. |
| P03-A2 | PASS | WAL framing/checksums, term/vote persistence, atomic suffix replacement, snapshot publication/fallback and corruption tests. |
| P03-A3 | PASS | Isolated reads fail, unknown mutation identities survive retry/overload, restart deduplication and CAS histories pass the independent bounded checker. |
| P03-A4 | PASS | Three actual process integration methods and two complete catalog up/down/reset/demo rounds preserve acknowledged data. The detached-launcher shutdown regression now passes. |
| P03-A5 | IN_PROGRESS | The prescribed workload driver exists; full 100,000-operation/profile/concurrency measurements and final report remain open. |

The latest extended integration run passed at 2026-10-18 01:48:25 UTC: **41 fresh test methods, no failures, errors or skips**, on effective JUnit 6.1.3. Core coverage is 758/779 lines (97.30%) and 446/528 branches (84.47%), above unchanged aggregate and selected-class gates. An obsolete XML report for an earlier verification-module `SimulationTest` remained under `target`; older summary counts that included it must not be used. Current evidence filters reports by the actual run start.

The unchanged selected Raft/Registers source passed mutation verification at 01:42:49 UTC: 262 killed, 80 survived, four timed out and eight uncovered out of 354. Strict killed percentage is 74.01%, above the unchanged 70% gate. Timeouts and uncovered cases remain in the denominator; no memory or runner errors occurred. [Every non-killed mutation](../evidence/mutation-survivors.json) is retained for review.

Two actual catalog lifecycle rounds then passed. Each started the default cluster, stopped it, reset only its seeded runtime and ran a fresh three-process fault demo. Both demos observed `UNAVAILABLE` isolated reads, `UNKNOWN_OUTCOME` isolated mutations, a five-operation history check, successful restart reads and stable duplicate receipts. Their common final digest is recorded in [demo evidence](../evidence/demo-current.json). Shared-host timing fields are diagnostic observations, not a performance baseline.

A02 independently acknowledged the snapshot/client/checker repairs and the final detached-process helper changes. The latter review explicitly inspected ownership checks, bounded Linux process-state reads and stop deadlines, all-member cleanup, and a separate launcher JVM that exits before an independent controller stops and restarts its nodes. [Review details](REVIEW.md) distinguish this scoped closure from final release approval.

## Remaining work

The first `scripts/benchmark.sh --smoke` failed under shared-host contention: its 32-client read-heavy case recorded 20 failed/rejected outcomes and ended at term 34. A member GC pause exceeded the minimum election timeout. [Failed-run evidence](../evidence/workload-smoke-failure.json) preserves the actual data; an isolated rerun and diagnosis are required before any timing change or baseline claim. Smoke remains driver validation only. In an explicitly quiet window run `scripts/benchmark.sh`: 100,000 1-KiB operations per combination, 1/8/32 clients, separate read-heavy/mixed profiles and three independent runs. Preserve raw CSV, environment, per-member/client CPU and GC, replication lag/sample failures, snapshot observations and JFR. Report closed-loop coordinated omission and variability.

Then review measured bottlenecks, complete the performance/case-study evidence, repeat from a clean checkout, build independently and in the root reactor, verify remote CI and close P03-A5. Current-instance correctness evidence does not fulfill those gates.

Historical handoff and coordinator envelopes remain under `evidence/` with their original source scope. The runtime stamp is reused only for identical source/build inputs; a working stop path remains available after source edits. The project assumes fixed three-member non-Byzantine operation and the documented POSIX flush/atomic-rename contract. It has no lease reads, dynamic membership, multi-key transactions or silent client-session expiration. Independent JVMs on one host share kernel, storage and host failures.
