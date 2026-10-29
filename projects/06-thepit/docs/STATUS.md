# Verification status

Current evidence covers deterministic matching, exact retry ownership, checkpoint consistency, fail-stop durability, bounded admission and HTTP identity/resource boundaries. Historical measurements below retain their original source identities; no new performance study was run.

## Update 2026-10-29: market data and operator tooling

This update adds code after the 2026-10-20 receipt below, so that receipt does not describe the current source. Executed on JDK 25.0.4.1 (checksum-pinned Temurin) and the wrapper's Maven 3.10.0, no Docker:

| Check | Result |
| --- | --- |
| `portfolio.sh verify thepit` (formatting, JaCoCo gates, report-freshness checks, all modules) | passed; 79 ordinary methods: matching-core 25, journal 18, gateway 19, simulator 16, benchmarks 1 |
| `-Pmutation` (unchanged 70% gate) | passed; matching-core 306 killed of 336, journal 138 of 159, gateway 85 of 93 |
| Asserted demo | still prints `PASS executions=4 lots=8 aliceCash=19200 aliceLots=8 openOrders=0 results=12` with digest `4e226aa1…959e3` |
| Architecture scan, source and bytecode | 0 violations |

New code is covered by independent checks rather than self-comparison: market data against recomputation from raw orders and executions over 60 generated histories, account fill pages against an independent filter for every offset, limit and cutoff, checkpoint and replay recovery equivalence, a concurrent-reader test that fails when stale statistics are published with a newer view, every torn byte and corruption offset against both journal readers, and a replay of the asserted demo journal that must reproduce its recorded digest. Mutation survivors in the new code are two equivalent boundary mutants in `Tracker`; the remaining survivors in `JournalReader` are boundaries of the frame parser moved unchanged from `Journal` (run-cap and short-read limits that cannot be reached with a bounded test). Recorded mutation counts are for this source only; the integration profile, the process-kill checks and the performance study were not rerun.

## Current verification: 2026-10-20

Executable/build-input fingerprint: `385404601a15385beaf36830dd0ce4628038164f21ef6fdcfd5bcbb061ea13fb`.

The six-module `-Pintegration,mutation verify` build passed **45 test methods with zero failures, errors or skips**, including two separate-process integration methods. Formatting and the unchanged core coverage gates passed on JDK 25.0.4.1 / Maven 3.10.0. Core line coverage was 99.26%; branch coverage was 91.27%.

[The current receipt](../evidence/deep-verification.json) binds source, shared POM, suite reports, mutation reports and the raw local run log. [Every remaining mutation](../evidence/deep-mutation-outcomes.json) is listed explicitly. All three selected modules passed the unchanged 70% kill threshold; there were no mutation timeouts, memory errors, run errors or non-viable outcomes.

| Selected module | Killed | Survived | Uncovered | Killed / all outcomes |
| --- | --- | --- | --- | --- |
| `matching-core` | 259 | 24 | 0 | 91.52% |
| `journal` | 126 | 18 | 6 | 84.00% |
| `gateway` | 73 | 5 | 3 | 90.12% |

Survivors are not presumed equivalent. Core selection includes Book/BookCheckpoint and Model, journal selection includes Journal, and gateway selection includes Gateway. HTTP identity/resource tests and the independent simulator execute in ordinary/integration verification; their production classes are outside these selected mutation targets.

| Boundary | Current evidence |
| --- | --- |
| Independent matching correctness | 120 generated histories of 350 transitions, comparison after every command; separate 12,000 conservation transitions |
| Atomic checkpoint restore | Invalid balance/reservation/order/priority/result/history matrices, valid round trips and invalid authority followed by valid restore on the same instance |
| Exact retry identity | Unequal commands sharing the legacy v1 hash reject as conflicts while pending, committed and after real checkpoint restart; exact retries retain the original result |
| Caller ownership and concurrency | Cancellation/forged completion isolation, callback monitor assertion, force/apply latches, exact queue/run-cap and waiting-read publication |
| Storage durability and failure ownership | Independent golden frames and CRCs; observed real journal/snapshot/directory/repair force calls; partial I/O; lock/force/release/close faults; suppressed cleanup errors |
| Crash recovery | Actual child JVM SIGKILL at BEFORE_APPEND, DURING_FRAME, BEFORE_FORCE, AFTER_FORCE and AFTER_APPLY; full independent durable-prefix comparison |
| HTTP protection | Real RSA issuer/JWKS, original claim types, issuer/audience/time/scope/account checks, strict JSON/query shapes and no-store responses |
| Incomplete uploads | Separate supported HTTP CLI process, all four handlers occupied by incomplete bodies, bounded socket closure and subsequent successful durable command |

