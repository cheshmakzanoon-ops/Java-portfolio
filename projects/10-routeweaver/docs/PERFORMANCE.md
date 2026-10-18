# RouteWeaver performance protocol and evidence

No large-instance optimum or uniform speedup is promised. Feasibility, physical cargo/freeze preservation and same-input incumbent nondegradation are mandatory. The proposed 10% operating-cost reduction applies only to the preregistered deliberately improvable fixture within a 30-second budget. The completed reference run met it on that fixture in all three outcomes; the measured limits and remaining release gates are recorded below.

## Workload and boundaries

Fixtures.problem generates synthetic directed matrices and delivery jobs from explicit seeds. Corpus instances 0–8 cover easy, tight and deliberately overconstrained cases for 100 jobs/5 vehicles, 250/10 and 500/25, using seeds 4200–4208. Instance 9 uses seed 4209 and an asymmetric seconds-versus-meters trap: every off-diagonal arc takes one second; ascending preferred arcs cost one meter and other arcs 1000 meters. The fixture was declared before measurement. It is not evidence that arbitrary routing inputs improve similarly.

Each input has three real runs, solver seed 42, a 30,000 ms budget and a 100,000-step safety cap. The durable worker's budget covers baseline plus improvement. The system is closed-loop at concurrency one; this measures a queue with one offered job at a time and cannot establish overload latency or avoid coordinated omission under arbitrary load. No system warmup is excluded: run 0 includes cold/JIT effects and is reported separately from runs 1/2. Actual durations and stop conditions are retained, including early step termination if it occurs.

System comparison uses the actual independently validated baseline persisted by that same job, under the same input and budget. An unbounded client baseline can have more assigned demand and is not silently substituted. Only equal assigned-demand outcomes receive a scalar operating-cost improvement fraction; all runs retain the full lexicographic score, unassigned count and identity. Across breakdown revisions, raw score changes are different workloads and are never called optimization gains.

The driver records actual HTTP problem creation, durable queue latency, worker claim-to-terminal duration, first durable baseline timing, score/time traces, feasibility, unassigned work and client used-heap observations. HTTP polling/serialization is distinct from worker time. Client heap is not peak service memory. A one-second /proc sampler records service user/system CPU, RSS/high-water RSS and thread count; both client and service GC logs are preserved. These are observed resource samples, not proof of every instantaneous allocation or peak.

CPU validation throughput has a separate JMH benchmark, ValidationBenchmark.fullReferenceValidation: complete independently feasible route sets with all 100/250/500 jobs, directed return arcs, loads, windows, score and partition. Fixture construction and feasibility assertions occur in trial setup. JMH consumes the returned immutable Validation, with three forks, three one-second warmups and three one-second measurements, -Xms512m/-Xmx768m and GC profiler. A second benchmark, immutableProblemImportValidation, measures strict JSON parsing, bounded immutable model construction and canonical hashing of the complete matrix/jobs/vehicles. The serialized input is prepared outside measurement. These are distinct in-process route-evaluator and problem-import boundaries, never end-to-end dispatch latency.

## Reproduction

Build the benchmark artifact without running a measurement:

```sh
source scripts/cloud-env.sh
./mvnw -f projects/10-routeweaver/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A10/m2 -B -ntp -Pbenchmarks package
```

During an explicitly reserved quiet host window, after current-source verification:

```sh
projects/10-routeweaver/scripts/up.sh
projects/10-routeweaver/scripts/benchmark.sh
projects/10-routeweaver/scripts/down.sh
```

The script checks the source stamp and health, records real environment/JVM/cgroup details, runs JMH and all 30 system solves, and propagates failures. Default raw output is a fresh .runtime/benchmark/run-<UTC> directory (existing result files are never overwritten): corpus-raw.json, validation-jmh.json/log, client gc.log, server-gc.log, server-resources.jsonl and server-process-status.txt. Compact source/environment-linked results belong in evidence; raw JFR/logs/JARs do not belong in Git. Use a fresh/reset demo tenant before repeating corpus problem IDs. Never prune unrelated volumes.

Reference host currently observed: Intel Xeon Platinum 8573C, Linux x86_64, a four-CPU cgroup quota and 32 GiB memory limit; Temurin 25.0.4.1+1. Maven 3.10.0, Timefold 2.7.1, Spring Boot 4.1.1, Jackson 2.21.5 and PostgreSQL 18.3 Alpine digest are pinned. Latest root JUnit is 6.1.3; earlier historical tests used 6.0.3. Every actual run must capture the environment again and identify other containers/processes and source/root POM fingerprint.

