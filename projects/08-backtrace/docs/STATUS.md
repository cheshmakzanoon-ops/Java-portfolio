# Verification status

Backtrace has functional, transformation, privacy and boundedness evidence on Java 25 HotSpot/Linux x86-64. The first complete 84-JVM overhead study failed P08-A5. A complete study of the subsequent configuration remains unexecuted; no performance acceptance or speedup is claimed.


## Stack export, flame graph and a flaky-test repair (2026-10-29)

`report.html` gained a static SVG flame graph, and `export folded|speedscope|diff` converts a finished report's sampled stacks for flamegraph.pl, inferno and speedscope ([operations](OPERATIONS.md#exporting-stacks-to-other-profilers)). Every export sums to the report's sample count, with explicit `[truncated]` and `[unattributed]` leaves for samples the bounded tree kept only as a prefix. The text forms drop per-JVM numbers in hidden-class names so a baseline and a candidate recording align; speedscope keeps exact names. `ComparisonCommand`'s file rules moved into a shared `SafeFiles` helper that `export` reuses (the comparison tests pass unchanged).

Executed against this source with `-Pintegration,mutation verify` (JDK 25.0.4.1): **236 ordinary test methods and 7 packaged-JVM integration methods, zero failures, errors or skips**; the repository's test and mutation checkers accepted the fresh reports. The new classes (`FlameExport`, `FlameSvg`, `ExportCommand`, `SafeFiles`) were added to the mutation scope rather than left outside it: strict mutation now reports **report 287 / 328 killed (87.50%) and cli 45 / 45**, with probe 93.20%, analysis 96.65%, jfr 82.24% and agent 85.19% unchanged in kind and all above the 70% gates (841 killed overall; 81 survivors, 16 uncovered and four timeouts remain in the denominator). The first mutation run of the new classes produced a memory-error outcome from a loop-increment mutant in descriptor parsing, which the repository checker treats as a failure; parameter parsing is now bounded at the JVM's 255-parameter limit and the mutant is killed. The per-mutant inventory in `evidence/deep-mutation-survivors.json` was **not** regenerated and describes the 21 October source.

`ObserverStressTest.cleanupStopsBothHostThreadKindsEvenWhenTheCallerIsInterrupted` was flaky: it released the stress hosts immediately after interrupting the closing thread, so a late interrupt could arrive after the last blocking call and no `InterruptedException` was reported. Under four busy-loop processes it failed **132 of 400** repetitions; after the test waits for the interrupt to be consumed before releasing the hosts it passes **400 of 400** under the same load. This was a test race, not a product defect.