Reproduce from the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/06-thepit/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A06/m2" -B -ntp -Pintegration,mutation verify
./scripts/portfolio.sh demo thepit
```

No Docker or external credentials are needed. [The current asserted demo](../evidence/deep-demo.json) retains four executions, eight traded lots, Alice cash 19,200 and inventory eight, zero open orders, twelve results, and reference/replay digest `4e226aa11dbed09f79d746c5df50ac857e30804a2e43d3ccf39893d656f959e3`. The demo injects a storage fault; the separate integration test performs actual process kills.

An earlier parser-boundary pass at fingerprint `094f64bbceacdd785ff28414c955a5463647ab23f136d94bba682e52ce436d28` passed 24 methods. The current receipt supersedes that validation for current executable source without changing its historical scope.

## Operating limits

Runs retain at most 1,100,000 unique command results and exact original commands; books hold at most 100,000 open orders. Checkpoints are at most 16 MiB and remain optional. Exact authority is reconstructed from already-validated journal records without rematching the checkpoint prefix. Standalone state-only Book restore cannot authorize historical retries; use the original-command overload. No old persisted v1 hash or journal schema was changed.

Recovery assumes retained local storage honoring force. Checksums, ancestry hashes and semantic validation provide consistency checks, not authenticity against a filesystem writer. CLI HTTP deadlines use JVM-wide periodic JDK settings; embedded hosts own that policy. There is no replication, disk-loss recovery, hardware power-loss certification or production latency guarantee.

## Historical mutation and artifact checks

The 2026-10-18 full integration/mutation run passed 21 methods and killed **119/133 selected `Book` mutants**, leaving 14 survivors and no uncovered mutations, timeouts, or runner errors. [The survivor report](../evidence/mutation-survivors.json) retains all outcomes; equivalence is not assumed. That report predates later boundary work; current broader mutation results appear above.

Seventeen artifact-integrity probes checked source binding, compiled/resource bytes, runtime classpath, dependency JARs and symlink rejection. [The receipt](../evidence/runtime-artifact-cache.json) records 67 bound artifacts for the earlier build. Current source changes intentionally invalidate older prepared-artifact receipts; supported scripts rebuild and verify before reuse.

## Historical reference study

Executable/build-input fingerprint `62fc0b0351864486ffa736bd64f444393e3dda894f93677c7ca95448dab1ce83` identifies the completed historical study. It ran three one-million-command repetitions in core mode, three in forced-journal mode, and three-fork JMH. Every full run produced the same replay/reference digest and business histograms. No repetition was discarded.

The workload peaked at **27 open orders**, despite a configured 100,000-order cap. The results therefore do not establish deep-book performance. Fixed-seed generation, closed-loop sampling, process-wide profiling boundaries, and warm recovery remain explicit limitations in [performance](PERFORMANCE.md).

[The measurement envelope](../evidence/reference-20261018/measurement-envelope.json) and [review](REVIEW.md) retain identities and arithmetic checks for 83 raw artifacts and 67 runtime artifacts. Large recordings, journals and logs were preserved in a verified local archive. [The manifest and recovery/removal receipts](../evidence/reference-20261018/README.md#restore-the-original-raw-files) explain physical restoration of all 98 files and 15 directories. That local archive is not included in a Git clone. The derived CSV's CRLF and LF identities remain recorded; parsed values and original raw identities are unchanged.

## Release limits

Clean-checkout reproduction, repository-wide checks, hosted CI, and release publication require their own evidence. Current local verification does not imply a production service-level objective, hardware power-loss proof, exchange certification, or complete repository release. Historical failures and smoke captures remain separate from successful reference measurements.
