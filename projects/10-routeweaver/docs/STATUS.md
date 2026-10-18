# RouteWeaver execution status

Full release is IN_PROGRESS. A10 exclusively owns projects/10-routeweaver/** and .work/A10/**; the coordinator owns shared files and all Git operations. A correctness milestone is not final release acceptance.

The four prescribed modules implement immutable bounded integer-unit directed/residual problems, independent validation and complete tiny enumeration, original insertion/relocate/swap/2-opt, bounded real Timefold, immutable PostgreSQL snapshots and durable bounded workers, fenced cancellation/recovery and publication CAS, protected HTTP and dispatcher UI, physical breakdown/urgent replanning, and executable demo/restart/corpus drivers. Required INVARIANTS, OPERATIONS, SECURITY, PERFORMANCE and INTERVIEW documents are substantive; architecture, decisions, case study and demo walkthrough are present.

## Current source certification

The combined reactor passed at 2026-10-18T02:50:25Z on actual JUnit 6.1.3:

- 54 methods: 40 ordinary and 14 real PostgreSQL, zero failures/errors/skips.
- 40 generated property checks and complete 95,901-leaf tiny oracle enumeration.
- 82/82 selected original Validator mutants killed, with nonzero XML and HTML reports.
- Domain coverage: 227/233 lines and 121/122 branches. Planning: 507/523 lines and 297/336 branches. Both exceed 85% line and 75% branch requirements.

Exact source fingerprint: d5964723baa5e51619d4d85316f02ae3ccc370726ad7eb3b41617a200ced88d0. Root POM SHA: 3743d1ba382977b3f8d8609e1191190f220cdf272a5338d538869f96a7b7230d. Fingerprinting includes Python runtime helpers along with project Java/POM/XML/properties/SQL/HTML/shell/YAML and root POM; generated output, Markdown and JSON evidence are excluded. No Java/POM/runtime-script edits occurred during the run.

```sh
source scripts/cloud-env.sh
./mvnw -f projects/10-routeweaver/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A10/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration,mutation,benchmarks verify
```

Raw log: .work/A10/source-compatibility-integration.log. evidence/integration.json and mutation.json contain exact per-file/report SHA, methods, coverage and mutation counts. Raw XML is preserved in .work/A10/source-compatibility-reports; PIT XML/HTML in .work/A10/pit-source-compatibility. Testcontainers PostgreSQL and Ryuk stopped after the suite; the reservation was released. No persistent service/database stack is running.

The new provenance regression actually executed queued-old-source and expired-running-old-source refusal. A mismatched binary fails the job through the fenced transaction before planning, emits no new candidate, preserves an existing historical incumbent and submitted source, and cannot use the stale fence to finish/publish. Same-source expired work completes at a newer fence with independently valid nondegradation; a new-source replacement submission completes. ADR 003 documents the conservative UI-only/rolling-upgrade tradeoff. The other PostgreSQL cases cover restore/immutability, cancellation/completion races, stale version/concurrent publication, admission, tenant isolation and protected HTTP/body bounds.

## Executed runtime evidence

A finite actual live round on earlier certified source 2aa7d7da completed successfully: three pooled-JDBC diagnostic pairs; the first and reset/repeat 60-job/six-vehicle demos (118/114 checks); real Chromium rendering, solving and CAS publication; actual service SIGKILL/restart with fence 1 → 3, valid retained incumbent and no automatic publication; explicit reset; and successful down preserving the owned volume. evidence/live.json and polling-diagnostic.json preserve raw paths, exact source, outcomes and limitations. These results are not relabeled to the current binary.

Original same-input operating cost was 227,742 baseline versus 213,881 and 213,456 optimized in the two demonstrations. Both assigned all original jobs with zero hard violations. Replanning visibly retained completed history and stranded failed-vehicle cargo, audited released commitments, assigned the urgent depot job to the compatible standby vehicle and rejected outdated publication with 409. Replanned scores describe different workloads and are not optimization-gain claims.

The actual pooled diagnostic retained identical independently valid scores and reduced JDBC reads from 2,743 each to 4/11/2 with sampling. Shared-host timing is diagnostic only. Cancellation reads are bounded to 100 ms sampling; local stop checks remain per trial and authoritative writes still check fence/lease/state transactionally.

The compact publication-status UI repair and source compatibility guard are included in the current 54-method pass. The coordinator subsequently executed the exact prepared source d5964723 at 2026-10-18T03:51Z: no-Maven artifact preflight, up, scoped reset, live demo, real Chromium compact-status check and down all returned 0. The demo made 101 actual assertions; the browser checked ten behaviors including six route polylines/timelines, stranded cargo, urgent work, same-input baseline, live solve, CAS publication, compact 107-character status and zero page errors. [Current lifecycle operations](../evidence/lifecycle-current/operations.json) record the observed command exits; browser results and screenshot/DOM metadata are preserved alongside them. The final-source UI runtime gate is closed; this shared-host functional pass makes no performance claim.

## Prepared artifacts and review

The final service and driver fat JARs, sizes and SHA are in evidence/artifacts-current.json, bound to the current source. .work/A10/preflight-no-maven.sh passed without Maven, downloads, HTTP or measurements: source/build stamp and artifact hashes, packaged HTML identity, helper syntax, self-contained 60-job/six-vehicle depot-standby fixture and discovery of both JMH methods. JavaScript syntax also passed Node. This prepares the runtime interval without consuming its CPU on compilation.

A09 closed the original cancellation-start/null-cloner, URL-safe ID and static-shell findings, then inspected strict JSON, bounded cancellation polling and same-job baseline comparison without another blocking finding. The new provenance guard was inspected and its observed 14-method PostgreSQL evidence was sent for scoped closure. REVIEW records the exact scope. Measurements are complete; A03 subsequently closed the measured packet/documentation scope; root-owned fresh-checkout/global/CI certification remains separate.

Historical passes remain preserved: 45 methods/12 PostgreSQL at 737a05c and strict-input 37 methods/82 mutants at d2636b73 used JUnit 6.0.3; the 53-method/13-PostgreSQL pass at 2aa7d7da used 6.1.3. A shared-host startup-race regression failed when its five-second test budget expired before setup; .work/A10/release-driver-preparation.log is retained. Explicit early/continued cancellation latches and a legal 30-second test budget repaired the scenario, without changing production solver code. Later full runs passed.

## Completed quiet reference measurement

The coordinator completed the entire quiet reservation on 18 October 2026, 09:19:15.646–09:35:26.133 UTC (970.488 seconds), at Git revision 49d23dae48eeffb8939730a953cd803314b8dc5a and unchanged source fingerprint d5964723. All 30 corpus outcomes and six JMH method/size rows with three forks executed. The root receipt records no other Java processes/application containers at admission or completion and an independent stopped check. This is completed measurement evidence, not a newly inferred pass from prepared scripts.

All 30 results had zero hard violations and no same-input degradation: 21 were fully feasible and nine explicitly partial. All 1,483 authoritative trace points were time-ordered and nondegrading. The preregistered improvable fixture reduced operating score 101,101 → 96,106 after original moves → 90,120 after Timefold in all three runs, a 10.8614158% combined gain with identical assigned demand. The goal was met on that fixture; no universal improvement is claimed.

The actual maximum worker duration was 31.552836 seconds under a cooperative 30,000 ms configuration. Three 500-job overconstrained cases consumed the budget before Timefold began and retained their valid partial baseline. Six outcomes ended at the actual 100,000-step cap without padding. The logs contain 27 Timefold sessions. All primary JMH rows have three measurements per fork; one secondary gc.time metric has eight values [3, 2, 3], preserved without filling. Timefold's PHASE_ASSERT mode applies to service solver sessions, separately from standalone JMH validator/import work.

The [sealed version 1 packet](../evidence/performance-v1/packet.json), SHA aef369a9ef0f2b97a2a8903af91b40ae1fe778728c90d6db86a2c6589445806b, includes all 30 outcomes/variability, exact JMH fork values, 811 service-resource samples summarized with GC, and a verified 39-file raw manifest. [PERFORMANCE](PERFORMANCE.md) reports every run and measured boundary; prior failures, source-specific lifecycles and the current 54-method/82-mutant scope remain preserved. No Java, Docker or new measurements were started during the offline analysis.

## Remaining release work

1. Coordinate root-owned clean-checkout/global/CI/release certification and Git. A03 completed independent packet and documentation review with no unresolved blocker in that scope. Completed tests, runtime demonstrations and measurements do not imply that these separate release checks have run.

P10-A1/A2 have current scoped correctness passes. P10-A3 has current PostgreSQL recovery/fencing/source-compatibility evidence and an actual historical process-restart pass. P10-A4 has actual breakdown/urgent lifecycle and final-source browser passes. P10-A5 now has the full prescribed corpus/JMH and source-linked operational evidence; clean-checkout release reproduction remains open. No large-instance optimum or production/customer deployment is claimed.

A03 independently closed both numeric and documentation review, recorded separately in [current review acknowledgment](../evidence/performance-review-current.json); the sealed version 1 packet remains unchanged. All owned paths are now write-paused for the coordinator’s checkpoint. No new application, build or measurement process was started during analysis.

The earlier 02:53 and pre-measurement scheduling checkpoints are historical and superseded by the observed runtime/measurement evidence above. Any future work still requires the coordinator's current ownership and resource scheduling instructions.
