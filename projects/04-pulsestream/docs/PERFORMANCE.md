# Performance methodology and observed evidence

The complete quiet12 study passed on2026-10-18: three separate1,000,000measurement durable runs, each with1,000duplicates and all six independent correctness assertions, plus original/current three-fork engine JMH. The coordinator's runner exited0 at08:09:12UTC and scoped teardown succeeded. [The small evidence packet](../evidence/reference-20261018-quiet12/README.md) contains exact JSON inputs, statistics, identities and a verified raw-artifact index. Final artifact/release review is separate.

## Durable drain

`fixtures/workload-v1.json` fixes seed4404, eight partitions and10,000keys. Every thousandth index is duplicated; every tenth index is reordered by15seconds. `scripts/benchmark.sh full` prepares real Kafka input and a fresh PostgreSQL database, then measures bounded poll/engine/SQL drain. Snapshot, result, checkpoint and SQL outbox insertion are included. Source/schema preparation and result-outbox delivery are excluded. This is a closed-loop drain of preloaded durable input, not an open-loop capacity or offered-load latency test.

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

Each round admitted exactly1mmeasurements and1,000identical duplicates, reached watermark94,000, published20,000windows, matched its committed/recovered durable semantic-projection digest and independently derived aggregate result digest, and passed6/6assertions. Aggregate result digests match across rounds; independent run/database identities differ. Maximum observed queue occupancy was7batches against capacity8.

The recovery digest covers watermark/duplicates, source offsets and maximum timestamps, dedup entries, panes, nonfinal results, pending windows and admitted keys. It excludes partition activity/idle fields intentionally rebased on restart, run identity/limits, late counters, quarantine, outbox and finalized results. It is a semantic projection, not byte-for-byte full snapshot equality. Separately, the SQL oracle checks row count, tenant/sensor/window keys, end, count/sum/minimum/maximum and finality, and requires a positive revision (`revision >=1`). It does not independently derive exact revision values or their sequence; revision is not part of its aggregate result digest. Raw labels claiming “full recovered state digest” and “all published window fields” remain preserved with [this explicit qualification](../evidence/reference-20261018-quiet12/scope-clarifications.json).

The arithmetic mean of the three rates is876.493events/s, sample standard deviation155.477 and sample CV17.739%. Aggregate3,000,000accepted events divided by summed drain time is859.785events/s. The faster third round is an observed variation; these three sequential runs on one host do not establish stable capacity or its cause. The reported p95/p99 values are per-round quantiles; they are not pooled into a global percentile. Checkpoint latency excludes offered-load queue waiting and has coordinated-omission limits. Observed heap maxima are sampled, not absolute peak bounds.

The GC text logs cover complete JVM lifetimes, including preparation, validation and recovery. Their completed-pause sums are49.753/48.925/36.849seconds, with maxima364.051/569.540/270.319ms. These have a different observation boundary and event definition from the drain-scoped MXBean collection counts/times above; they must not be equated. Raw log summaries and parsing boundaries are in the index.

## Fresh-engine comparison

`EngineBenchmark.freshBatch` computes a fresh500-record transition with eight partitions and consumes the result. `@OperationsPerInvocation(500)` normalizes the throughput to event operations/second. Each variant uses three independent forks, two1second warmups and three1second measurements, the same current harness/dependencies/profilers, G1 and512MiB heap. The original baseline uses preserved, hash-verified pre-optimization Engine classes first on the fork classpath. It is not a separate durable-service control.

| JMH quantity | Original engine | Current engine |
|---|---:|---:|
| Mean normalized event operations/s |26613.575|176874.217|
| Fork1mean operations/s |13987.295|241998.957|
| Fork2mean operations/s |31726.426|185722.212|
| Fork3mean operations/s |34127.005|102901.483|
| Sample CV across three fork means |41.334%|39.559%|
| Normalized allocation B/event-op |19987.013|7642.761|
| JMH supplied score-error half-width, operations/s |16810.149|120377.771|

The observed current/original mean throughput ratio is6.646; normalized allocation is61.761%lower. Between-fork variability is high and warmup/measurement durations are short. All nine measurements per variant and JMH's original confidence/error fields are retained; within-fork measurements are not nine independent forks. This fresh-state microbenchmark does not exercise a million-ID dedup table or establish a6.646×durable-service benefit. No old-engine durable control was run.

