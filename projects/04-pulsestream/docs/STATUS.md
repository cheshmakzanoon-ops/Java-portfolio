# A04 PulseStream status

Project milestone: M1/M2 original engine implemented and early unit gate observed; M3/M4 durability/security verification in progress; M5 evidence workload/review pending. Owned paths: `projects/04-pulsestream/**`, `.work/A04/**`. No Git writes or shared file changes by worker.

Acceptance status:

| ID | Status | Evidence/current next check |
|---|---|---|
| P04-A1 | IN_PROGRESS | First `verify` succeeded with 120 seeded boundary/reordering histories and focused engine tests; added independent source-progress/idle/restart oracle histories awaiting latest full verify |
| P04-A2 | IN_PROGRESS | Focused idle/backlog/queue/resume/all-idle/restart tests passed in initial runs; generated independent control schedules added |
| P04-A3 | IN_PROGRESS | Fenced PostgreSQL ownership/atomic normalized state implemented; first IT SQL fencing/restart test passed but real crash fixture failed before processing because AdminClient API timeout was below request timeout; fixed and rerun queued |
| P04-A4 | IN_PROGRESS | Initial real broker retention/backpressure and SQL quotas/conflict tests passed; real outbox crash/retry awaits repaired run; actual paused-database outage poison/recovery test added |
| P04-A5 | IN_PROGRESS | Real asserted ReplayWorker/demo scripts, JMH and prescribed million-measurement driver implemented; clean full demo/full workload/three-run baseline not yet executed |

Commands and observed results:

- `source scripts/cloud-env.sh; ./mvnw -f projects/04-pulsestream/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply verify` → first completed build SUCCESS, `.work/A04/build.log`, 5 engine tests (including 120 jqwik histories), active engine line>=85% / branch>=75% checks passed. This predates later additions and is not certification of the current tree.
- Initial `-Pintegration verify` → FAIL, `.work/A04/integration.log`; discovered 4 real integration tests, 3 passed and crash fixture failed during Kafka topic-identity AdminClient construction (`default.api.timeout.ms` smaller than request timeout). The failed fixture never proved crash recovery. Formatting also failed because sources were still being added; no pass claimed.
- Second integration invocation `.work/A04/integration2.log` → ABORTED by owner before Docker launch to release coordinated stack slot and incorporate strict required JWT claims. No result claimed.
- Latest unit verification `.work/A04/unit-final.log` → RUNNING (to be updated on completion). Docker rerun is queued with coordinator. No simultaneous project builds remain active.

Current trust/guarantee boundary: one active processor per finite run; one consumer poll owner and one engine owner; monotonic event-time watermark; atomic PostgreSQL-visible state/results/dedup/next offsets/outbox; at-least-once Kafka outbox. No distributed/global exactly-once claim. Final SQL windows are immutable and leave engine memory. All retained dedup hashes are scoped to the finite run and never silently expire.

Review corrections in progress: independent oracle must compute source watermark/idle/restart from recorded controls rather than reuse production watermark; strict duplicate JSON-member rejection; durable session must poison after ambiguous SQL failure; protected JWTs must require exp/subject/audience/typed tenant and reject future nbf. Negative fixture tests and actual database pause are included in upcoming validation.

Remaining gates: latest format/verify, targeted mutation run >=70% (investigate survivors), fresh Kafka4.2.1/PostgreSQL18.3 integration suite with all crash points and stricter issuer negatives, real Compose up/health/demo/down/reset, measured JMH and full eight-partition/10,000-key/million-measurement workload in isolated performance window (three runs/variability), source fingerprints and stable evidence, independent frozen-tree review. No mandatory gate has been excluded or treated as green while unavailable.

Resume: finish current unit command; inspect `.work/A04/unit-final.log` and module surefire/JaCoCo reports. Request coordinated Docker slot before `-Pintegration verify`. Run scoped formatter before each verification after any edit. Then use `scripts/up.sh`, `scripts/demo.sh`, `scripts/down.sh` with project-only Compose. Coordinate benchmarks alone on reference host. Coordinator stages/commits only after this owner acknowledges write pause.

## Write-paused handoff — 2026-10-18 00:22 UTC

Owner explicitly acknowledges WRITE PAUSE over all A04 owned paths after this status update. No Maven, Docker or benchmark job remains active for A04. Coordinator may now validate, stage only owned paths, or resume owner for defects. Acceptance remains IN_PROGRESS.

Latest implementation fingerprint: `bedb5977b7dcc0999f64e6a81e529277733454a4282b3c033f39bb385aa96704` (41 files). Reproduction: SHA256 over sorted project-relative POSIX path + NUL + bytes for every file excluding path components `target`, `docs`, `evidence`, `.jqwik-database`. `evidence/handoff.json` records the same envelope. Formatting changes will legitimately change this fingerprint; recompute after final validation. Root dependency/build changes also require recording root POM fingerprint with final evidence.

