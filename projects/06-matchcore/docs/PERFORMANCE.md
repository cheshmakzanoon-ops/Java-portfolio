# Measured reference workload

The isolated reference study completed on 2026-10-18: three fresh one-million-command JVMs per mode and a separate three-fork JMH run. Median throughput was **113,277 commands/s for the memory-only core** and **1,706 commands/s for the durable gateway**. These measure different boundaries. The study exercises a shallow book: **27 observed peak open orders**, versus a configured cap of 100,000. It establishes neither deep-book capacity nor HTTP service throughput.

All six runs returned the same complete business-result histogram and final digest, `37574212b19a77ee68564470ad68f1b4488e112a8b85886a3e4443e6ebfff6f5`. Each durable replay reproduced its live-state digest. No repetition was discarded.

The small committed artifacts preserve [all six exact results](../evidence/reference-20261018/reference-results.json), [system-run CSV](../evidence/reference-20261018/system-runs.csv), [complete JMH JSON](../evidence/reference-20261018/jmh-results.json) and an [audit envelope](../evidence/reference-20261018/measurement-envelope.json) containing environment, isolation, raw-file hashes, resources, GC/JFR summaries and variability. Original journals, JFR recordings, GC logs and event JSON are retained in a verified 165,957,826-byte local archive; the original `.runtime/reference.2lkNWp` batch was removed after independent physical recovery and a complete immediate recheck. Historical raw locators in the unchanged envelope require [explicit restoration](../evidence/reference-20261018/README.md#restore-the-original-raw-files). The full manifest and preservation receipts are committed; the large archive is ignored local data and must be transferred separately from a clone.

## Results and variability

Acknowledgement percentiles below use microseconds. Elapsed time includes the generated command loop and scheduled duplicate lookups, but excludes the 64 INIT commands, final state verification and recovery.

| Mode/run | Loop seconds | Commands/s | p50 µs | p95 µs | p99 µs | Allocation B/command | Loop-end heap MiB | Recovery seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Core 1 | 8.828 | 113,276.7 | 2.511 | 16.629 | 31.808 | 2,358.32 | 695.14 | — |
| Core 2 | 5.838 | 171,277.7 | 2.353 | 6.630 | 24.580 | 2,354.98 | 693.14 | — |
| Core 3 | 8.859 | 112,881.1 | 2.527 | 9.596 | 38.688 | 2,365.15 | 458.14 | — |
| Durable 1 | 558.619 | 1,790.1 | 401.129 | 1,424.590 | 3,086.002 | 12,506.66 | 720.11 | 10.944 |
| Durable 2 | 641.760 | 1,558.2 | 430.964 | 1,796.581 | 3,827.595 | 12,540.12 | 876.14 | 18.379 |
| Durable 3 | 586.052 | 1,706.3 | 413.472 | 1,516.378 | 3,263.688 | 12,483.01 | 664.14 | 25.456 |

Core rates range from 112,881 to 171,278 commands/s; their arithmetic mean is 132,479 and sample coefficient of variation is 25.36%. Durable rates range from 1,558 to 1,790; their mean is 1,685 and sample coefficient of variation is 6.97%. Three repetitions and one fixed seed do not establish a population performance distribution. The faster second core run remains in every summary; no controlled experiment attributes its difference to a particular JVM or host effect. System-driver commands have **no excluded warmup** beyond account initialization, so compilation and GC effects during those commands remain included.

The median of the three per-run p99 values is 31.808 µs for core and 3,263.688 µs for durable. These are medians of percentiles, not pooled percentiles. Recovery's median is 18.379 seconds, with a 10.944–25.456-second range. It measures the reopened Gateway constructor in the same warm JVM with hot OS page cache; digest comparison and close are excluded. It is not cold-start or cold-storage recovery.

Each durable loop performed exactly 1,000,000 `force(true)` calls, with another 64 for initialization. Counts follow successful unique admissions through the one-force journal path; no independent syscall trace was collected. Batch size was one, and force frequency equaled the reported durable command rate. Duplicate requests returned retained outcomes without another journal append or force. The closed-loop client had one outstanding command; sampled queue depth peaked at one for durable and zero for core, rather than providing a complete queue histogram. Risk/capacity rejection count was zero in every run. This load does not measure saturation or latency under arbitrary offered traffic; closed-loop sampling has coordinated omission.

## Workload and complete outcomes

Generator version 2 (`v2-live-identity-shuffled-block`, seed 42006006) uses 64 accounts and shuffled 100-command blocks containing exactly 60 NEW, 25 CANCEL and 15 REPLACE commands. NEW/REPLACE prices are uniform integral ticks 9900..10100 and quantities 1..10. NEW uses GTC without post-only. Cancel/replace chooses an actual live account/order/side, or a unique missing identity only when the book is empty. Every run selected 387,308 live mutation targets and 12,692 empty-book targets; final open orders were six. No claim that this workload reaches the cap is made. Matching rules, cap rejection and IOC/FOK/post-only behavior are verified separately by correctness tests rather than inferred from these timing results.

Duplicate injection runs every 1000 commands, including command zero: 1000 identical retries per full run, outside the million-command business histogram. Each run verifies the live-order tracker, aggregate cash/inventory and complete result count. Every durable run additionally compares recovered state digests.

| Business result | Count per run | Percent of million commands |
| --- | ---: | ---: |
| RESTING | 571,993 | 57.1993% |
| CANCELED | 242,369 | 24.2369% |
| FILLED | 167,535 | 16.7535% |
| NOT_FOUND | 12,692 | 1.2692% |
| SELF_TRADE_CANCELED | 5,072 | 0.5072% |
| REDUCED | 339 | 0.0339% |

The original generator independently chose accounts and order IDs, producing 39 missing targets in an initial 100-command smoke. It was replaced before baseline collection. The new generator has independent target/state assertions across three seeds and 3,000 transitions. Earlier shared-host 10,000-command captures remain explicitly ineligible diagnostics in `capture-diagnostic-v2.json`; they are not pooled into this study. Prebaseline samples also motivated caching the constant identifier Pattern. Correctness and mutation gates passed afterward, but no controlled before/after speedup is claimed.

## Resource observations and profiling limits

ThreadMXBean allocation deltas cover live Java threads during the measured loop, including the driver, writer and background threads; terminated-thread allocations can be missed. Median allocation was 2,358.32 B/command for core and 12,506.66 B/command for durable. Used heap is a loop-end point sample, not retained size after GC. The finite run retains identities, results and executions, so memory cost is not proportional only to the 27-order book.

| Mode/run | Loop process CPU seconds | Loop GC count/time ms | Whole-process max RSS GiB | Whole-process max logged GC pause ms |
| --- | ---: | ---: | ---: | ---: |
| Core 1 | 12.85 | 8 / 782 | 2.368 | 191.001 |
| Core 2 | 10.72 | 10 / 652 | 2.384 | 148.865 |
| Core 3 | 13.30 | 9 / 757 | 2.135 | 143.441 |
| Durable 1 | 200.50 | 69 / 4,286 | 2.936 | 199.115 |
| Durable 2 | 236.28 | 69 / 4,766 | 2.992 | 502.504 |
| Durable 3 | 211.82 | 68 / 4,690 | 3.036 | 339.725 |

Whole-process POSIX resource accounting includes startup, setup, verification/digest construction and recovery; RSS also includes native memory. GC logs and JFR likewise cover that entire process. The logged maximum pauses must not be presented as measured-loop acknowledgement samples. No full-GC pause or allocation-failure marker appeared in the six retained logs. GC-log pause counts and MXBean collection counts have different definitions and boundaries.

The six recordings contain 1,217/816/1,409 core execution samples and 4,044/6,381/6,150 durable execution samples. Allocation samples number 2,548/1,900/2,532 and 16,188/17,838/18,050 respectively. Counts and top-frame/class summaries were independently recomputed from all retained event JSON and matched the launcher summaries. Durable samples repeatedly include immutable public-view/account construction, result-map operations and final digest construction. Byte arrays, Balance objects and TreeMap entries are prominent sampled allocation classes. These identify areas to investigate, not exact cost attribution: recovery and verification contribute to the recordings, and CPU execution samples do not measure blocked force latency. Group commit and lower-allocation publication are possible future experiments; neither improvement was implemented or claimed from this baseline.

## Separate JMH partial-fill result

JMH 1.37 used one thread, three independent forks, three one-second warmup iterations and three one-second measurement iterations per fork, with `-Xmx512m` and `-prof gc`. The benchmark fills five lots from a ten-lot resting ask at maker price. Per-invocation setup constructs a fresh two-account book outside the timed `Book.apply` method. Setup allocations remain visible to GC profiling, so these are not matching-only allocation figures.

Across nine measured iterations, throughput was **1,193,758.75 ± 238,436.68 ops/s**, JMH's reported 99.9% confidence interval `[955,322.07, 1,432,195.43]` under its normal-distribution assumption. Fork means were 1,319,986.12; 1,224,349.17; and 1,036,940.96 ops/s. Normalized allocation was **7,165.36 ± 26.88 B/op**, including invocation setup. JMH reported 65 collections and 103 ms GC time across measured iterations. This tiny isolated partial-fill experiment is distinct from both whole-workload rates and durable acknowledgements.

## Environment, provenance and reproduction

The coordinator held all three build locks from 02:54:16.349894 to 03:28:07.482351 UTC; the complete protocol exited zero after 2031.133 seconds. No unrelated active JVMs or application containers were present at reservation or completion, and every launch recorded zero other live Java processes. Defunct entries were recorded separately. The cloud provider's physical neighbors remain unobservable.

Measured executable/build-input fingerprint: `62fc0b0351864486ffa736bd64f444393e3dda894f93677c7ca95448dab1ce83`. Reservation Git revision was `29e0bbd0df3c6ab6d776817b0dc3b05e86141392`; runtime outputs/dependency artifacts were bound to verification by the prepared cache. [Cache integrity evidence](../evidence/runtime-artifact-cache.json) retains 17 actual tamper/restoration probes; the [67-artifact receipt](../evidence/reference-20261018/verified-artifacts.json) preserves actual compiled/resource/dependency hashes. Its current read-only integrity check also passed during analysis. This is a trusted local receipt, not remote attestation. No production Java changed after the 21-method integration/mutation verification, which killed 119/133 selected Book mutants. Raw-file SHA-256 checks passed for all 83 indexed files, including journals, recordings and their summaries.

Host: Debian 13.6/Linux 6.18.55, x86_64 Intel Xeon Platinum 8573C, five visible processors with a four-core cgroup quota, 32 GiB cgroup memory, Eclipse Temurin 25.0.4.1+1-LTS. System JVMs used `-Xms512m -Xmx3g`, G1, JFR profile recording and GC/safepoint logs. Storage is overlayfs; the underlying physical device and power-loss behavior are unknown. `force(true)` process-crash recovery depends on retained local storage honoring the operation and provides no replicated availability or disk-loss protection.

Prepare/verify before reserving the host. During a coordinator-approved quiet reservation, use the prepared mode, which fails before Maven if source or runtime artifacts changed:

```bash
MATCHCORE_PREPARED=1 MATCHCORE_MEASUREMENT_CLASS=quiet-baseline \
  MATCHCORE_COMMANDS=1000000 projects/06-matchcore/scripts/benchmark.sh core
MATCHCORE_PREPARED=1 MATCHCORE_MEASUREMENT_CLASS=quiet-baseline \
  MATCHCORE_COMMANDS=1000000 projects/06-matchcore/scripts/benchmark.sh durable
# Repeat each mode in a fresh JVM three times; do not share a journal directory.
MATCHCORE_PREPARED=1 MATCHCORE_MEASUREMENT_CLASS=quiet-baseline \
  projects/06-matchcore/scripts/jmh.sh
```

The actual reservation command was `bash .work/A06/run-reference-window.sh`, alternating core/durable for three repetitions before JMH. Exact Java argument arrays and all run locations are hashed in the envelope. Clean-checkout/global release verification remains coordinator work; no remote-CI success is inferred from this study.
