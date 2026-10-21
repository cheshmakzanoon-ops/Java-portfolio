# Verification status

Late Arrival implements bounded event-time processing, atomic SQL recovery, fenced run ownership, tenant-authorized queries, and at-least-once output delivery. Historical measurements passed within their recorded scope. Final clean-checkout and repository release validation remain incomplete.

## Current verification, 2026-10-20

Project integration verification passed at **08:59:08 UTC** with **48 methods: 37 ordinary and 11 real Kafka/PostgreSQL integration, zero failures, errors, or skips**. The property suites also generated 120 event-time histories and 80 independent progress/control histories. [Current application evidence](../evidence/engineering-verification-2026-10-20.json) records source/POM hashes, individual methods, image identities, and execution limits.

The current suite adds explicit transaction-outcome, compatible filtered snapshot, bounded owner-thread termination, poll-admission accounting, SQL-readiness, partial output publication, and interrupted in-flight shutdown regressions. The backpressure fixture now commits the dequeued batch through SQL before acknowledging it. Its recovered checkpoint is independently checked at 20 and its tumbling aggregate at count 10, sum 145, minimum 10, maximum 19. Output rollback/retry preserves stable revision identities; interrupted shutdown retains the acknowledged checkpoint and releases ownership only after engine termination.

```bash
source scripts/cloud-env.sh
export PORTFOLIO_TEST_RUN_STARTED="$(date +%s)"
./mvnw -f projects/04-latearrival/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A04/m2" -B -ntp \
  -Pintegration,mutation verify
python3 scripts/check-tests.py projects/04-latearrival integration
python3 scripts/check-mutations.py projects/04-latearrival
python3 projects/04-latearrival/scripts/test-lifecycle.py
```

The actual successful functional command used `-Pintegration verify`; mutation passed earlier against identical selected module source/POM inputs. These are separate observations. [Targeted mutation evidence](../evidence/engineering-mutation-2026-10-20.json) retains every non-killed outcome and the original overall-command failure boundary.

| Current mutation scope | Strict killed count | Other outcomes | Required kill rate |
| --- | --- | --- | --- |
| Engine | 131/133, 98.50% | One boundary survivor and one arithmetic timeout | 70% |
| Event decoder and owner-thread termination | 32/34, 94.12% | One deadline-boundary survivor and one removed-join timeout | 70% |
| SQL transactions and snapshot codec | 46/52, 88.46% | Four survivors and two skip-loop timeouts | 70% |

All outcomes remain in the denominator; no timeout is presented as killed. No uncovered mutants, runner errors, or memory errors remained in these targets. Storage survivors remove a flush/cleanup warning or change inclusive byte/skip boundaries. The analysis does not cover every production class. Existing engine coverage gates remain 85% line and 75% branch; existing mutation thresholds were retained. An earlier expanded-target attempt exposed weak transaction/filter/termination boundaries and a termination-test fixture that assumed one JVM thread state; the final targets passed after deterministic regressions and a fixture accepting both blocked waiting states.

## Functional failures and execution boundary

The preceding combined integration/mutation command ended at **08:50:50 UTC** with mutation gates passing but integration reporting nine passes, one failure, and one error. The new one-in-flight guard exposed the old dequeue-without-acknowledgement fixture. Separately, the broker logged a controller-heartbeat RPC timeout at 08:50:13.248 and removal of partition fetchers at 08:50:16.947 before a crash-seed child's unchanged 15-second metadata deadline expired. [Failure evidence](../evidence/engineering-failed-integration-2026-10-20.json) retains the timeline and limits. The fixture now exports broker logs before teardown.

The later passing run paused other project Maven jobs and retained every existing budget. This isolates a functional observation; it neither proves host contention caused the earlier broker failure nor establishes sustainable load capacity. PostgreSQL 18.6-alpine and Kafka 4.2.2 are pinned by digest in current fixtures. Seven upstream Kafka image HIGH findings and PostgreSQL's separately reported `gosu` Go advisories remain distinct from the clean application-library scan boundary. No performance study was rerun for the current code/dependencies/images.

## Local lifecycle ownership, 2026-10-20

Twenty-three process/control-adapter regressions passed in 86.347 seconds. [Scoped evidence](../evidence/lifecycle-ownership-2026-10-20.json) binds the lifecycle scripts and tests. Actual child processes cover exact kernel/artifact/complete-argument identity, restricted procfs observations, resistant termination, interruption, stale-source/artifact reuse, and retained group identity through the last cleanup signal. Controlled Compose/readiness adapters cover resource rollback, preexisting running/stopped states, retained volume markers, and preserved primary failures. These tests do not replace real Kafka/PostgreSQL recovery or a live stack lifecycle.

The current-image working-checkout lifecycle passed startup, the seven-barrier [recovery demo](../evidence/engineering-demo-2026-10-20.json), three documented query checks, and shutdown. The first tumbling result was independently checked at count 3, sum 35, minimum 5, maximum 20, and finality; status retained one duplicate and quarantine was empty. Recovery and oracle digests matched. [Lifecycle evidence](../evidence/engineering-lifecycle-2026-10-20.json) records pinned image identities, exact current-runtime ownership checks, verified process exit, zero running project containers, and retained named volumes. Historical `demo.json` and `demo-output.txt` were preserved byte for byte. The current ordinary preparation repeated 37 methods successfully at 09:30:44 UTC; those repeats are not additional unique tests.

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
