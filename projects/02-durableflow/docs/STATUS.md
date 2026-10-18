# DurableFlow status

The current semantics 3 source passed its automated correctness gates. **P02-A5 remains OPEN:** standalone launcher/demo, smoke and three full measured workloads have not run. No release, workload throughput or regression-budget claim is made. No worker Git writes were performed. Java/POM/scripts remain frozen pending live script scheduling.

Source: `sha256:a8a4692a96226a6d45f3f42f1a8780b28e6d5ade834389d8c83ea8a2b0ec48f5`. Root POM: `3743d1ba382977b3f8d8609e1191190f220cdf272a5338d538869f96a7b7230d`. [Source manifest](../evidence/source-manifest.json) records individual hashes and exclusions. Actual Jupiter/platform dependencies are 6.1.3, confirmed in Surefire classpaths. Historical 6.0.3 runs are not relabeled.

## Verified evidence

[Verification envelope](../evidence/verification.json), [environment](../evidence/verification-environment.json), [full build log](../evidence/verification-build.log), [mutation log](../evidence/mutation-build.log), and [all selected mutants](../evidence/mutations.xml) preserve actual outcomes. Historical 6eacba1 evidence remains in `.work/A02/historical-6eacba1-evidence/` and earlier certification logs.

- Project-only formatter and ordinary verify: PASS, 2026-10-18T02:02:42Z, 46.5 seconds.
- Fresh full `-Pintegration verify`: PASS, 2026-10-18T02:06:19Z, 87 seconds; 67 ordinary methods plus 13 actual PostgreSQL 18.3 methods, zero failures/errors/skips. Raw `.work/A02/semantics3-integration.log`; copied reports `.work/A02/semantics3-reports/`.
- Core coverage: model 94.20% line/88.70% branch; engine 96.34% line/84.76% branch. Both enforce 85% line/75% branch with no broad generated-code exclusion.
- Selected mutation: PASS, 2026-10-18T02:03:16Z, 30.7 seconds; 41 generated/41 KILLED, zero uncovered/surviving/timed-out mutants. Mutated Data* lines 87/88 covered. The nonzero 70% threshold is enforced and all reports are retained in `.work/A02/semantics3-mutation-reports/`. This covers original typed scalar/schema/vector/cancellation-evidence invariants, not an engine-wide mutation claim.
- Expanded independent A01 review CLOSED for this exact source and evidence. The reviewer inspected versioned cancellation evidence, terminal rejection, frozen version 2 compatibility and actual leased late-completion/compensation proof, plus prior typed/CAS/dispatch/JWT/provider paths. No additional concrete serious issue was found in the inspected scope. Demo/full workload/profile/release remain separate open gates.

## Cancellation compatibility correction

Expanded review demonstrated STARTED→FAILED→CANCELLED was accepted by pure semantics 2 replay although live store cancellation rejects terminal FAILED. Another parallel forward lease can legitimately keep cancellation pending after a peer fails, so a blanket failure-set prohibition would reject valid histories. [ADR 003](DECISIONS/003-cancellation-evidence.md) specifies the explicit version boundary.

New definitions pin semantics 3. Cancellation records the current leased-forward-task count as a typed INTEGER input under the instance lock. Replay rejects malformed/impossible evidence and terminal failure with no outstanding lease, uncertain outcome or pending compensation. Cooperative cancellation still cannot prove that expired workers stopped. Frozen interpreters retain semantics 1 and 2, including the prior empty cancellation payload for version 2. Existing immutable definitions retain their pins; a higher definition version or new definition activates 3. The frozen version 2 interpreter is byte-identical to the prior typed interpreter after class/doc-name normalization; the old and normalized hashes both equal `6f741ef514566f4617941b8b4d2df882d3b20d865d207639d92ed2ff6a055888`.

Pure regressions cover terminal FAILED before and after successful compensation, invalid evidence and legitimate late completion. The real-PG compatibility case seeds persisted version 2, records an independent provider effect while its claim remains leased, fails the parallel peer, cancels, deploys 3, and verifies unchanged old hash/history/pin. Late completion creates durable compensation and ends CANCELLED. The version 3 equivalent records INTEGER 1; terminal FAILED cancellation rejects without changing version/history. Four independent forward/compensation effects are asserted.

Typed semantics use actual TEXT/INTEGER/BOOLEAN scalars, exact start schemas, predecessor-derived arguments and recorded typed results. Provider/sum/not results are scalar; pure echo preserves actual argument count. Wrong-type/wrong-length completions leave version/history unchanged. Timers preserve arguments, signals expose TIMED_OUT, skipped branches are explicit, and joins preserve declared predecessor order. Real-PG tests verify actual predecessor parameters in the provider CALL log and original version 1 compatibility.

