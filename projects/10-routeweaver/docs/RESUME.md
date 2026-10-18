# Coordinator resume protocol

This is an execution checkpoint, not a completed release. A10 owns projects/10-routeweaver/** and .work/A10/**; only root stages/commits. Use the existing isolated checkout directly. Maven uses the FIFO wrapper, maximum three builders; PostgreSQL and quiet measurement require explicit coordinator scheduling. Never start a third application stack.

## Current evidence and immediate next step

Current source d5964723baa5e51619d4d85316f02ae3ccc370726ad7eb3b41617a200ced88d0 passed the combined integration/mutation/benchmarks reactor at 2026-10-18T02:50:25Z: 54 methods (40 ordinary + 14 real PostgreSQL), actual JUnit 6.1.3, 40 generated checks, complete 95,901-leaf oracle and 82/82 selected Validator mutants. evidence/integration.json and mutation.json carry per-file/report hashes; raw log is .work/A10/source-compatibility-integration.log. Testcontainers stopped and the PostgreSQL slot was released. No persistent stack is running.

The source-compatibility regression is now actually verified: queued/expired jobs from a different binary fail before planning, no newly mislabeled candidates are emitted, an existing incumbent/source remains historical, a stale fence cannot finish/publish, same-source recovery completes with a newer fence and a new-source replacement completes. ADR 003 explains conservative upgrade refusal even for UI-only changes. A09 has the actual result for scoped review closure.

Earlier source 2aa7d7da passed 53 methods/13 PostgreSQL and the real pooled-JDBC diagnosis, first/repeat 60-job demos (118/114 checks), Chromium rendering/solve/publication, actual SIGKILL with fence 1 → 3, reset and down. evidence/live.json retains those exact source/results; do not relabel them to current source. The current compact UI status and compatibility guard have the fresh 54-method certification. At 2026-10-18T03:51Z the coordinator also completed the exact current-source no-Maven preflight/up/reset/demo/Chromium/down lifecycle: all six commands returned 0, the demo made 101 actual assertions and the browser checked ten behaviors including compact 107-character publication status. See evidence/lifecycle-current/operations.json. This closes the final-source browser/runtime preflight; earlier pending statements are superseded.

No further Maven is needed before runtime measurement unless sources change. The final source stamp and fat artifacts have been checked. .work/A10/preflight-no-maven.sh rechecks them, the driver fixture and both JMH entries without resolving dependencies or running a stack. evidence/artifacts-current.json records exact service/driver SHA and the observed preflight success. The coordinator committed the correctness and current-source lifecycle milestones; the full quiet reference interval subsequently passed as recorded below. A scheduling pause never marks performance/release complete.

## Verified artifacts and completed runtime preflight

The runtime artifacts are self-contained: service -exec.jar and verification -driver.jar. No dependency resolution or Maven should run in a reserved reference interval. Current-source checks already verified the source/build stamp, artifact SHA, source HTML identity with packaged BOOT-INF/classes/static/index.html, the 60-job/six-vehicle fixture and discovery of both fullReferenceValidation and immutableProblemImportValidation. evidence/artifacts-current.json and the completed lifecycle evidence identify this exact source. Recheck artifacts without rebuilding before the reserved measurement interval; evidence/ui-status.json is historical.

The coordinator's completed 03:51 runtime preflight used the installed Python Playwright/Chromium and python3 projects/10-routeweaver/scripts/browser-check.py against real HTTP, without mocks. It loaded published input 2, rendered six polylines/timeline rows, exposed stranded cargo and URGENT with zero hard violations, solved with the same-input baseline, performed CAS publication and asserted compact status below 500 characters with no page errors. The actual result was 107 characters. Screenshot/DOM metadata and results are preserved in evidence/lifecycle-current. Do not repeat this finite functional preflight as a substitute for the full quiet corpus/JMH; a later source change would require appropriately renewed verification.

restart-check creates its separate deterministic problem, waits for a validated RUNNING incumbent, verifies its own PID command, sends actual SIGKILL, restarts the same source/database and requires COMPLETED with a newer fence, same-input nondegradation, exact partition and no automatic publication. Repeating it requires explicit reset while idle. A source upgrade intentionally fails old queued/recovered jobs without new candidates; do not bypass this by lying about source identity. See DECISIONS/003.

Always preserve .runtime result/GC/service logs before reset or restart overwrites them. Down preserves the owned volume. Release the stack after the finite round; do not keep a database running while another owner's quiet reservation starts.

## Completed reference measurement and remaining release work

The full quiet run is complete: reservation 17 passed from 2026-10-18T09:19:15.646 to 09:35:26.133 UTC on source d5964723 and Git revision 49d23dae48eeffb8939730a953cd803314b8dc5a. The sealed evidence/performance-v1/packet.json records all 30 corpus outcomes, six JMH rows with three forks, 811 resource samples, raw hashes and the root-observed stopped check. No new runtime measurement is needed to analyze this unchanged source. The following commands remain the reproduction protocol for a separately granted future interval, not unfinished current work.

```sh
projects/10-routeweaver/scripts/up.sh
projects/10-routeweaver/scripts/reset-demo.sh
projects/10-routeweaver/scripts/benchmark.sh
projects/10-routeweaver/scripts/down.sh
```

benchmark.sh creates a fresh output directory under .runtime/benchmark/run-<UTC>, refuses result overwrite, records real environment, runs both JMH boundaries with three forks/three one-second warmups/three one-second measurements and GC profiling, then all ten deterministic inputs three times. System sizes are 100/250/500 jobs with 5/10/25 vehicles, easy/tight/overconstrained plus the preregistered directed cost trap. Solver budget is 30,000 ms including baseline, with a 100,000-step safety cap; record actual duration/stop, never pad a run or claim the configured limit was all consumed if it was not.

The system driver compares against the actual same-job persisted independently checked baseline, retains full lexicographic score/unassigned and only computes scalar operating-cost gain for identical assigned demand. Preserve corpus-raw.json, both JMH results, server CPU/RSS/thread samples, client/server GC, raw score/time traces, queue/baseline/worker/HTTP durations, source/config/seed/input hashes and environment. Shared-host diagnosis is separate: three pooled-JDBC pairs showed equal scores with 2,743 raw reads versus 4/11/2 sampled; no portable timing speedup was claimed.

All three outcomes and variability are now published. The preregistered fixture met the proposed goal in all three runs: 101,101 baseline → 96,106 after original moves → 90,120 after Timefold, a 10.8614158% combined improvement with identical assigned demand. Three 500-job overconstrained baselines exhausted the cooperative budget and remained partial; the actual maximum worker duration was 31.552836 seconds. Six early outcomes reached the actual 100,000-step cap; no duration was padded. See PERFORMANCE for all rows, resources, GC and the sparse secondary gc.time observation. No p95/p99 or universal gain is inferred.

Current-source PostgreSQL/source-upgrade, final-source compact-UI runtime and full quiet corpus/JMH gates are PASS within their recorded scope. A03 packet/documentation review is SCOPED_PASS, with its acknowledgment in evidence/performance-review-current.json. Root-owned clean-checkout/global/CI/release certification remains open. The current 54-method and selected 82/82 Validator mutation evidence is preserved without expanding its scope. A scheduling pause is not release completion.

## Historical scheduling handoff at 02:53 UTC

All owned paths were write-paused for the correctness milestone at that checkpoint. It preceded the completed 03:51 final-source lifecycle; its earlier browser/runtime-pending statements are superseded by the current evidence above. The historical fingerprint/build stamp remains d5964723, and no result is relabeled to another source. A09's final acknowledgment was pending then; final release review remains a current gate. Continue only under the coordinator's current reservation and ownership instructions. The quiet corpus/JMH subsequently completed; clean-checkout release work remains unfinished; a scheduling pause is not completion.


## Completed analysis handoff

The full prescribed reference measurements and A03 independent numeric/documentation review are complete for unchanged source d5964723. Packet version 1 remains sealed at aef369a9ef0f2b97a2a8903af91b40ae1fe778728c90d6db86a2c6589445806b; its historical REVIEW_PENDING state is superseded only by the separate current acknowledgment, not rewritten. All projects/10-routeweaver/** and .work/A10/** paths are WRITE-PAUSED for the coordinator’s checkpoint. No source/Java/Maven/Docker/new measurement was run during analysis. Resume only for explicitly assigned remaining clean-checkout/global/CI release work; a project release pass is not inferred.
