# Measured authorization reference

The full quiet reference completed on 2026-10-18: three fresh 100,000-request client JVMs against the same continuing server/database, plus two separate three-fork JMH component benchmarks. Every HTTP run had **zero errors and exactly 20,000 denials**. Throughput declined **593.275 → 504.958 → 367.263 requests/s**; the median was 504.958 and the sample coefficient of variation was **23.32%**. These runs establish successful workload execution, not stable sustainable capacity. No repetition was discarded or replaced to improve the summary.

The coordinator held all three build locks from 03:53:56.096602 to 04:07:30.175597 UTC. The protocol exited zero after 814.080 seconds, with no unrelated active JVMs/application containers at reservation or completion. Required PostgreSQL, Keycloak and both HTTP services shared the host with the workload. Source fingerprint is `working-tree-sha256:832dfc3ccd0bf9399528721f8a31710c4b0197129b0b183aadcd5a1afeb92c11`; reservation Git revision was `7770cfee7cf2055c69e5612acfd03934f97b57b9`.

The [small audit envelope](../evidence/reference-20261018/measurement-envelope.json), [all-run CSV](../evidence/reference-20261018/system-runs.csv), [JMH summary](../evidence/reference-20261018/jmh-summary.json) and [verified artifact receipt](../evidence/reference-20261018/verified-artifacts.json) preserve results and boundaries. Original HTTP/JMH/server summary JSON remains in evidence. Six original JFR recordings and complete logs/results are retained in `.work/A05/reference-20261018-quiet6/`, indexed by size/SHA256; the raw archive contains 22 indexed files. No new Java profiling process or experiment was started during analysis.

## Full authenticated HTTP boundary

The actual seed contains 50 tenants, 200 immutable policy revisions per tenant, 200 active rules, 100 roles per revision and 1,000 resources per tenant: 10,000 versions, 1,000,000 role rows, 990,000 inheritance edges and 50,000 resources. Each active definition contains department/clearance allow, suspension deny and 198 applicable false-purpose allow rules. Every fifth resource mismatches department. Seed reuse validates digest/counts; `preparation.json` records the original direct PostgreSQL totals and reset-preservation check.

Each client process obtains genuine local Keycloak tokens before measurement, performs 1,000 excluded warmup requests, then uses 16 closed-loop clients for 100,000 timed decisions. Each request includes real JWT signature validation, fresh repeatable-read tenant membership/epoch/policy/resource reads, immutable policy-cache lookup/evaluation and required committed redacted audit. Hikari has eight connections. There is no ALLOW cache. The protected-resource service is exercised separately in the asserted demo; these system measurements call the decision HTTP API directly.

| Run | Measured seconds | Requests/s | p50 ms | p95 ms | p99 ms | Application prepared statements | Cache entries | Denials/errors | Maximum client in-flight |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 168.556 | 593.275 | 19.406 | 66.606 | 133.285 | 700,150 | 50 | 20,000 / 0 | 16 |
| 2 | 198.036 | 504.958 | 25.160 | 70.953 | 121.010 | 700,150 | 50 | 20,000 / 0 | 16 |
| 3 | 272.284 | 367.263 | 34.690 | 102.451 | 176.966 | 700,150 | 50 | 20,000 / 0 | 16 |

Mean throughput was 488.499 requests/s, sample standard deviation 113.901. The first-to-third decline was 38.10%. The median of per-run p99 values was 133.285 ms; this is not a pooled percentile. Three repetitions with one fixed seed provide low confidence in stable capacity. No numeric latency/throughput budget was imposed after seeing the results.

Fresh clients reused the same decision JVM, policy cache and PostgreSQL data. Audit records accumulated; the backend was not reset between repetitions. The observed slowdown is not attributed to audit growth, GC, storage, JVM behavior or cloud interference without a controlled experiment. A future matched fresh-dataset versus retained-history comparison could investigate this, but no such experiment or optimization speedup is claimed here.

The query count instruments application `PreparedStatement` creation in committed tenant transactions. Its delta includes tenant-context/epoch/membership/policy/resource/audit statements and telemetry reads; it is not a PostgreSQL wire-level query trace. Transaction commit/rollback, trigger-internal work, warmup and later telemetry outside the sampled delta are not all represented. Cache size50 exercises the active working set, not eviction at the 500-entry bound. The workload checks the aggregate deny/error counts; independent policy-oracle/adversarial/isolation correctness evidence is separate.

Closed-loop sampling has coordinated omission and understates latency under stalls at arbitrary offered load. Maximum client in-flight16 is observed client concurrency, not an assertion about saturation of the 64-handler service bound. Request deadlines were10seconds and the measured-run deadline15minutes; no partial success or reduced smoke count was accepted.

