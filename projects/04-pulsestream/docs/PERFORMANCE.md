# Performance methodology and observed evidence

These measurements describe their recorded 2026-10-18 source, not later unmeasured changes. In the comparison tables, “current engine” means the optimized version measured in that study.

The complete quiet12 study passed on 2026-10-18: three separate 1,000,000-measurement durable runs, each with 1,000 duplicates and all six correctness assertions, plus original/current three-fork engine JMH. The runner exited zero at 08:09:12 UTC and scoped teardown succeeded. [The evidence packet](../evidence/reference-20261018-quiet12/README.md) binds inputs, statistics, source identities, and raw artifacts. Final artifact/release review is separate.

## Durable drain

`fixtures/workload-v1.json` fixes seed 4404, eight partitions, and 10,000 keys. Every thousandth index is duplicated; every tenth index is reordered by 15 seconds. `scripts/benchmark.sh full` prepares real Kafka input and a fresh PostgreSQL database, then measures bounded poll/engine/SQL drain. Snapshot, result, checkpoint, and SQL outbox insertion are included. Source/schema preparation and result-outbox delivery are excluded. This is closed-loop drain of preloaded durable input, not an open-loop capacity or offered-load latency test.

| Measured quantity | Round1 | Round2 | Round3 |
|---|---:|---:|---:|
| Drain seconds |1285.807|1256.208|947.230|
| Accepted measurements/s |777.722|796.047|1055.710|
| Checkpoint samples |1018|1017|1018|
| Checkpoint p50 ms |774.059|1122.618|743.846|
| Checkpoint p95 ms |3330.102|2656.685|1816.286|
| Checkpoint p99 ms |5014.970|3674.227|2680.853|
| Java process CPU seconds |1065.110|1017.500|808.690|
| Average Java process cores |0.828|0.810|0.854|
| Java allocated GB, decimal |60.158|59.620|60.100|
| Observed peak heap MiB |459.5|441.5|449.5|
| Drain MXBean GC collections |1811|1683|1849|
| Drain MXBean GC time seconds |47.982|47.729|34.733|
| Database growth MB, decimal |383.205|363.160|385.729|
| SQL restore/source-seek recovery ms |3075.732|3692.436|4008.791|
| Source preparation seconds, excluded |11.284|5.360|10.675|
| Database preparation seconds, excluded |1.783|1.985|2.230|
| Oracle validation seconds, separate |0.227|0.265|0.196|

Each round admitted exactly one million measurements and 1,000 identical duplicates, reached watermark 94,000, published 20,000 windows, matched its committed/recovered semantic-projection digest and independent aggregate result digest, and passed all six assertions. Aggregate result digests match across rounds; run/database identities differ. Maximum queue occupancy was seven batches against a capacity of eight.

The recovery digest covers watermark/duplicates, source offsets and maximum timestamps, dedup entries, panes, nonfinal results, pending windows and admitted keys. It excludes partition activity/idle fields intentionally rebased on restart, run identity/limits, late counters, quarantine, outbox and finalized results. It is a semantic projection, not byte-for-byte full snapshot equality. Separately, the SQL oracle checks row count, tenant/sensor/window keys, end, count/sum/minimum/maximum and finality, and requires a positive revision (`revision >=1`). It does not independently derive exact revision values or their sequence; revision is not part of its aggregate result digest. Raw labels claiming “full recovered state digest” and “all published window fields” remain preserved with [this explicit qualification](../evidence/reference-20261018-quiet12/scope-clarifications.json).

The arithmetic mean rate is 876.493 events/s, sample standard deviation 155.477, and sample CV 17.739%. Dividing 3,000,000 accepted events by total drain time gives 859.785 events/s. The faster third round is observed variation; three sequential runs on one host do not establish stable capacity or its cause. Reported p95/p99 values are separate per-round quantiles. Checkpoint latency excludes offered-load queue waiting and has coordinated-omission limits. Sampled heap maxima are not absolute peak bounds.

The GC text logs cover complete JVM lifetimes, including preparation, validation, and recovery. Completed-pause sums are 49.753/48.925/36.849 seconds, with maxima of 364.051/569.540/270.319 ms. Their observation window and event definition differ from the drain-scoped MXBean counts/times above; the two must not be equated. The raw index records parsing boundaries.

## Fresh-engine comparison

`EngineBenchmark.freshBatch` computes a fresh 500-record transition with eight partitions and consumes the result. `@OperationsPerInvocation(500)` normalizes throughput to event operations/s. Each variant uses three independent forks, two one-second warmups, three one-second measurements, the same harness/dependencies/profilers, G1, and a 512 MiB heap. The baseline puts preserved, hash-verified pre-optimization Engine classes first on the fork classpath. It is not a durable-service control.