Other passing gates cover explicit version CAS and killed-before-commit atomicity; duplicate delivery; killed workers after externally logged effects; stale dispatch/heartbeat/completion; leases; non-idempotent uncertainty without automatic repeat; operator audit; claim/cancel races; cancellation/late completion/reverse compensation/failure/retry; signal/timer races and 5,000 dormant timers. Reference-model and 500-case fixed-seed jqwik suites run for each interpreter. A 40MiB provider-log recovery test uses a separate 32MiB JVM and verifies one retained effect. Signed raw numeric/array/null/blank subjects and wrong-typed tenants, missing claims, forgery, issuer/audience/time validation, role/tenant scope, chunked 8KiB limits, RFC9457 and typed HTTP flow pass. OpenAPI route/record/ref drift is checked.

## Remaining mandatory work

P02-A1 through A4 have executable module-level evidence and expanded scoped peer review. **P02-A5 is OPEN.** Application PostgreSQL container stopped after certification. The coordinator requested a hold on new standalone Compose/runtime starts for its explicitly quiet measurement reservation. No new build or runtime process is pending from A02. This scheduling hold is not an acceptance result.

1. After the coordinator releases the hold and assigns an application slot, run actual `up`, fault demo with both genuine 31-second lease waits, 100-execution smoke, idempotent repeated up and down/reset lifecycle. Preserve raw output and independent provider logs. Launcher and measurement code may reveal further defects when exercised.
2. Obtain an explicitly quiet reference host, reset disposable data between three full runs, and execute 10,000 executions/eight activities with 16,000 first-attempt rejections, 10,000 accepted unique signals and 5,000 business timers using two schedulers/eight workers. Assert 80,000 completed activities and independently logged unique effects.
3. Preserve per-run JSON/environment/RSS/CPU/GC/JFR artifacts. Run the three-run summarizer and establish a same-environment budget only from actual stable observations. Proposed tolerance is 25% above the slowest reference duration/p95 completion/p95 scheduling when CV<=10%; no numeric threshold exists yet.
4. Regenerate identity and rerun affected gates after any concrete fix; request independent review for changed invariants and provide final evidence/limitations to the coordinator for Git/publication/CI.

## Commands

Run from repository root after `source scripts/cloud-env.sh`. Normal wrapper uses the shared FIFO three-build limit; maximum two application stacks requires coordinator scheduling. Do not edit Java/POM during a certification invocation.

```bash
./mvnw -f projects/02-durableflow/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A02/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration verify
./mvnw -f projects/02-durableflow/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A02/m2 -B -ntp -pl workflow-model -am -Pmutation verify
./scripts/portfolio.sh up durableflow
./scripts/portfolio.sh demo durableflow
projects/02-durableflow/scripts/benchmark.sh --smoke
./scripts/portfolio.sh down durableflow
./scripts/portfolio.sh reset-demo durableflow
./scripts/portfolio.sh up durableflow
projects/02-durableflow/scripts/benchmark.sh
projects/02-durableflow/scripts/summarize-performance.py RUN1 RUN2 RUN3
./scripts/portfolio.sh down durableflow
```

Full-run raw evidence is captured under `target/performance/<UTC>-<count>-<pid>/`, including actual exit status. Smoke cannot satisfy the full workload. Environment-script execution and shell/Python syntax probes passed; they are metadata/syntax checks, not workload measurements.

## Development failures retained honestly

Historical `.work/A02/integration.log` ran7 actual-PG methods:1 failure/2 errors under one-second active leases and severe contention. Tests now use60-second live claims plus explicit database expiry injection; the live demo keeps real30-second expiry. An early typed build failed unchanged coverage limits before focused Data tests were added. `typed-build-3` found an invalid one-input parallel-all fixture; it now uses real left/right joins and latest completion anchor. First typed PG run passed11/12 but found bearer-filter problem-response drift; explicit bearer/Basic handlers fixed it without weakening body assertions. Post-maintenance hardening caught old fixtures canceling already-completed graphs; pending signals now make those compensation scenarios legal. Queue-canceled invocations were scheduling changes, not accepted test results. Raw logs/reports remain in `.work/A02/`.

A02→A03 scoped reviews closed prior snapshot-transfer, unknown-mutation-identity and checker-state findings with tested owner fingerprints. The later detached-process lifecycle follow-up reviewed bounded Linux zombie detection and independent controller stop/restart against41 passing methods on6.1.3; actual catalog rounds/full workloads remain separately reported by the coordinator. No other project files were written.