## Existing server recording observations

Separate server and client JFR files were captured for every run. Server recording begins before token/warmup work and ends after telemetry/result completion, so its boundary is broader than the timed 100,000-request loop. Client recordings also include JVM startup/warmup/telemetry. Existing `FlightSummary` JSON was checked for source/profile identity and retained unchanged; raw recordings were size/SHA256 checked and archived. This analysis did not rerun JFR CLI extraction or independent event recounting.

| Run | CPULoad events | Mean / maximum JFR JVM CPU fraction | GC events | Sum GC-event seconds | Allocation samples | Allocation sample weight GB (10^9 bytes) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 190 | 0.2475 / 0.5125 | 392 | 6.549 | 52,396 | 22.965 |
| 2 | 207 | 0.2047 / 0.3400 | 350 | 2.325 | 58,425 | 45.731 |
| 3 | 279 | 0.1804 / 0.2729 | 351 | 2.862 | 78,466 | 68.489 |

CPULoad values are periodic JVM-normalized fractions, not exact cgroup utilization or CPU stack counts. Allocation weights are sampling estimates; sequential recordings share a long-lived JVM and sampling state. They are not converted to exact per-request allocation or used to explain the slowdown. GC-event durations can include concurrent collector work and must not be summed as application pause time. These summaries provide observed profiles, not exclusive attribution of time to SQL, cryptographic verification or evaluator code.

## Separate JMH component boundaries

JMH 1.37 used SampleTime microseconds, one thread, three forks per method, three one-second warmups and three one-second measurements per fork, with `-Xms128m -Xmx256m` (overriding the launcher's earlier `-Xmx512m`) and `-prof gc`. The actual [unmodified JSON](../evidence/jmh.json) retains all3×3 histograms. Recomputed weighted histogram means match the reported scores: 7,929 cold-compilation and235,850 warm-evaluation samples.

| Method | Sample mean µs/op | JMH reported error µs/op | Sample p50 µs | Sample p99 µs | Allocation B/op | GC count / time ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Uncached compilation | 1,133.872 | ±114.328 | 616.448 | 6,850.150 | 1,548,097.58 | 156 / 275 |
| Warm indexed evaluation | 12.724 | ±1.355 | 5.360 | 41.280 | 5,540.26 | 67 / 186 |

Reported 99.9% score confidence intervals were1,019.544–1,248.200µs and11.369–14.078µs. Normalized allocation errors were±8,921.81B/op and±122.73B/op. These are JMH statistical summaries, not worst-case bounds or network/database latency. Long tails remain in the raw samples; the mean is not the median.

“Uncached compilation” recompiles an already constructed200-rule/100-role definition, including condition parsing/type checking/closure/index/digest, with warmed JVM code. It does not measure cold JVM startup, PostgreSQL reads or JSON request ingestion. Warm evaluation uses one precompiled policy and a reader principal with supplied attributes; it includes none of JWT/JDBC/mandatory audit/HTTP work. Returning compiled/decision values prevents dead-code elimination. No comparison of these microseconds to durable authorization capacity is implied.

## Host, provenance and reproduction

Host: Linux 6.18.55/x86_64 Intel Xeon Platinum 8573C, five visible processors with four cgroup CPU quota cores,32 GiB memory; Temurin 25.0.4.1+1-LTS. Storage is overlayfs with unknown underlying device. PostgreSQL 18.3 and Keycloak 26.8.1 use the exact pinned tested image digests. Both services use256MiB heaps; clients512MiB; Hikari8. Physical cloud neighbors are unobservable. Complete [captured environment](../evidence/environment.json) includes artifact hashes and historical test baselines; its earlier integration-stack count is not the quiet-study concurrent workload count.

Before reserving the reference host, `scripts/prepare-runtime.sh` performs ordinary verification if required, checks all executable artifact hashes and extracted runtime bytes, and requires real JMH `-l` discovery. During a coordinator-granted quiet window the executed protocol was:

```bash
TENANTSHIELD_QUIET_GRANTED=1 .work/A05/run-reference-window.sh
```

It uses no-Maven mode, frozen source/artifacts, retained seeded volume, both full JMH methods and three full HTTP processes, then owned teardown even on failure. The first quiet attempt failed before JMH because extraction omitted archive-root benchmark metadata; its exact failure remains `reference-attempt-5-failed.json`. The repaired metadata extraction passed 18 unit methods, actual discovery and eight integrity checks before this successful study. No failed or diagnostic run was pooled into these results.

Independent measured-artifact review, clean-checkout reproduction and repository-wide/remote-CI release validation remain open. Current-instance workload success is not a production SLA, capacity guarantee or complete repository release.
