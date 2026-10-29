# Switchback validation status

Switchback implements immutable bounded dispatch inputs, independent feasibility validation and a complete tiny-instance oracle, original route moves, Timefold improvement, PostgreSQL solve jobs with lease fencing, explicit publication, tenant-scoped HTTP, and a browser dispatcher. Repository-wide release certification remains separate from project checks.

## Faster, wider local search (2026-10-29)

`Heuristics.improve` (the search that runs between the insertion baseline and Timefold) now (1) skips candidate moves whose exactly computed soft-score change is not negative, before building routes, validating or spending evaluation budget, and (2) after relocate, swap and reversal converge, tries Or-opt segment moves and cross-route tail exchange ([architecture](ARCHITECTURE.md#local-search-before-the-solver)). Every accepted candidate is still validated by the independent `Validator`; the validator, model, service, persistence, fencing and publication are unchanged. `new Heuristics(false)` and `improveBasic` keep the original accounting and move set as references.

Measured in the sandbox on this source (synthetic `Fixtures` instances, "tight" windows; these are measurements of the search in isolation, not of the Timefold phase or of the service):

- **Lossless.** With an unlimited evaluation budget the pruned search returned exactly the original search's routes on all 30 instances of the test matrix and on the 18 instances of an additional size study (16-80 jobs, 3-5 vehicles, with and without disruption), and the estimator matched the validator's soft-score change on over 5,000 moves of every type.
- **Speed to the same local optimum.** 80 jobs / 5 vehicles, six instances: 51.1 s to converge with every candidate validated, 0.86 s with pruning (about 59x); 40 jobs: 2.1 s to 0.06 s (about 38x); 20 jobs: 0.52 s to 0.006 s (about 87x).
- **Quality at the production evaluation limit** (`min(2000, max(100, 10 x jobs))`): the share of baseline operating cost removed rose from 2.5% / 1.6% / 1.4% (20 / 40 / 80 jobs) to 23.8% / 12.6% / 29.3% with pruning, and to 26.3% / 18.8% / 33.9% with the wider moves. The original search exhausted that budget long before converging; with pruning the limit counts only validated, potentially improving candidates.
- **Against the exact optimum** (150 random 7-job / 2-vehicle instances the oracle solved): the original moves reached it on 136, pruning on the same 136, the wider search on 144, never worse than the original on any; mean operating-cost gap 0.34% to 0.08%. On 20-job instances the wider moves beat the original local optimum on 9 of 12.

Executed with `-Pmutation verify` (JDK 25.0.4.1; no Docker): **63 ordinary test methods (10 domain, 16 planning, 14 service, 23 verification, ten of them new), zero failures, errors or skips**, the repository's report checkers accepting them, and the unchanged `Validator` mutation gate at **82 / 82 killed**. `Heuristics` is not in the mutation scope (its tests live beside the fixtures in the verification module), so its mutation strength was not measured. **Not run:** the PostgreSQL integration suite, the browser/UI checks, the Timefold-phase benchmarks and the lifecycle demo. The 20-30 second Timefold measurements in the [performance report](PERFORMANCE.md) start from the old search's incumbent and do not describe this source; a service solve now spends more of its early time in the local search and hands Timefold a better starting plan.

## Current CSRF enforcement, 2026-10-21

The complete ordinary reactor passed **53 methods: 10 domain, 16 planning, 14 service and 13 verification**, with zero failures, errors or skips. Fresh-report completeness checks passed. The added real-HTTP regression uses a loopback RSA OIDC issuer and the production protected filter chain: bearer plus parsed or malformed Cookie headers returns `403` for POST, PUT, PATCH and DELETE before persistence. Explicit bearer POST still reaches input validation with `400`, and safe cookie-bearing GET remains `200`.

The [CSRF verification receipt](../../../docs/execution/csrf-enforcement-20261021.json) binds the changed security/test files and observed reports. It preserves the initial failure, where bearer plus cookie reached the controller with `400` instead of CSRF `403`. This run did not rerun PostgreSQL integration, mutation, packaged-browser lifecycle or performance studies; earlier results below retain their original source identities.

## Historical implementation hardening, 2026-10-20

The latest implementation separates JDBC transactions, immutable inputs, leased jobs and publication behind the compatible Store facade. It corrects caller-supplied candidate identity, shutdown/claim races, heartbeat and cancellation-observer failure handling, shared oracle search state, bounded route/time/fixture/HTTP inputs, and ambiguous JSON/error classification. Additive schema constraints protect state and candidate references. Launchers now bind artifact bytes and kernel process identity.

The final supported integration/mutation build passed at **09:02:23 UTC on 20 October 2026: 73 methods, comprising 51 ordinary and 22 real PostgreSQL methods, with zero failures, errors or skips**. All 82 selected Validator mutants were killed. Domain coverage is 242/248 lines and 129/130 branches; planning coverage is 516/534 lines and 302/342 branches. The original line/branch and mutation thresholds remain unchanged. Forty generated property checks and the exhausted 95,901-leaf oracle are included. [Deep hardening evidence](../evidence/deep-hardening-current.json) binds source `e814e426`, the parent POM, reports and packaged artifact bytes.

Eight Python artifact/process/socket regressions passed. Strict-CSP browser fixtures cover prominent partial-plan decisions, keyboard vehicle filtering, refresh/reset and stale-response schedules, cross-day service times, published-input pairing and late cancellation acknowledgement. The packaged application passed actual desktop and 390-pixel Chromium rendering, a delayed unchanged real HTTP response, solve/publication and retained-data SIGKILL recovery. Recovery advanced fence 1 to 3, retained a nondegrading valid incumbent and published nothing automatically. Teardown stopped the owned service and removed its container/network while retaining the volume.

The first immediate restart check exposed a TIME_WAIT false positive in port ownership; an actual socket regression and repeated live recovery verify the correction. One instrumented watcher fixture also exhausted its one-second setup budget; an explicit thread/latch schedule and legal thirty-second solve budget replaced that timing assumption. Failed run hashes remain recorded. No new benchmark was run.

## Previous browser and HTTP hardening

The earlier browser hardening adds a development-mode browser boundary against cross-origin mutations and DNS rebinding, a same-origin Content Security Policy, early publication/replanning request validation, and a readable responsive dispatcher. The UI keeps tokens in page memory, gates actions by durable job state, polls serially, and discards progress responses after connection details change. Unassigned jobs have readable reason labels in an accessible table; raw validation payloads and detailed score provenance are expandable. JavaScript and CSS now participate in the launcher source fingerprint.

The final ordinary verification passed 41 methods at 07:32:37 UTC on 20 October 2026. The subsequent full integration reactor passed at 07:36:31 UTC: **55 methods, comprising 41 ordinary and 14 PostgreSQL, with no failures, errors, or skips**. It includes malformed publication/replanning rejection before persistence and real HTTP static-asset/CSP assertions. [Historical hardening evidence](../evidence/hardening-current.json) binds source and report hashes.

The isolated Chromium/Playwright UI regression passed keyboard navigation, narrow-screen layout, safe text rendering, readable unassigned reasons, solve/publication states, same-input baseline, nonoverlapping polling, stale-response isolation, and absence of persisted browser credentials. This uses deterministic API fixtures under the same strict CSP. A separate live PostgreSQL demonstration and Chromium solve/publication round also passed, with six routes/timelines, stranded cargo, urgent work, same-input baseline, compact feedback, and no page errors. Teardown stopped the service and removed its container/network. The first live harness attempt failed because its eval-based wait violated CSP; locator assertions repaired the harness without weakening the policy, and the repeated live check passed. A final UI-only readability refinement was packaged through Maven resources/repackage with Java sources unchanged, then passed both isolated and live browser checks. The asset-refresh package command skipped tests and is not counted as another Java test run.

## Historical source certification

Source fingerprint `d5964723baa5e51619d4d85316f02ae3ccc370726ad7eb3b41617a200ced88d0` passed the full project reactor on 18 October 2026 with JUnit 6.1.3:

- 54 methods: 40 ordinary and 14 PostgreSQL, with no failures, errors, or skips.
- 40 generated property checks and an exhausted 95,901-leaf tiny oracle.
- 82 of 82 selected original Validator mutants killed.
- Domain coverage: 227/233 lines, 121/122 branches; planning: 507/523 lines, 297/336 branches.

The [integration record](../evidence/integration.json) and [mutation record](../evidence/mutation.json) retain their source and report hashes. Tests cover cancellation/completion races, restored immutable snapshots, stale version and concurrent publication, admission, tenant isolation, and executing-source compatibility. Incompatible recovery emits no new candidate; same-source recovery uses a newer fence.

The same historical source passed a real 60-job/six-vehicle demonstration and Chromium solve/publication check. [Lifecycle operations](../evidence/lifecycle-current/operations.json) and [browser results](../evidence/lifecycle-current/browser-result.json) preserve that scope. The live demo asserted physical cargo, completed history, urgent work, and stale publication; the browser checked six routes/timelines, visible stranded cargo, same-input baseline, and compact publication feedback. These records do not certify the redesigned UI.

Earlier failed startup-race validation used a five-second budget consumed by setup. Barrier-based cancellation assertions and a legal 30-second budget repaired the scenario; later runs passed. Earlier source/test versions remain historical in the evidence directory.

## Historical performance

The complete reference study ran on 18 October 2026 from 09:19:15.646 to 09:35:26.133 UTC at revision `49d23dae48eeffb8939730a953cd803314b8dc5a`, with unchanged source `d5964723`. It executed 30 corpus outcomes and six JMH method/size rows with three forks.

All 30 outcomes had zero hard violations and no same-input degradation; 21 were fully feasible and nine partial. All 1,483 authoritative trace points were time-ordered and nondegrading. The preregistered improvable fixture reduced operating score from 101,101 to 90,120 in each of three runs, a combined 10.8614158% gain after original moves and Timefold. The maximum observed duration was 31.552836 seconds under a cooperative 30-second setting. Three 500-job overconstrained cases spent the budget constructing the baseline, so Timefold never started. Six outcomes reached the actual step limit early.

The [sealed packet](../evidence/performance-v1/packet.json) preserves all outcomes, variability, JMH forks, 811 resource samples, and the 39-file raw manifest. The [performance report](PERFORMANCE.md) explains sparse GC observations, measured boundaries, and scaling limitations. Independent [packet review](../evidence/performance-review-current.json) covered those records; no later-source performance claim follows.

## Remaining work

Earlier project-scoped deep hardening, packaged browser and crash-recovery checks remain recorded for their original source. The current CSRF change has the ordinary verification above. Fresh-checkout, repository-wide CI, hosted security scans, and release certification remain separate checks. Historical measurements need a new complete run before being attributed to changed runtime source.
