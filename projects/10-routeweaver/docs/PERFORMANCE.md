# RouteWeaver performance protocol and evidence

No large-instance optimum or uniform speedup is promised. Feasibility, physical cargo/freeze preservation and same-input incumbent nondegradation are mandatory. The proposed 10% operating-cost reduction applies only to the preregistered deliberately improvable fixture within a 30-second budget. It is a tuning goal until the real measured result is recorded.

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

Current measured release status: full quiet corpus and JMH are OPEN. No p95/p99 estimate is fabricated from three solve samples. Publish all three outcomes and variability, plus the exact improvable-instance result after execution. The current STATUS and evidence files identify historical correctness runs separately from pending current-source measurements.
