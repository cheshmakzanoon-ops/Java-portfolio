# MatchCore execution status

The matching engine, durable gateway, journal and failure suites are implemented and verified on Java 25 with effective JUnit 6.1.3. Full workload measurements and final release validation remain open. This is not a completed project release.

The coordinator currently owns the write-paused project. Workers perform no Git writes. The current verified executable/build fingerprint is `184d3914a93771b32b5198093a4bba9c6347480f92c2d7dcf969c7af2a40bc27`; its method and exact command are recorded in [current verification evidence](../evidence/verification-current.json).

| Acceptance | State | Evidence |
| --- | --- | --- |
| P06-A1 | PASS | Original indexed price-time book agrees with an independent flat-list/BigInteger oracle across 120 histories of 350 transitions. |
| P06-A2 | PASS | An additional 12,000 invariant transitions, priority/reduction/replacement, FOK, self-trade prevention, fixed-point risk and maximum-supply cases pass. Core coverage is 258/261 lines and 178/190 branches, above the unchanged 85%/75% gates. |
| P06-A3 | PASS | Real child-process SIGKILL at BEFORE_APPEND, DURING_FRAME, AFTER_FORCE and AFTER_APPLY; force-before-result, stable retries and recovered-prefix comparisons pass. |
| P06-A4 | PASS | Every-byte torn-tail and corruption tests, checksummed snapshot fallback, second-writer refusal, consistent read barriers and bounded admission pass. Signed malformed identity/time/audience cases and HTTP overload responses also pass. |
| P06-A5 | IN_PROGRESS | Two complete catalog lifecycle/demo/reset rounds passed with exact totals and replay digest. Full million-command core/durable measurements and JMH remain open. |

On 2026-10-18 at 01:42:18 UTC, the combined `-Pintegration,mutation verify` passed 19 test methods, with no failures, errors or skips. Jupiter and jqwik now use separate test classes, so their XML reports preserve every method. Targeted Book mutation testing killed 119 of 133 mutants (89.47%); 14 survivors remain visible, with no uncovered mutants, timeouts or runner errors. [All surviving mutations](../evidence/mutation-survivors.json) remain in the denominator.

Two catalog rounds executed `up`, `demo`, `reset-demo` and `down`, each exiting zero. Both demos recovered the interrupted durable command and produced four executions, eight lots, Alice cash 19,200, Alice lots eight, zero open orders and 12 results. The replay digest and raw transcript locations are in [demo evidence](../evidence/demo-current.json). These are current-instance checks; clean-checkout repetition is still required.

A05 independently inspected the production identity guard, positive-consumption invariant, reduction/FIFO/depth assertions and prior journal/security fixes. Its scoped closure is recorded in [the review](REVIEW.md). It does not approve unmeasured performance or pending release work.

## Remaining work

- Run one million commands across 64 accounts in core and durable modes separately, with the specified 60/25/15 mix, duplicate schedule, 100,000-open-order cap and price distribution. Perform three independent quiet runs per mode and retain the business-result histogram; unsuccessful cancels/replacements must remain visible.
- Run `scripts/jmh.sh` with its declared three forks and actual allocation results. Capture CPU, GC, heap, storage, JFR, recovery, source identity and variability for system measurements. The 100-command smoke is only driver validation.
- Review measured bottlenecks and strengthen the workload if its actual result distribution exposes an unrepresentative scenario. Preserve the prescribed mix and disclose any general generator revision before measuring.
- Complete source-frozen review of measurement changes, a clean checkout, independent/root builds and remote CI. Rewrite the performance/case study from actual results before marking P06-A5 or the project release complete.

Historical `coordinator-integration.json` and first mutation reports describe older snapshots; use the explicitly dated current evidence above for the latest baseline. Raw logs and recordings stay under ignored `.work`/`.runtime` paths. POSIX local durable storage, a finite run cap and one writer are deliberate boundaries; no replicated availability or physical power-loss guarantee is claimed.
