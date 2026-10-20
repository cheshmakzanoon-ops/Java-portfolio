# Verification status

PulseStream implements bounded event-time processing, atomic SQL recovery, fenced run ownership, tenant-authorized queries, and at-least-once output delivery. Historical measurements passed within their recorded scope. Final clean-checkout and repository release validation remain incomplete.

## Current maintenance verification

On 2026-10-20, formatting and full project integration verification passed **26 methods: 17 ordinary and 9 real Kafka/PostgreSQL integration, with zero failures, errors, or skips**. The wire decoder now rejects an explicitly supplied `recordType` unless it is a recognized string. Two regressions cover malformed types, checkpoint/quarantine behavior without aggregation, and compatibility with legacy records that omit the field.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/04-pulsestream/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply verify
./mvnw -f projects/04-pulsestream/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp \
  -Pintegration verify
```

The new Kafka/PostgreSQL regression passed: three malformed records were durably quarantined, a valid retry retained its event identity, and source checkpoint/aggregate state survived a fresh session restore. The suite completed at 07:40:45 UTC and its containers were cleaned up. Mutation analysis and performance studies were not rerun; their historical results below retain their recorded source identities.

## Historical correctness evidence

The [2026-10-18 recertification packet](../evidence/recertification-20261018/README.md) binds exact source, test reports, dependencies, and runtime inputs. Its source fingerprint is `working-tree-sha256:2f7d000815cce016675463dcac431f93332bf2f4049a0b19a1330391fda940fc`.

| Check | Observed outcome | Boundary |
| --- | --- | --- |
| Cold application verification | 23 methods: 15 ordinary/property and 8 Kafka/PostgreSQL/process/JWT integration; no failures, errors, or skips | Actual JUnit 6.1.3; missing Docker fails integration |
| Engine coverage | Enforced 85% line and 75% branch gates passed | No exclusions added to obtain a pass |
| Selected engine mutation analysis | 131 killed of 133 outcomes: one survivor and one timeout; no runner/memory errors | The timeout remains in the denominator and is not counted as a strict kill |
| Runtime preparation | 15 ordinary methods repeated; JMH discovery succeeded | Discovery executed no benchmark iterations |
| Artifact comparison | 44 main classes, compared resources, and 6 JARs unchanged; 129 runtime inputs bound | Observed for those artifacts, not a general cross-machine reproducibility claim |

Repeated engine and preparation methods overlap the 23-method application suite and are not additional unique tests. Earlier reports retain their own source identities, including an initial mutation result containing a memory error and timeout.

| Acceptance | Demonstrated historical scope | Release boundary |
| --- | --- | --- |
| P04-A1 | Independent pane/membership oracle over 120 seeded histories and independent progress/control schedules over 80 histories | Current ordinary and integration suites passed; clean-checkout reproduction remains |
| P04-A2 | Finality/retention edges, late revisions, pause/backlog/all-idle/resume/restart, monotonic watermark | Bounded schedules, not an exhaustive protocol proof |
| P04-A3 | Process termination before SQL, after SQL before mirroring, and during outbox; lease expiry and obsolete-owner rejection | Real Kafka/PostgreSQL fixtures |
| P04-A4 | Dedup/conflicts, finite quotas, unavailable database, retention gaps, topic recreation, partition-growth refusal, slow-delivery lease renewal | Dedup persists until explicit run archival |
| P04-A5 | Deterministic lifecycle plus three million-event durable runs and separate engine JMH | Historical source; final artifact/release review remains separate |

## Historical lifecycle and storage

The corrected deterministic demo asserted first-window count 3, sum 35, finality, one duplicate, one rejected late tumbling contribution, separate sliding outcomes, and the independent oracle digest. Startup, scoped reset, repeat demo, and owned teardown passed.

Kafka's explicit `/var/lib/kafka/data` setting matches its named-volume mount. Recreating Kafka and PostgreSQL preserved topic UUID `c16zn4DtS8GeyvY7fBD_Rg`, SQL next offsets 3/7, and independently checked result digest `0050e36db52d262196924b3b278eb13e549e8cfd6cacca8b3d8e96d6d423eff6`. See `evidence/container-recreation.json`. This demonstrates orderly recreation with retained local volumes, not physical power-loss durability.

## Historical performance studies

The [quiet12 study](../evidence/reference-20261018-quiet12/README.md) ran on 2026-10-18 from 07:08:40 to 08:09:12 UTC. It completed original/current three-fork engine JMH and three separate one-million-measurement durable runs, each including 1,000 duplicates. All six workload assertions passed in every round; the runner and scoped teardown exited zero.

Durable drain rates were 777.722, 796.047, and 1,055.710 accepted events/s. Their arithmetic mean was 876.493 events/s with sample CV 17.739%; three million events divided by total drain time was 859.785 events/s. Per-round checkpoint p99 was 5,014.970, 3,674.227, and 2,680.853 ms. These separate quantiles cannot be pooled into one global percentile. Closed-loop latency excludes offered-load queue waiting. All 25 indexed raw files, totaling 70,859,616 bytes, retain their recorded hashes.

The original/current fresh-engine JMH mean ratio was 6.646 with high fork variability. It does not establish a corresponding durable-service speedup. Each durable run retained its full JFR. The original JMH profiler reused a pathname, leaving one recording per variant despite retaining all fork numeric samples. The [semantic addendum](../evidence/reference-20261018-quiet12-jfr-addendum/README.md) subsequently inspected all five retained recordings through 16 bounded official JDK commands.

A separate [quiet14 capture check](../evidence/capture-20261018-quiet14/README.md) verified PID-named recordings for all six forks after a script-only change. It preserved compiled application bytes and runtime hashes. Its profiling boundary and scores remain separate from quiet12; it does not replace or relabel the durable measurements.

The earlier quiet10 attempt failed during dependency startup on a permission-denied process-namespace read, after its engine phases but before a full durable run. Its original outputs remain preserved and contribute no rows to quiet12. The subsequent process-supervision repair and its control tests are separate from application/performance evidence.

## Measured assertion limits

Recovery equality covers a durable **semantic projection**: watermark/duplicates, offsets/maxima, dedup, panes, nonfinal results, pending windows, and admitted keys. It excludes rebased activity/idle fields, run identity/limits, late counters, quarantine, outbox, and finalized results. It is not full serialized-snapshot equality.

The independent SQL oracle checks row count, key, window end, aggregates, and finality, requiring `revision >= 1`. It does not independently derive exact revision values or sequences. Original raw labels that overstate these boundaries remain preserved with a separate [scope clarification](../evidence/reference-20261018-quiet12/scope-clarifications.json).

## Remaining checks

Review the final measured-artifact packet, reproduce the current demo from a clean checkout, and complete repository/hosted release gates. Historical mutation and performance outcomes identify their tested versions. Same-version snapshot restoration is supported; rolling migration and distributed rescaling remain outside scope. See [performance](PERFORMANCE.md), [operations](OPERATIONS.md), and [review](REVIEW.md).