## Diagnosed baseline bottleneck

A shared-host preparation diagnostic counted stop checks, not certified throughput. At 100 overconstrained jobs there were 2,751 checks during a completed roughly 2.1-second baseline; five-second slices at 250 and 500 jobs made 3,204 and 3,160 checks. Previously each check opened a JDBC transaction. Those wall times are noisy preparation observations, not before/after performance claims. Raw: .work/A10/poll-count-diagnostic.log.

CancellationPoll now samples database cancellation at most once per 100 ms and latches cancellation. Deadline/interruption checks remain on every CPU trial. Unit tests use a deterministic clock to prove bounded reads, immediate first check, cancellation latch and nanoTime wrap; authoritative database writes still recheck lease/fence/state. The 53-method reactor at source 2aa7d7da included the real PostgreSQL gate for this change. The subsequent actual pooled-JDBC diagnosis completed three pairs with independently identical valid scores: 2,743 reads in each raw run versus 4, 11 and 2 reads with sampling. Raw durations were 2.870/8.661/5.418 seconds versus 0.327/1.243/0.197 seconds. Shared CPU and cache conditions were uncontrolled, so these timings diagnose unnecessary IO and do not certify a portable speedup. Exact counts, hashes and raw paths are in evidence/polling-diagnostic.json and evidence/live.json. scripts/poll-diagnostic.sh runs three raw/sampled pairs on the same 100-job overconstrained input with the service stopped, checks identical independently valid baseline scores, and records real JDBC read counts/timings. It requires the owned PostgreSQL reservation and an idle database; its shared-host timings are diagnosis, not accepted quiet-host performance. Candidate/reference validation and full directed evaluation remain intact; no physical constraint was dropped for speed.

The earlier unrun measurement checkpoint is superseded by the completed reference run below. Shared-host diagnosis remains separate from the accepted quiet reference measurements.

## Observed quiet reference run

The coordinator completed the full reserved run on 18 October 2026 from 09:19:15.646 to 09:35:26.133 UTC, an elapsed 970.488 seconds. All three build locks were held; the reservation recorded no other Java processes or application containers at admission or completion. Source fingerprint d5964723… and Git revision 49d23dae48eeffb8939730a953cd803314b8dc5a identify the measured binaries. The [root receipt](../../../docs/execution/performance-reservations/017-routeweaver.json) records the independent stopped check. The [version 1 packet](../evidence/performance-v1/packet.json) binds artifact hashes, all 30 outcomes, JMH forks, resources and raw files.

Every result has zero hard violations and a same-input score no worse than its actual persisted baseline: 21 results are FULLY_FEASIBLE and nine are PARTIAL_FEASIBLE. All 1,483 authoritative score/time trace points are ordered and nondegrading, with baseline/final endpoints matching. No partial result is described as serving all demand. The scalar operating-cost comparison is omitted when assigned demand changed.

The following tables retain runs 0 / 1 / 2 in order. Baselines happened to match across the three runs of each instance. Every hard and disruption score is zero; the operating score uses the declared weights of one per meter and one per travel second, while disruption has weight 1,000. Weighted unassigned priority is a separate lexicographic level.

