# Verification status

ThreadLens has functional, transformation, privacy and boundedness evidence on Java 25 HotSpot/Linux x86-64. The first complete 84-JVM overhead study failed P08-A5. A complete study of the subsequent configuration remains unexecuted; no performance acceptance or speedup is claimed.

## Current development validation

The current review hardens timing imports against duplicate method identities and oversized unterminated rows, and prevents startup instrumentation from mixing old output artifacts with a new run. A real packaged-JVM regression checks host survival and preservation of existing observations. Offline reports now provide keyboard navigation, semantic page structure, responsive tables and print styling. The full build/integration run passed on 2026-10-20 at 07:38:10 UTC: **121 methods, zero failures, errors or skips**, including six independent packaged-JVM integration methods. [Validation evidence](../evidence/quality-validation.json) binds source hashes, command, suite results and log identity. Report validation includes generated HTML structure/fragment checks and the existing escaping and deterministic round-trip tests.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/08-threadlens/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A08/m2" -B -ntp -Pintegration verify
```

Current-source mutation, long stress, planted-cause and full overhead studies have not been rerun. The historical results below certify only their recorded implementation and settings.

## Recorded correctness and boundedness evidence

| Scope | Recorded outcome | Evidence |
| --- | --- | --- |
| Repair verification, 2026-10-18 | 118 methods: 113 Surefire and five packaged-JVM integration methods; zero failures/errors/skips | [Verification](../evidence/repair-verification-v3.json) |
| Selected mutations from that run | 256 killed / 266 total (96.24%); ten survivors; zero uncovered, timeout, error or nonviable outcomes | [Verification and source identities](../evidence/repair-verification-v3.json) |
| Transformation preservation | 48 supported cases, including wide returns, recursion, handler precedence and same-object exceptions; child JVM verification enabled | [Compatibility](../evidence/compatibility.json) |
| Planted causes under v3 settings | CPU 7/7, allocation 7/7 and contention 7/7; 42 workload JVMs and 42 sequential analyzers | [Cause trials](../evidence/planted-cause-v3.json), [raw index](../evidence/planted-cause-v3-raw-index.json) |
| Full v3 observer stress | 1,202.669 seconds; bounded retention and exact accepted timing conservation, with incomplete event windows | [Stress](../evidence/observer-stress-v3.json), [raw index](../evidence/observer-stress-v3-raw-index.json) |
| Earlier demonstrations and report schema validation | Eight workload JVMs, four comparisons and 52 validated reports | [Demo](../evidence/demo.json), [schema validation](../evidence/schema-validation-final.json) |

The historical v3 stress retained 10,000 methods and 900 windows. Observed peak heap was 208,660,048 bytes, conservative queued payload 27,679,440 bytes, observer output 34,529,357 bytes and retained JFR 29,677,582 bytes. Accepted reservations and summarized timings both equaled 271,396 monitor operations; timing drops, shutdown discards, unfinished exits, probe faults and unaccounted replay timings were zero. The omitted 261,396 raw-prefix rows remained represented in full histograms.

The event pipeline also recorded **58,999,215 queue drops**, overflow and clipping. All 900 retained windows were incomplete. Four host workloads progressed, and normalized exports excluded the fixture secret. This demonstrates bounded behavior under the recorded shared-host conditions; it does not establish complete profiling coverage or acceptable overhead.

## Failed overhead study

The first full study completed on **2026-10-18 at 06:25:14 UTC**, after 7,720.023 seconds. All 84 workload JVMs completed the protocol; the executor returned `3` because the overhead gates failed. [The original study](../evidence/overhead-study-first.json) preserves every comparison and uncertainty interval.

| Workload | Instrumented median throughput change | Instrumented median p99 change |
| --- | ---: | ---: |
| CPU | −4.698% | +32.506% |
| Allocation | −10.489% | +100.408% |
| Contention | +3.322% | +177.362% |
| Virtual threads | −8.498% | +106.880% |

JFR-only allocation p99 (+15.315%) and virtual-thread throughput/p99 (−13.534% / +29.690%) also failed. Platform-contention and virtual-thread probe drops were 35 and 26 respectively, and remain explicitly incomplete. Wide intervals do not override the prescribed median limits.

The subsequent `v3-allocation50-park-class-only` configuration lowers weighted allocation sampling from 150/s to 50/s and omits park stacks while retaining parked class/duration. It passed the historical cause and stress checks above. Its full 84-JVM overhead remeasurement remains open. Safe shutdown also adds atomic in-flight accounting; its runtime cost must be measured, not assumed negligible.

## Remaining validation

Run the complete seven-block, four-workload, three-mode overhead study on a quiet host, retaining all trials and actual producer settings. Each JVM requires 30 seconds of warmup and 60 seconds of measurement: 126 minutes of fixed observation time, plus launch and output costs. Short demos and smoke runs cannot substitute for it.

New runtime changes require appropriate fresh correctness and boundedness checks. Clean-checkout/full-reactor verification, hosted CI and release validation remain separate obligations. Windows 25 x86-64 and macOS 25 arm64 are untested; other JVMs/JDKs, native images and dynamic attachment are outside verified support.
