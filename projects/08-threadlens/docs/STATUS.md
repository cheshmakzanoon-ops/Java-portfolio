# ThreadLens acceptance status

A08 has paused **all writes** under `projects/08-threadlens/**` and `.work/A08/**` after final verification. The coordinator may review, stage and commit these paths. No A08 process remains running. The full reference overhead study is still mandatory and has not started; this is a scheduling handoff, not a completion claim for P08-A5.

| Gate | Observed status | Evidence |
| --- | --- | --- |
| P08-A1 deterministic fixtures, replay and live equality | PASS | Analysis fixtures and actual stopped live/offline replay tests |
| P08-A2 planted causes in at least 6/7 pairs | PASS: CPU 7/7, allocation 7/7, contention 7/7 | `evidence/planted-cause-trials.json`: 42 fresh JVMs, seed 80842, 1-second warmup and 4-second measurement |
| P08-A3 transformation preservation | PASS: 48 supported cases | Per-case registry/probe evidence, outputs, same-object exceptions and finally effects; separate child `-Xverify:all` |
| P08-A4 recoverable observer faults | PASS | Saturation, failed probes/transforms, malformed/unwritable startup, unavailable events, consumer exceptions and shutdown conservation |
| P08-A5 full reference overhead | NOT_RUN | 84 fresh JVMs: four workloads × seven randomized three-mode blocks × 30-second warmup + 60-second measurement; 126 minutes fixed observation plus startup |
| P08-A6 full observer stress | PASS: 1,204.969420742 seconds | `evidence/observer-stress.json`: live JFR, production queue/analysis, explicit synthetic cardinality, slow consumer, real platform/virtual workloads and selected startup agent |

Final verification completed with exit 0 at **2026-10-18T01:51:06Z**:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/08-threadlens/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A08/m2 -B -ntp -Pmutation com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply verify org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
```

Actual environment: Eclipse Adoptium HotSpot **25.0.4.1+1-LTS**, Linux 6.18.55 x86-64; Jupiter API **6.1.3**, confirmed in Surefire classpath. **100 tests, zero failures/errors/skips**. Probe coverage: 81/84 lines (96.43%), 33/34 branches (97.06%). Analysis: 246/246 lines (100%), 132/138 branches (95.65%). Selected instrumentation algorithm classes each pass 85% line / 75% branch coverage; exact counters are in `evidence/verification.json`. Lifecycle/config adapters remain visible in whole-module coverage and have risk-based packaged-agent JVM tests.

Targeted PIT: **163/165 killed (98.79%)**, two survivors, zero uncovered. Both survivors change one variance factor from subtraction to addition: equivalent after summation in exact arithmetic because Σx = n·mean; floating-point rounding differences remain possible. They remain labelled SURVIVED. Nonzero XML/HTML: `analysis/target/pit-reports/{mutations.xml,index.html}`. Coverage XML/HTML: each module's `target/site/jacoco/`. Log: `.work/A08/final-certification-8.log`.

Final verification fingerprint: `ad35aee1cc5395e36b1cde9ddac88db635ac78be26a1c51f963b834153fbb351`. Git HEAD alone does not identify the working implementation. This fingerprint includes source/contracts/scripts/root build inputs; the launcher cache fingerprint uses source/POM/scripts/wrapper inputs and is separately labelled.

The full observer run passed 15 actual assertions: peak observed heap **200,043,080 B**; queued payload **29,225,528 B**, below 64 MiB; output **32,222,726 B**, below 512 MiB; retained JFR **27,365,839 B**; 10,000 methods and 900 windows. It recorded **46,361,673 queue drops**, and all retained windows were incomplete. All four host workers progressed without failure. The agent remained healthy with zero timing drops/discards/probe failures. Its **174,659** timings exactly match monitor worker operations **87,210 + 87,449**; 164,659 omitted raw-prefix rows remain represented in full histograms. Live/replayed normalized exports excluded fixture secrets. Raw artifacts and observed command: `.work/A08/final-observer-stress-6`.

This was shared-host boundedness evidence. Synthetic ingress is explicit, not native CPU sampling; its offer count is not the denominator for total queue offers. Fifteen-minute recording retention means the retained file covers less than the twenty-minute live aggregate. Central test-only BOM maintenance, removal of a redundant assertion and one clarified report label followed the archived run. `evidence/stress-final-component-identity.json` confirms unchanged collector/probe/transformer/aggregation/settings and a byte-identical agent JAR (`86fd35575c882790dc3d3a8aced428a51a9ec136b6f2b1b443a6b6ae184481f8`). Only `ReportDocument.class` changed its timing label; implicit synchronized-method acquisition before bytecode is excluded, while explicit in-method block waiting remains inside the timing boundary.

Final demo8 passed eight real workload JVMs and four differential reports (`evidence/demo.json`). Strict Draft 2020-12 validation with jsonschema 4.26 passed **52 actual reports**, including all eight final demo timing labels (`evidence/schema-validation-final.json`). Benchmark smoke passed 12 JVMs but is explicitly ineligible: zero warmup, two-second measurement and shared host (`evidence/benchmark-smoke.json`).

A07 independently verified the final 100-test XML totals, stress-envelope hash, byte-identical agent, monitor-operation/timing equality and explicit incomplete-window/drop evidence. No new high finding was reported. Earlier review findings R1 (shutdown tail/import loss) and R2 (actual limits versus ceilings) were verified resolved. See `docs/REVIEW.md`. Windows 25 x86-64 and macOS 25 arm64 remain UNTESTED; no runners were used. Other JDKs/JVMs, native images and dynamic attachment are outside verified support.

Historical failures remain preserved: per-event TSV reached 256 MiB during a 42-second allocation diagnostic; bounded HDR output then retained 7,779,166 accepted events without loss in approximately 275 KB. These are shared-host output diagnostics, not throughput comparisons. Invocation5 failed an 80% visitor line-coverage gate; a real method-ceiling regression closed it before successful invocations6–8. The older aggregation-only 1,205-second stress is supporting evidence only. Historical details/logs remain under `.work/A08` and timing-output evidence.

The prepared reference inputs are `.work/A08/reference-study-8-inputs/manifest.json`: copied immutable production/dependency JARs, source archive, functional certificate, SHA-256 identities and the exact full command. The benchmark has **not started**. After explicitly reserving a quiet host, the coordinator runs:

```bash
THREADLENS_REFERENCE_RESERVATION='actual coordinator reservation and host conditions' \
  python3 .work/A08/execute-prepared-reference-8.py
```

The executor verifies immutable inputs/JDK, rejects active Maven or reused output, runs the unchanged full protocol, checks 84 completed JVMs and all eight workload/mode throughput and p99 gates, and preserves failures. Output: `.work/A08/reference-study-8`; progress: `run-order.json`; launcher log: `.work/A08/reference-study-8-inputs/launcher.log`. No shortened or mixed-only substitute is authorized. If a gate fails, preserve it and improve the implementation, then rerun with fresh inputs/output. Root owns all Git/CI/publication work; A08 performed no Git mutations.
