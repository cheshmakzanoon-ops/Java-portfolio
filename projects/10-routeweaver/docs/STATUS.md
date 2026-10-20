# RouteWeaver validation status

RouteWeaver implements immutable bounded dispatch inputs, independent feasibility validation and a complete tiny-instance oracle, original route moves, Timefold improvement, PostgreSQL solve jobs with lease fencing, explicit publication, tenant-scoped HTTP, and a browser dispatcher. Repository-wide release certification remains separate from project checks.

## Current changes

The current hardening adds a development-mode browser boundary against cross-origin mutations and DNS rebinding, a same-origin Content Security Policy, early publication/replanning request validation, and a readable responsive dispatcher. The UI keeps tokens in page memory, gates actions by durable job state, polls serially, and discards progress responses after connection details change. Unassigned jobs have readable reason labels in an accessible table; raw validation payloads and detailed score provenance are expandable. JavaScript and CSS now participate in the launcher source fingerprint.

The final ordinary verification passed 41 methods at 07:32:37 UTC on 20 October 2026. The subsequent full integration reactor passed at 07:36:31 UTC: **55 methods, comprising 41 ordinary and 14 PostgreSQL, with no failures, errors, or skips**. It includes malformed publication/replanning rejection before persistence and real HTTP static-asset/CSP assertions. [Current hardening evidence](../evidence/hardening-current.json) binds source and report hashes.

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

Project-scoped hardening verification and the live-service browser check are complete. Fresh-checkout, repository-wide CI, hosted security scans, and release certification remain separate checks. Historical measurements need a new complete run before being attributed to changed runtime source.