| Instance and difficulty | Jobs / vehicles | Baseline priority; operating | Final priority, runs 0 / 1 / 2 | Final operating, runs 0 / 1 / 2 | Unassigned count, runs 0 / 1 / 2 | Cost gain %, runs 0 / 1 / 2 |
| --- | --- | --- | --- | --- | --- | --- |
| 0: easy | 100 / 5 | 0; 234,796 | 0 / 0 / 0 | 180,015 / 175,532 / 191,530 | 0 / 0 / 0 | 23.331 / 25.241 / 18.427 |
| 1: tight | 100 / 5 | 0; 233,939 | 0 / 0 / 0 | 189,160 / 194,067 / 191,506 | 0 / 0 / 0 | 19.141 / 17.044 / 18.138 |
| 2: overconstrained | 100 / 5 | 153; 180,781 | 110 / 110 / 110 | 155,228 / 155,228 / 155,228 | 52 / 52 / 52 | n/a / n/a / n/a |
| 3: easy | 250 / 10 | 0; 532,594 | 0 / 0 / 0 | 506,404 / 506,404 / 500,637 | 0 / 0 / 0 | 4.917 / 4.917 / 6.000 |
| 4: tight | 250 / 10 | 0; 508,067 | 0 / 0 / 0 | 476,962 / 479,305 / 477,590 | 0 / 0 / 0 | 6.122 / 5.661 / 5.999 |
| 5: overconstrained | 250 / 10 | 448; 351,928 | 327 / 326 / 336 | 326,305 / 326,304 / 330,339 | 152 / 151 / 156 | n/a / n/a / n/a |
| 6: easy | 500 / 25 | 0; 1,212,454 | 0 / 0 / 0 | 1,199,282 / 1,199,282 / 1,199,282 | 0 / 0 / 0 | 1.086 / 1.086 / 1.086 |
| 7: tight | 500 / 25 | 0; 1,206,491 | 0 / 0 / 0 | 1,188,390 / 1,188,390 / 1,188,390 | 0 / 0 / 0 | 1.500 / 1.500 / 1.500 |
| 8: overconstrained | 500 / 25 | 900; 900,322 | 900 / 900 / 900 | 900,322 / 900,322 / 900,322 | 375 / 375 / 375 | 0.000 / 0.000 / 0.000 |
| 9: improvable | 100 / 5 | 0; 101,101 | 0 / 0 / 0 | 90,120 / 90,120 / 90,120 | 0 / 0 / 0 | 10.861 / 10.861 / 10.861 |

Instances 2 and 5 improved weighted unassigned priority; their changed assigned demand makes a scalar cost-gain percentage inappropriate. Instance 8 retained its baseline unchanged. On the preregistered improvable instance 9, all three outcomes kept identical assigned demand and reduced operating score from 101,101 to 90,120, a 10.8614158% gain. The original move stage reached 96,106 before Timefold reached 90,120. The measured goal was met by the combined pipeline on this declared fixture; it is not a universal gain or an attribution solely to original moves or Timefold.

| Instance | Worker seconds, runs 0 / 1 / 2 | First durable baseline seconds, runs 0 / 1 / 2 | Queue milliseconds, runs 0 / 1 / 2 |
| --- | --- | --- | --- |
| 0 | 30.154 / 30.060 / 30.028 | 0.322 / 0.088 / 0.087 | 234.851 / 41.142 / 35.251 |
| 1 | 30.051 / 30.064 / 30.017 | 0.048 / 0.070 / 0.035 | 123.091 / 23.854 / 40.030 |
| 2 | 13.633 / 11.577 / 11.288 | 0.357 / 0.135 / 0.169 | 103.350 / 34.345 / 38.844 |
| 3 | 30.136 / 30.156 / 30.111 | 0.339 / 0.699 / 0.351 | 44.219 / 166.114 / 148.783 |
| 4 | 30.095 / 30.052 / 30.079 | 0.469 / 0.310 / 0.561 | 77.787 / 21.068 / 207.035 |
| 5 | 30.125 / 30.133 / 30.102 | 2.685 / 3.638 / 3.727 | 201.482 / 85.965 / 78.897 |
| 6 | 30.161 / 30.162 / 30.449 | 8.804 / 7.392 / 8.699 | 198.277 / 25.026 / 163.182 |
| 7 | 30.148 / 30.138 / 30.202 | 8.315 / 7.902 / 8.596 | 99.779 / 191.942 / 209.568 |
| 8 | 30.292 / 31.553 / 30.433 | 30.281 / 31.532 / 30.421 | 111.812 / 170.388 / 193.714 |
| 9 | 7.900 / 5.543 / 6.915 | 0.032 / 0.024 / 0.022 | 213.340 / 96.747 / 126.087 |

These are descriptive three-run outcomes, not p95/p99 latency estimates. Run 0 is retained rather than discarded; it includes the fresh service/JIT path for the first instance, while subsequent instances inherit a warming process. OS and database caches were not flushed. The packet also retains per-instance means, ranges and sample standard deviations, full lexicographic scores and exact input hashes. Queue time, HTTP/driver time, worker time and first durable baseline time are distinct boundaries.

The 30,000 ms configuration is cooperative. The maximum observed worker duration was 31.552836 seconds; an in-flight complete validation or database operation is allowed to finish. For the 500-job overconstrained instance, the first durable baseline arrived at 30.281 / 31.532 / 30.421 seconds, including construction, independent validation and persistence. The whole budget was consumed before Timefold started; all three returned the valid partial baseline. This is an observed scaling limitation, not a hidden constraint reduction.

