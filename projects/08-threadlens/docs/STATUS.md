# Verification status

ThreadLens has functional, transformation, privacy and boundedness evidence on Java 25 HotSpot/Linux x86-64. The first complete 84-JVM overhead study failed P08-A5. A complete study of the subsequent configuration remains unexecuted; no performance acceptance or speedup is claimed.

## Current development validation

The current implementation separates live event processing, startup instrumentation ownership, child-JVM termination and report validation into explicit components. Regressions cover sealed admission, bounded drain, callback-triggered shutdown, failed startup rollback, duplicate agent declarations, directory-budget escapes, incomplete observations, immutable aggregation and adversarial report input. [Architecture](ARCHITECTURE.md) links all 36 production classes; [review findings](REVIEW.md) describes the concrete failure schedules and corrections.

The final build/integration run passed on **2026-10-20 at 09:27:08 UTC: 164 test methods, comprising 157 Surefire methods and seven packaged-JVM integration methods; zero failures, errors or skips**. The latter include real startup failure, duplicate-agent and host-survival checks. [Current verification](../evidence/deep-verification.json) binds source/build hashes, commands, test suites, strict mutation outcomes and log identities.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/08-threadlens/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A08/m2" -B -ntp -Pintegration verify
```

The combined integration/mutation campaign passed at **09:22:14 UTC**. Its 19 targeted production source files and six mutation reports were hash-checked unchanged after the final report-rendering rebuild. Strict results count only killed mutations as successes; surviving, uncovered and timed-out mutations remain in the denominator. Existing 70% module gates are unchanged.

| Module | Killed / generated | Strict killed percentage |
| --- | ---: | ---: |
| probe | 81 / 91 | 89.01% |
| analysis | 173 / 179 | 96.65% |
| jfr | 125 / 152 | 82.24% |
| agent | 115 / 135 | 85.19% |
| report | 85 / 109 | 77.98% |
| cli | 14 / 14 | 100.00% |
| Total | 593 / 680 | 87.21% |

The [non-killed mutation inventory](../evidence/deep-mutation-survivors.json) preserves 72 survivors, 12 uncovered mutations and three timeouts. These outcomes are limitations of the selected tests, not evidence of equivalence or complete fault detection.

The rebuilt standard and differential reports also passed actual Chromium checks at 1280×960 and 390×844. All four cases verified fragment navigation, skip-link focus, literal rendering of hostile identifiers, local mobile table scrolling, no viewport overflow and no console errors or remote requests. Native stack expansion and keyboard table scrolling were exercised. [Browser receipt](../evidence/deep-browser.json) identifies the generated HTML and screenshots; the reproducible harness is documented in [operations](OPERATIONS.md#live-jfr-and-offline-reports).

Long stress, planted-cause and full overhead studies were not rerun. The historical results below certify only their recorded implementation and settings.

## Recorded correctness and boundedness evidence

| Scope | Recorded outcome | Evidence |
| --- | --- | --- |
| Earlier development validation, 2026-10-20 07:38 UTC | 121 methods, including six packaged-JVM integration methods; zero failures/errors/skips | [Validation](../evidence/quality-validation.json) |
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
