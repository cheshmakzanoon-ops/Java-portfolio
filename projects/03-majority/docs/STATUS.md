# Verification status

Majority implements a fixed three-member Raft protocol, checksummed storage, authenticated TLS transport and a real-process fault demonstration. Correctness checks cover the documented bounded model. The prescribed full performance study and repository-wide release validation remain incomplete.

## Current development checks

The combined extended/integration/mutation build passed on **2026-10-20 at 08:34:44 UTC: 76 methods, zero failures, errors or skips**. Its seven integration methods use actual TLS sockets and independent JVMs. The 1,000-seed simulator now requires a fresh post-heal command to commit and converge on every member. [Current verification evidence](../evidence/deep-verification.json) binds the 17 reviewed production sources, tests/build inputs, shared parent POM, report hashes and command to that result.

Deterministic regressions cover snapshot acknowledgments delayed across heartbeats, lost/duplicate chunks, queued execution after protocol/disk failure, shutdown under active persistence and transport-close failure, failed startup binding, uncertain WAL writes, semantic suffix replacement, immutable published snapshots, atomic client admission and unauthenticated TLS capacity recovery. The earlier checksum-valid malformed recovery fixtures remain active.

Both strict mutation gates pass at their unchanged 70% thresholds: **core 298/388 killed (76.80%); lifecycle 27/33 (81.82%)**. Across both reports, 82 survived, nine were uncovered and five timed out. These 96 non-killed outcomes remain penalized in the denominator and are individually recorded in the [current mutation evidence](../evidence/deep-mutation-survivors.json). Mutation certification covers `Raft`, `Registers`, `SnapshotAssembler` and `NodeExecutor`; it does not certify every adapter branch.

Run the complete project correctness workflow from the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/03-majority/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A03/m2" -B -ntp \
  -Pextended,integration,mutation verify
```

`extended` runs 1,000 deterministic simulation seeds with 180 fault actions per seed. `integration` runs actual TLS-process tests; those tests require OpenSSL and available loopback ports. `mutation` adds the core and lifecycle mutation passes. The repository's `check-tests.py` and `check-mutations.py` accepted the fresh reports; runtime artifact hashes, relative documentation links and stopped-process checks also passed. Unit/property tests, storage fault injection, process tests and the independent bounded history checker exercise different failure surfaces. None is an exhaustive consensus proof.

## Recorded evidence

| Scope | Recorded result | Source-bound evidence |
| --- | --- | --- |
| Protocol, storage, lifecycle and process certification, 2026-10-20 08:34 UTC | 76 methods; no failures, errors or skips; 1,000 seeds; seven TLS/JVM integration methods; both strict mutation gates above 70% | [Current certification](../evidence/deep-verification.json), [non-killed mutations](../evidence/deep-mutation-survivors.json) |
| Earlier recovery validation, 2026-10-20 07:31 UTC | 46 methods; no failures, errors or skips; three TLS-process tests | [Recovery validation](../evidence/recovery-validation.json) |
| Protocol and process recertification, 2026-10-18 | 43 methods; no failures, errors or skips; 1,000 simulator seeds; three TLS-process tests | [Recertification](../evidence/runtime-recertification-current.json) |
| Selected Raft/Registers mutations, 2026-10-18 | 261 killed / 351 total (74.36%); 79 survived, eight uncovered, three timed out | [Non-killed mutations](../evidence/mutation-recertified-survivors.json) |
| Earlier protocol/storage tests | 41 methods and documented source identity | [Historical verification](../evidence/verification-current.json) |
| Fault demonstration | Partition refusal, majority progress, restart recovery and stable retry results | [Demonstration](../evidence/demo-current.json) |
| Prepared runtime integrity | Compiled classes/resources, classpath and dependency hashes; 17 tamper/restoration checks | [Artifact cache](../evidence/runtime-artifact-cache.json) |
| Workload smoke under contention | Failed with 20 rejected/failed outcomes in the 32-client read-heavy case | [Failed smoke](../evidence/workload-smoke-failure.json) |
| Isolated workload smoke | All six 100-operation combinations completed without errors | [Isolated smoke](../evidence/workload-smoke-isolated.json) |

Historical envelopes retain their original source identity. They do not certify subsequent changes. Mutation survivors, uncovered branches and timeouts remain in the denominator; they are not represented as successful kills. [Review findings](REVIEW.md) explain the defects and corresponding regressions.

## Remaining validation

The full workload is 100,000 operations with 1-KiB values, read-heavy and mixed profiles, 1/8/32 clients and three independent runs. Execute `scripts/benchmark.sh` on a quiet host and retain raw observations, environment metadata, GC/CPU/allocation data, replication lag, snapshot duration and failure-recovery timing. The isolated smoke validates the driver only. Closed-loop latency remains subject to coordinated omission.

Clean-checkout and full-reactor verification, hosted CI and release acceptance also remain open. Native Windows and macOS behavior has not been verified. Three processes on one machine share its kernel, storage and host failure risks; availability claims are limited to the documented process/partition model.