The logs contain 27 actual Timefold sessions. Six outcomes, instances 2 and 9, reached the actual 100,000-step Local Search cap and ended early; none was padded to 30 seconds. The remaining 24 cases reached a wall-budget boundary, including the three baseline-exhaustion cases. Stored stopCondition remains TIME_OR_STEP_BUDGET; the finer classification comes from the preserved session logs. Timefold ran with its recorded PHASE_ASSERT environment mode, no move threads and seed 42, so these results include assertion overhead.

### JMH validation and import throughput

Each row uses three forks, three one-second warmups and three one-second measurements per fork, one thread, JMH 1.37 and Temurin 25.0.4.1 with -Xms512m/-Xmx768m. Construction occurs in trial setup. All nine primary throughput values per row and JMH-reported errors/confidence ranges are preserved; the table shows the overall mean and each fork mean. These operations are full route validation or complete strict import/model/hash work, not HTTP latency.

| Boundary | Jobs | Throughput ops/s | Fork means ops/s, 0 / 1 / 2 | Allocated bytes/op |
| --- | --- | --- | --- | --- |
| Route validation | 100 | 63,328.869 | 60,781.664 / 63,695.299 / 65,509.645 | 27,082.8 |
| Route validation | 250 | 19,697.380 | 14,082.097 / 23,997.719 / 21,012.323 | 64,709.7 |
| Route validation | 500 | 2,227.725 | 2,231.470 / 2,243.298 / 2,208.409 | 128,646.6 |
| Strict problem import | 100 | 239.394 | 287.296 / 224.215 / 206.672 | 3,915,633.6 |
| Strict problem import | 250 | 43.952 | 39.157 / 50.036 / 42.663 | 21,950,748.4 |
| Strict problem import | 500 | 8.584 | 7.716 / 9.424 / 8.613 | 82,356,845.2 |

One secondary metric is sparse: route validation at 500 jobs has gc.time sample counts [3, 2, 3]. The console shows a zero-GC iteration with gc.count but no gc.time value. The eight original values are preserved without inventing a ninth. Primary throughput, allocation and gc.count still have the full three-by-three observations. JMH-generated throughput-distribution percentiles in raw JSON are not request-latency percentiles.

### Process resources and GC

The service sampler produced 811 observations across 811.369 seconds of the system corpus. Process CPU increased by 991.52 seconds (963.97 user and 27.55 system), averaging 1.222 CPU cores against the four-core quota. Maximum sampled RSS and observed kernel high-water RSS were 983,470,080 bytes (937.91 MiB); sampled thread count ranged from 33 to 36. RSS includes native/JIT/thread memory in addition to the 768 MiB heap cap. PostgreSQL, corpus-client and JMH-fork memory are outside this service-process measurement.

The current service GC log contains 3,311 completed G1 pauses, totaling 24,580.216 ms with a maximum of 146.635 ms: 2,523 young, 394 remark and 394 cleanup pauses, with zero full pauses. This log also covers service startup and the idle period during JMH, so its total is not divided by the shorter resource-sampling interval. The corpus client separately recorded 30 young pauses totaling 1,007.014 ms, maximum 100.486 ms. JMH GC profiler metrics are a third boundary. Historical before-start logs and rotated logs are preserved but are not added to current-run GC totals.

### Credits and remaining gates

The immutable model, independent validator/oracle, deterministic insertion and directed move evaluation, residual cargo rules, fenced lifecycle and corpus are original project work. [Timefold Solver 2.7.1](https://github.com/TimefoldAI/timefold-solver) supplies the improvement engine and planning API; its contribution is visible in the fixture’s second improvement stage. [OpenJDK JMH 1.37](https://github.com/openjdk/jmh) supplies the benchmark harness, statistics and profiler. Spring Boot, PostgreSQL, Jackson and the Temurin/OpenJDK runtime provide application/database/JSON/JVM infrastructure. No upstream solver or harness is represented as an original routing engine.

The measured corpus/JMH gate is now executed. Independent review of the sealed packet and root-owned fresh-checkout/global/CI/release checks remain open. The current 54-method and 82/82 selected Validator mutation evidence keeps its original timestamp and scope; measurement does not expand mutation coverage or prove large-instance optimality.