Latest unit command finished FAIL in `.work/A04/unit-final.log`: 120 generated core histories passed; the added invalid-progress negative fixture failed because test helper rewrote nonzero progress value to zero. The helper is now corrected, but no rerun has certified it. This run occurred during subsequent source improvements and is not evidence for the current complete tree. Initial `.work/A04/build.log` is an earlier full SUCCESS with core coverage gate, while `.work/A04/integration.log` is the initial 3/4-pass integration FAIL described above. `.work/A04/integration2.log` is an aborted run. No benchmark metrics, crash recovery pass or current-tree green claim is made.

Known review/code fixes now present but awaiting fresh verification:

- Source watermark oracle independently processes progress, retries, idle eligibility, all-idle and restart; generated histories no longer feed it production watermark. Progress dedup is included.
- Strict duplicate JSON member rejection; required expiry/nonblank subject/audience/typed tenant, with missing-claim and future/malformed nbf negative fixtures.
- DurableSession refuses further apply after an ambiguous SQL/runtime failure; an actual paused-PostgreSQL integration test checks old committed object/checkpoint and restart recovery.
- Invalid identifiers use bounded diagnostic placeholders; unattributed records use invalid API tenant sentinel `!unattributed` and cannot be queried as an ordinary tenant. A durable diagnostic limit (six times run-event quota) fails the transaction before checkpoint movement; its rollback test is added.
- In-flight batches join queued records/backlog/paused state in the idle eligibility proof. Metrics include queue utilization and lag measured from SQL-committed offsets. Final results remain SQL-owned history.
- Consumer startup now awaits assignment/retained-position validation. Kafka Admin timeout configuration corrected; broker image changed to verified available `apache/kafka:4.2.1` (aligns client 4.2.1), PostgreSQL `18.3-alpine`. These upgraded images have not yet been used by a completed A04 test run; capture actual RepoDigests on successful run.
- Public demo adds real HTTP window/status assertions, scoped reset includes run usage, and fixture lease is five real seconds (demo waits six seconds after crashes).

Exact queued commands (from repository root; do not overlap project Maven invocations):

```bash
source scripts/cloud-env.sh
./mvnw -f projects/04-pulsestream/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply verify
./mvnw -f projects/04-pulsestream/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp -Pintegration verify
./mvnw -f projects/04-pulsestream/engine/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A04/m2 -B -ntp -Pmutation verify
projects/04-pulsestream/scripts/up.sh
projects/04-pulsestream/scripts/demo.sh
projects/04-pulsestream/scripts/down.sh
# With only this project's dependencies available; reset only last recorded seeded run:
projects/04-pulsestream/scripts/reset-demo.sh
# Isolated performance window, three independent runs; smoke is separate and not acceptance:
projects/04-pulsestream/scripts/benchmark.sh smoke
projects/04-pulsestream/scripts/benchmark-engine.sh
projects/04-pulsestream/scripts/benchmark.sh full
projects/04-pulsestream/scripts/benchmark.sh full
projects/04-pulsestream/scripts/benchmark.sh full
```

Run reset before down or restart only dependencies first; down intentionally stops dependencies and preserves volumes. Capture each command exit and actual source/environment fingerprint; copy only small real result/transcript summaries into evidence after success. JMH script requests three forks. The durable driver uses prescribed full seed/partitions/keys/count and records checkpoint percentiles, throughput, queue, sampled heap, database size/growth and restart time; it is closed loop, excludes Kafka preparation/outbox publication from durable drain, and discloses those boundaries. A failure is a failure, never a reason to reduce full workload or disable a test.

Mandatory gaps at pause: latest sources have not completed format/compile/unit/coverage; actual before-commit/after-commit/outbox-crash replay has not yet passed; stricter JWT/body/quota/database-outage cases unrun; mutation threshold unmeasured; Compose lifecycle/demo unrun; prescribed million-event workload and three-run variability unmeasured; independent review closure pending. Full-state copy/snapshot/pane replacement is a likely performance limitation and may need implementation optimization if measured workload cannot finish. A stack upgrade/snapshot migration protocol is not implemented (same-version restore only), nor elastic distributed execution, neither is claimed. No additional planned CRUD/UI scope should replace these gates.

Peer review A04→A05 (read only): production Java fingerprint `3a83ea859014c961d6f5982fc5d8a27e641f8705041341fb2bd9f52702b06dde`; reported missing-audience null handling/required protected-service subject and non-atomic rollback. A05 owner acknowledged and reports fixes/tests for all; fresh integration evidence and frozen-tree review remain pending. A03→A04 findings on independent oracle/duplicate JSON/poisoned session were accepted and code-corrected above; await tested review of final tree.

## Coordinator current-tree validation

2026-10-18 00:30 UTC: scoped formatter+verify exited0 on source SHA256`0e3279d077fef65b9e13644d9bfe0814f2eebca30373dcad2a2528ba32378415`. Console reports9 test methods across engine/codec/retention/schedule suites,0failures/errors/skips; generated tries are separate. Core line190/192(98.96%), branch146/162(90.12%). See evidence/coordinator-unit-verify.json for exact paths, source/rootPOM identity and mixed-engine XML count caveat. P04-A1 has bounded executable independent-oracle evidence; remaining gates stay IN_PROGRESS. Current-tree Kafka/PostgreSQL integration is running from frozen sources in .work/coordinator/pulsestream-integration.log; no result claimed yet.
