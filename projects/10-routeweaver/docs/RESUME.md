# Coordinator resume protocol

This is an execution checkpoint, not a completed release. A10 owns projects/10-routeweaver/** and .work/A10/**; only root stages/commits. Use the existing isolated checkout directly. Maven uses the FIFO wrapper, maximum three builders; PostgreSQL and quiet measurement require explicit coordinator scheduling. Never start a third application stack.

## Current evidence and immediate next step

Current source d5964723baa5e51619d4d85316f02ae3ccc370726ad7eb3b41617a200ced88d0 passed the combined integration/mutation/benchmarks reactor at 2026-10-18T02:50:25Z: 54 methods (40 ordinary + 14 real PostgreSQL), actual JUnit 6.1.3, 40 generated checks, complete 95,901-leaf oracle and 82/82 selected Validator mutants. evidence/integration.json and mutation.json carry per-file/report hashes; raw log is .work/A10/source-compatibility-integration.log. Testcontainers stopped and the PostgreSQL slot was released. No persistent stack is running.

The source-compatibility regression is now actually verified: queued/expired jobs from a different binary fail before planning, no newly mislabeled candidates are emitted, an existing incumbent/source remains historical, a stale fence cannot finish/publish, same-source recovery completes with a newer fence and a new-source replacement completes. ADR 003 explains conservative upgrade refusal even for UI-only changes. A09 has the actual result for scoped review closure.

Earlier source 2aa7d7da passed 53 methods/13 PostgreSQL and the real pooled-JDBC diagnosis, first/repeat 60-job demos (118/114 checks), Chromium rendering/solve/publication, actual SIGKILL with fence 1 → 3, reset and down. evidence/live.json retains those exact source/results; do not relabel them to current source. The current compact UI status and compatibility guard have the fresh 54-method certification; the new browser helper still needs actual final-source runtime execution.

No further Maven is needed before runtime measurement unless sources change. The final source stamp and fat artifacts have been checked. .work/A10/preflight-no-maven.sh rechecks them, the driver fixture and both JMH entries without resolving dependencies or running a stack. evidence/artifacts-current.json records exact service/driver SHA and the observed preflight success. The coordinator may now commit a correctness milestone and reactivate this owner for the remaining reference interval. A scheduling pause never marks performance/release complete.

## Final no-Maven artifact/runtime preparation

The runtime artifacts are self-contained: service -exec.jar and verification -driver.jar. No dependency resolution or Maven should run in a reserved reference interval. Before reserving it, verify the source/build stamp, artifact SHA, source HTML equals the packaged BOOT-INF/classes/static/index.html, strict generated fixture is 60 jobs/six vehicles, and JMH lists both fullReferenceValidation and immutableProblemImportValidation. Existing artifact checks for the earlier UI snapshot are in evidence/ui-status.json; refresh them on final source.

With an explicit final-runtime reservation, execute up, explicit reset while idle, demo, actual Chromium compact-status preflight and down. The coordinator may combine this finite preflight with preparation for the quiet interval; do not start a persistent stack without its grant. A repeated restart-check is available if new backend changes require it; the existing actual SIGKILL evidence is preserved under its true source. A final-source preflight is still required after the source guard/UI repair. Use the already installed Python Playwright/Chromium, never install a browser toolchain. Browser checks: load published input 2, six route polylines/timeline rows, visible stranded cargo and URGENT, zero hard violations, solve with same-input baseline, CAS publication, compact status rather than a full plan dump and no page errors. Save actual screenshot/DOM/result with source identity. Run python3 projects/10-routeweaver/scripts/browser-check.py for this actual live check. It writes a fresh screenshot/DOM/result directory, never mocks HTTP, checks the planRevision summary key and requires publication status shorter than 500 characters. This newly packaged helper has syntax validation but still awaits its first actual final-source runtime execution.

restart-check creates its separate deterministic problem, waits for a validated RUNNING incumbent, verifies its own PID command, sends actual SIGKILL, restarts the same source/database and requires COMPLETED with a newer fence, same-input nondegradation, exact partition and no automatic publication. Repeating it requires explicit reset while idle. A source upgrade intentionally fails old queued/recovered jobs without new candidates; do not bypass this by lying about source identity. See DECISIONS/003.

Always preserve .runtime result/GC/service logs before reset or restart overwrites them. Down preserves the owned volume. Release the stack after the finite round; do not keep a database running while another owner's quiet reservation starts.

## Full reference measurement remains mandatory

Obtain an explicitly quiet host window with unrelated builds, stress and services stopped. Estimate approximately 20–22 minutes for JMH plus all 30 real 30-second system solves and startup/IO; this is scheduling guidance, not a measured duration. Prepare binaries/image/datasets outside measured intervals. Start a fresh verified service for the accepted corpus, note existing OS/database cache state, and reset only the idle demo tenant so preregistered IDs do not conflict.

```sh
projects/10-routeweaver/scripts/up.sh
projects/10-routeweaver/scripts/reset-demo.sh
projects/10-routeweaver/scripts/benchmark.sh
projects/10-routeweaver/scripts/down.sh
```

benchmark.sh creates a fresh output directory under .runtime/benchmark/run-<UTC>, refuses result overwrite, records real environment, runs both JMH boundaries with three forks/three one-second warmups/three one-second measurements and GC profiling, then all ten deterministic inputs three times. System sizes are 100/250/500 jobs with 5/10/25 vehicles, easy/tight/overconstrained plus the preregistered directed cost trap. Solver budget is 30,000 ms including baseline, with a 100,000-step safety cap; record actual duration/stop, never pad a run or claim the configured limit was all consumed if it was not.

The system driver compares against the actual same-job persisted independently checked baseline, retains full lexicographic score/unassigned and only computes scalar operating-cost gain for identical assigned demand. Preserve corpus-raw.json, both JMH results, server CPU/RSS/thread samples, client/server GC, raw score/time traces, queue/baseline/worker/HTTP durations, source/config/seed/input hashes and environment. Shared-host diagnosis is separate: three pooled-JDBC pairs showed equal scores with 2,743 raw reads versus 4/11/2 sampled; no portable timing speedup was claimed.

Report all three results/variability and the actual deliberately improvable fixture outcome against the proposed 10% goal. Feasibility/incumbent preservation remain mandatory even if gains are small. Do not fabricate p95/p99 from three solve samples or substitute a smoke for the complete corpus.

Finish A09 final review, exact current evidence and measured documentation; coordinate root-owned clean-checkout certification/Git. Current-source PostgreSQL/source-upgrade gates are PASS. Final compact-UI runtime, full quiet corpus/JMH and clean-checkout gates remain OPEN until executed. A scheduling pause is not completion.

## Scheduling handoff at 02:53 UTC

All owned paths projects/10-routeweaver/** and .work/A10/** are WRITE-PAUSED for the coordinator's coherent correctness milestone. No source, evidence, scripts, generated artifacts or runtime output will be changed until reactivation. Current fingerprint/build stamp remain d5964723; no Maven, service, browser or owned containers are running. The final reviewer acknowledgment is pending because A09 was unavailable; its prior guard review and the observed current 54-method results are separately recorded. Retain all open runtime/measurement/clean-checkout gates above.
