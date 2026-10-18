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

A09 closed the original cancellation-start/null-cloner, URL-safe ID and static-shell findings, then inspected strict JSON, bounded cancellation polling and same-job baseline comparison without another blocking finding. The new provenance guard was inspected and its observed 14-method PostgreSQL evidence was sent for scoped closure. REVIEW records the exact scope; full release review remains pending measurements.

Historical passes remain preserved: 45 methods/12 PostgreSQL at 737a05c and strict-input 37 methods/82 mutants at d2636b73 used JUnit 6.0.3; the 53-method/13-PostgreSQL pass at 2aa7d7da used 6.1.3. A shared-host startup-race regression failed when its five-second test budget expired before setup; .work/A10/release-driver-preparation.log is retained. Explicit early/continued cancellation latches and a legal 30-second test budget repaired the scenario, without changing production solver code. Later full runs passed.

## Mandatory remaining work

1. Obtain an explicitly quiet host window and execute all ten 100/250/500-job instances three times at the actual 30-second budget, both full JMH boundaries, queue/score/time/resource/GC records and same-job baseline comparison. Preserve every result and report the preregistered improvement outcome, including a missed goal.
2. Complete final A09 release review and update measured PERFORMANCE/CASE_STUDY/INTERVIEW evidence. Coordinate root-owned clean-checkout certification and Git.

P10-A1/A2 have current scoped correctness passes. P10-A3 has current PostgreSQL recovery/fencing/source-compatibility evidence and an actual historical same-source process-restart pass. P10-A4 has actual breakdown/urgent lifecycle passes and the completed final-source compact-status browser preflight. P10-A5 remains OPEN for full quiet measurements and clean-checkout reproduction. No large-instance optimum, production deployment, accepted quiet speedup or final release pass is claimed.

Historical scheduling checkpoint: all owned paths were write-paused at 02:53 UTC for the correctness milestone, with A09's final acknowledgment pending. This checkpoint preceded the completed 03:51 final-source lifecycle and is superseded by the current evidence and remaining gates above. Full quiet corpus/JMH, final review and clean-checkout certification remain OPEN. No 10% tuning-goal result has been measured or claimed.