Also run: `./scripts/portfolio.sh demo backtrace` (eight workload JVMs, real JFR recordings; its assertions passed) and `export folded|speedscope|diff` on its reports (folded totals equal each report's `sampleCount`: 243 and 246; aligning lambda names reduced the CPU differential from six lines to four). The flame graph was rendered in headless Chromium at 1280 px and 390 px for one demo report (no page overflow, no console errors); the differential page's layout was not re-inspected. No overhead study or benchmark was run.

## Development candidate: bounded per-thread probe quiescence

The probe hot path no longer increments/decrements one globally shared atomic counter
for every instrumented exit on the first 2,048 participating threads. Each registered
thread publishes a volatile in-flight mark before the required second enabled check;
subsequent short-lived threads fall back to the original global atomic reservation
path. The observer scans the bounded marks only during quiescence. Registration is
bounded and does not capture application objects or retain a process-wide thread list.

A local Java 21 compilation and four executable concurrency scenarios passed:
24 shutdown/ring-conservation rounds, paused publisher vs seal, 2,100
short-lived participating threads exercising overflow registration, and failure
cleanup. These earlier checks were development smoke tests only. The 21 October
Java 25 verification below now supplies ordinary tests, packaged observer startup
and strict mutation evidence for the current probe. The **full 84-JVM overhead study**
and long live stress remeasurement remain unexecuted. P08-A5 remains FAIL until
the documented gate is met.

## Current source revalidation after toolchain activation repair

The complete Java 25 integration and mutation reactor passed again on
**2026-10-21 at 20:39:17 UTC**, using the corrected `PORTFOLIO_TOOLS_DIR`
activation path. The [final source receipt](../evidence/job-readiness-final-20261021.json)
binds all 93 source/build inputs and fresh reports to this invocation: **208 ordinary
methods, seven packaged-JVM integration methods, and 718/811 killed mutations**,
with zero failed, errored or skipped tests. Every selected mutation module meets
its unchanged 70% threshold. All 74 survivors, 15 uncovered mutations and four
timeouts remain counted. An interrupted execution-service attempt and a retry
blocked by the restricted environment's socket permissions are recorded separately;
neither is counted as successful verification.

The earlier comparison receipt below remains unchanged and identifies its own
source snapshot. The only source/build input changed between those two receipts
is the shared activation script. This revalidation does not rerun overhead,
long stress, interactive-browser or native-platform protocols.

## Earlier development validation: structured comparisons

The Java 25 build completed on **2026-10-21 at 20:00:36 UTC** with **215 test methods:
208 ordinary methods and seven packaged-JVM integration methods; zero failures,
errors or skips**. The [source-bound receipt](../evidence/job-readiness-diagnostics-20261021.json)
records source/build hashes, per-module test counts and strict mutation outcomes.

Differential reports now compare retained execution proportions, weighted allocation
bytes, monitor durations, park durations and selected method timings in separate
ranked tables. Optional JSON export preserves every timing quantile and both coverage
summaries. Presence flags distinguish an absent aggregate from an observed zero;
missing quantiles stay null, and no recording-span rate is invented. CLI publication
protects input files and stages both requested outputs before individual atomic moves.

New regressions exercise long-integer extremes, 20,000-subject comparison unions,
independent sample denominators, immutable snapshots, null/zero baselines, missing
timings, coverage losses, successful producer counters, differing collection limits,
hostile identifiers, input/output aliases, hard links, invalid manifests and malformed
reports. Report ingestion now rejects trailing documents and scalar coercions;
aggregate validation rejects overlapping windows, duplicated window samples and
positive measurements without an event. The standard 70% strict module thresholds
are unchanged. The report and CLI mutation targets were expanded to cover the new
comparison calculation and output ownership code.

| Module | Killed / generated | Strict killed percentage |
| --- | ---: | ---: |
| probe | 97 / 103 | 94.17% |
| analysis | 173 / 179 | 96.65% |
| jfr | 125 / 152 | 82.24% |
| agent | 115 / 135 | 85.19% |
| report | 168 / 202 | 83.17% |
| cli | 40 / 40 | 100.00% |
| Total | 718 / 811 | 88.53% |

All **74 survivors, 15 uncovered mutants and four timeouts** remain in the denominator.
These results do not establish complete fault detection. Current HTML escaping,
fragment navigation targets and local scrolling containers pass automated structural
assertions. A fresh Chromium installation was attempted but its download returned
truncated archives, so current browser layout/keyboard checks are not claimed.
The full observer-overhead and long stress protocols were not rerun; P08-A5 remains FAIL.

## Prior development validation (20 October)

The 20 October implementation separated live event processing, startup instrumentation ownership, child-JVM termination and report validation into explicit components. Regressions covered sealed admission, bounded drain, callback-triggered shutdown, failed startup rollback, duplicate agent declarations, directory-budget escapes, incomplete observations, immutable aggregation and adversarial report input. [Architecture](ARCHITECTURE.md) links production components; [review findings](REVIEW.md) describes the concrete failure schedules and corrections.

That build/integration run passed on **2026-10-20 at 09:44:12 UTC: 164 test methods, comprising 157 Surefire methods and seven packaged-JVM integration methods; zero failures, errors or skips**. The latter include real startup failure, duplicate-agent and host-survival checks. [20 October verification](../evidence/mobile-report-verification.json) binds source/build hashes, commands, test suites, strict mutation outcomes and log identities.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/08-backtrace/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A08/m2" -B -ntp -Pintegration verify
```

The combined integration/mutation campaign passed at **09:22:14 UTC**. Its 19 targeted production source files and six mutation reports were hash-checked unchanged after the final report-rendering rebuild. The later timeline-width correction changed rendering outside those targets and was verified by ordinary/integration and browser checks; no additional mutation campaign was run. Strict results count only killed mutations as successes; surviving, uncovered and timed-out mutations remain in the denominator. Existing 70% module gates are unchanged.

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

The rebuilt standard and differential reports also passed actual Chromium checks at 1280×960 and 390×844. All four cases verified fragment navigation, skip-link focus, literal rendering of hostile identifiers, local mobile table scrolling, no viewport overflow and no console errors or remote requests. Native stack expansion and keyboard table scrolling were exercised. Epoch-nanosecond cells retain useful width and stay on one line; the mobile fixture verifies full 19-digit values without clipping or global overflow. [Browser receipt](../evidence/mobile-report-browser.json) identifies the generated HTML and screenshots; the reproducible harness is documented in [operations](OPERATIONS.md#live-jfr-and-offline-reports).

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