Each durable run retains its complete workload JFR, GC log and environment/result/console files. The JMH profiler printed the same pathname for each fork within a variant; exactly one JFR remains per variant, so separate recordings for all three forks are not available. Numeric primary and GC-profiler samples retain all forks. The subsequent [bounded semantic addendum](../evidence/reference-20261018-quiet12-jfr-addendum/README.md) successfully parsed all five retained recordings with official JDK25tools. A03 independently closed its output/hash/sample interpretation review. This does not recover the overwritten fork profiles or establish unsampled/profile-complete hotspots.

## Environment, resources and interpretation

The recorded host was Linux6.18.55/x86_64 on Intel Xeon Platinum8573C, JDK Temurin25.0.4.1+1-LTS. Five logical/affinity CPUs were visible; cgroup CPU quota was400000/100000, equivalent to four cores, with32GiB cgroup memory. Java used `-Xmx512m`/G1. Kafka4.2.1 and PostgreSQL18.3-alpine shared this host and were pinned to the exact immutable digests in the environment records/Compose file. Java process CPU/allocation/heap excludes container resource costs. Two-second disk/process/Docker inspect/top supervision overhead is included, not independently quantified. Quiet reservation preflight excluded other builds/apps; light report/hash work occurred only after the entire study ended, during another project's functional/stress window.

Approximately60GB allocated per full run is an allocation observation. Full dedup/state copying, snapshot encoding/compression and SQL pane replacement remain cost hypotheses; these totals and GC times do not establish a dominant bottleneck. Later profile/control evidence is required for causal claims. Sequential-order/cache effects, instrumentation, one-host resource limits and the three-round sample size limit generalization. No production SLO, saturation, concurrent-tenant or distributed exactly-once claim is made.

The raw index verifies25files/70,859,616bytes with exact local paths/SHA256/sizes. Large raw files remain outside Git under `.work/A04/runtime`; small JSON copies are published. Source identity is `working-tree-sha256:2f7d000815cce016675463dcac431f93332bf2f4049a0b19a1330391fda940fc`; envelope records runner/READY/baseline identities. Failed quiet10 is preserved separately and contributes no measurements here.

The earlier10k/100key shared-host smoke remains [diagnostic evidence](../evidence/smoke-before-optimization.json), not a substitute for this prescribed study. Preparation explicitly generates managed JMH metadata/classpath and never silently runs a benchmark. Reproduction requires a fresh coordinator-granted quiet host and the frozen input checks; no Maven rebuild belongs inside the measurement reservation.


## Separate capture repair verification

Quiet14on2026-10-18 validated a capture-only script change at source `working-tree-sha256:bf8294af855673e08feb586894e30e1ed22f77ff065af8c2714c8ffd0e2e6ace`. The two scripts replace JMH's same-path built-in recording profiler with launch-enabled `StartFlightRecording` and literal `profile-%p.jfr`; the JDK expands `%p` to each fork PID. Three forks, two1second warmups, three1second measurements, GC profiler and original/current classpaths remain. No application Java/workload changed. Supported preparation passed15ordinary methods with a fresh-cutoff root checker; direct byte comparison preserved44classes/four resources/sixJARs and129runtime hashes.

The separate all-three-lock reservation passed08:41:51–08:42:37UTC in45.477seconds. [Capture evidence](../evidence/capture-20261018-quiet14/README.md) indexes14raw files/4,792,650bytes. Six distinct files match observed fork PID/start ticks and official JVMInformation/ForkedMain identities. All12capped official summary/identity checks passed; recording durations round to5–6seconds with positive execution/allocation samples. Both variants retain3×3finite primary/GC sample sets. All175preflight input hashes matched; the outer reservation recorded no other Java/apps initially or remaining. No dependency stack started.

| Capture-only observation | Original engine | Current engine |
|---|---:|---:|
| Distinct PID-named JFRs |3|3|
| Mean normalized event operations/s |39472.925|278588.980|
| Sample CV across three fork means |3.658%|12.492%|

These launch-enabled recordings include JMH warmup/measurement/shutdown after recorder initialization; early JVM bootstrap can precede recording. The observation window and profiling overhead differ from quiet12's built-in profiler. These scores stay separate and do not replace, extend or form a same-profiler comparison with quiet12rows. No new stable-capacity, durable-speedup or dominant-cause claim follows. The accepted three-million durable study retains its original source2f7d… and all semantic-projection/positive-revision qualifications; no durable workload was rerun or relabeled. Keeping all forks' recordings was a capture-specific improvement, not a newly invented missing mandatory gate on the earlier accepted study.

The exact prior qualified20file documentation/evidence bytes were preserved before these two current documentation updates; its seal remains reproducible from the archive recorded by the new capture packet. Original copied result/JMH/analysis JSON and the separate semantic addendum remain unchanged. Capture raw/code review passed independently; final public-packet/release review is separate.