| JMH quantity | Original engine | Current engine |
|---|---:|---:|
| Mean normalized event operations/s |26613.575|176874.217|
| Fork1mean operations/s |13987.295|241998.957|
| Fork2mean operations/s |31726.426|185722.212|
| Fork3mean operations/s |34127.005|102901.483|
| Sample CV across three fork means |41.334%|39.559%|
| Normalized allocation B/event-op |19987.013|7642.761|
| JMH supplied score-error half-width, operations/s |16810.149|120377.771|

The observed current/original mean throughput ratio is 6.646; normalized allocation is 61.761% lower. Between-fork variability is high and warmup/measurement durations are short. All nine measurements per variant and the original confidence/error fields are retained; within-fork samples are not independent forks. This fresh-state microbenchmark does not exercise a million-ID dedup table or establish a 6.646× durable-service benefit. No old-engine durable control ran.

Each durable run retains its workload JFR, GC log, and environment/result/console files. The JMH profiler reused one pathname within each variant, so only one recording per variant remains; numeric primary and GC-profiler samples retain all forks. The [bounded semantic addendum](../evidence/reference-20261018-quiet12-jfr-addendum/README.md) inspected all five recordings with official JDK 25 tools and received a separate output/hash/sample interpretation review. This does not recover overwritten recordings or establish complete hotspot coverage.

## Environment, resources and interpretation

The recorded host was Linux 6.18.55/x86_64 on Intel Xeon Platinum 8573C, using Temurin 25.0.4.1+1-LTS. Five logical/affinity CPUs were visible; the cgroup quota was 400000/100000, equivalent to four cores, with 32 GiB memory. Java used `-Xmx512m` and G1. Kafka 4.2.1 and PostgreSQL 18.3-alpine shared the host and used the pinned environment/Compose digests. Java CPU/allocation/heap figures exclude container resource costs. Two-second disk/process/container supervision overhead is included but not independently quantified. Preflight excluded competing builds/apps; report/hash processing began after the study ended.

Approximately 60 GB allocated per full run is an allocation observation. Dedup/state copying, snapshot encoding/compression, and SQL pane replacement remain hypotheses; allocation totals and GC times alone do not establish the dominant bottleneck. Sequential-order/cache effects, instrumentation, one-host resource limits, and the sample size limit generalization. No production SLO, saturation, concurrent-tenant, or distributed exactly-once claim follows.

The raw index verifies 25 files totaling 70,859,616 bytes with exact locators, hashes, and sizes. Large artifacts remain outside Git under `.work/A04/runtime`; small JSON copies are published. The measured source is `working-tree-sha256:2f7d000815cce016675463dcac431f93332bf2f4049a0b19a1330391fda940fc`; the envelope binds runner, readiness, and baseline identities. The failed quiet10 attempt is separate and contributes no measurements.

The earlier 10,000-event/100-key shared-host smoke remains [diagnostic evidence](../evidence/smoke-before-optimization.json). Preparation generates JMH metadata/classpaths without silently measuring a workload. Reproduction requires an idle reference host and frozen-input checks; Maven preparation belongs outside the measured interval.


## Separate capture repair verification

Quiet14 on 2026-10-18 validated a capture-only change at source `working-tree-sha256:bf8294af855673e08feb586894e30e1ed22f77ff065af8c2714c8ffd0e2e6ace`. The two scripts replace the reused profiler pathname with launch-enabled `StartFlightRecording` and literal `profile-%p.jfr`; the JDK expands `%p` to each fork PID. Three forks, two one-second warmups, three one-second measurements, GC profiling, and original/current classpaths remain. Application Java and workload inputs did not change. Preparation passed 15 ordinary methods; direct comparison preserved 44 classes, four resources, six JARs, and 129 runtime hashes.

The separate capture check ran from 08:41:51 to 08:42:37 UTC in 45.477 seconds. [Capture evidence](../evidence/capture-20261018-quiet14/README.md) indexes 14 raw files totaling 4,792,650 bytes. Six distinct files match observed fork PID/start ticks and official JVMInformation/ForkedMain identities. All 12 bounded summary/identity checks passed; recordings lasted approximately 5–6 seconds and contained execution/allocation samples. Both variants retained three forks with three finite primary/GC samples each. All 175 preflight hashes matched, no competing Java/apps were observed, and no dependency stack ran.

| Capture-only observation | Original engine | Current engine |
|---|---:|---:|
| Distinct PID-named JFRs |3|3|
| Mean normalized event operations/s |39472.925|278588.980|
| Sample CV across three fork means |3.658%|12.492%|

The launch-enabled recordings include JMH warmup, measurement, and shutdown after recorder initialization; early JVM bootstrap can precede capture. Their observation window and profiling overhead differ from quiet12. These scores stay separate and do not replace or extend the earlier comparison. No stable-capacity, durable-speedup, or dominant-cause claim follows. The three-million-event study retains its original source and semantic-projection/positive-revision qualifications; no durable workload was rerun or relabeled.

The prior qualified 20-file documentation/evidence packet remains preserved under the archive recorded by the capture packet. Original result/JMH/analysis JSON and the semantic addendum remain unchanged. Capture code/raw review passed separately; final public-packet and release review remain open.
