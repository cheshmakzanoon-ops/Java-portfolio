# Verification status

QuorumKV implements a fixed three-member Raft protocol, checksummed storage, authenticated TLS transport and a real-process fault demonstration. Correctness checks cover the documented bounded model. The prescribed full performance study and repository-wide release validation remain incomplete.

## Current development checks

The recovery validation review adds checksum-valid adversarial fixtures for duplicate client receipts, invalid client identifiers, nonpositive sequences, short digests, uncommitted outcome codes and impossible snapshot/WAL coordinates. These checks protect retry identity across recovery rather than relying on checksums alone. The full extended/integration build passed on 2026-10-20 at 07:31:15 UTC: **46 methods, zero failures, errors or skips**, including all three TLS-process tests and 1,000 deterministic simulation seeds. [Recovery validation evidence](../evidence/recovery-validation.json) binds the reviewed source, command, suite results and log hash. Current-source mutation testing was not rerun; the older mutation report below remains historical.

Run the complete project correctness workflow from the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/03-quorumkv/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A03/m2" -B -ntp \
  -Pextended,integration verify
```

`extended` runs 1,000 deterministic simulation seeds with 180 fault actions per seed. `integration` runs actual TLS-process tests; those tests require OpenSSL and available loopback ports, not Docker. Unit/property tests, storage fault injection, process tests and the independent bounded history checker exercise different failure surfaces. None is an exhaustive consensus proof.

## Recorded evidence

| Scope | Recorded result | Source-bound evidence |
| --- | --- | --- |
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
