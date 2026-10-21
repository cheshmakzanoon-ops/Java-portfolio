# Verification status

DurableFlow has executable replay, persistence, security, process-crash, and standalone recovery checks. The prescribed three-run performance baseline is incomplete, so no stable capacity budget or production-readiness claim is made.

## Current verification

The full PostgreSQL **18.6** integration build passed on **2026-10-20 at 09:18:01 UTC**: **96 ordinary Java test methods and 22 PostgreSQL integration methods**, with zero failures, errors, or skips. Ordinary verification also executes **ten real-JVM/fake-Compose lifecycle cases** and nine preparation/refusal cases. The lifecycle cases require no Docker. The final ordinary preparation build passed at 09:34:56 UTC and produced a source/artifact-bound runtime receipt.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/02-durableflow/pom.xml \
  -Dmaven.repo.local=.work/A02/m2 -B -ntp spotless:apply -Pintegration verify
```

The expanded checks cover isolated scheduler transactions, full-history failure, locked-row skipping, restart-persisted deferral, and a deterministic rollback → successful cancellation → rejected stale deferral interleaving. Worker and scheduler tests inject transient storage outages, recover without speculative dispatch, interrupt claim backoff, and expose permanent SQL failures. A provider receipt barrier checks interrupted shutdown while a committed external effect still awaits acknowledgement. The authenticated maximum-size claim test verifies authoritative claim matching, dispatch, actual provider delivery/deduplication, and persisted completion; public requests retain their 8 KiB bound.

Replay tests enforce reverse compensation identity and bounded fixed-point convergence. Transport tests bound fixed/chunked responses, reject redirect acknowledgements, preserve Unicode receipts, distinguish explicit pre-effect refusal from uncertainty, and exercise the derived provider request bound. The PostgreSQL process-crash and non-idempotent uncertainty cases remain part of the integration gate.

The final pure-component mutation build passed at **08:41:19 UTC** on the same production Java sources and patched dependency baseline: **41 / 41** generated `Data*` mutants killed, **158 / 162** current-interpreter mutants killed, and **51 / 51** selected persistence-policy/transaction/replay/classification mutants killed. All generated mutants are included in those denominators. The four interpreter survivors concern duplicate defensive validation, a bounded-loop counter direction, and a redundant guard; no generated mutants lacked coverage or timed out. The unchanged threshold is 70%. This scope does **not** mutation-certify JDBC SQL, scheduler concurrency, worker shutdown, frozen interpreters, or shell scripts; their evidence comes from the deterministic ordinary/integration/lifecycle checks.

The initial final-image build failed in a newly added lifecycle regression before PostgreSQL startup: procfs could report a live stat and then an empty command line while that process exited. A second start/state snapshot now distinguishes that exit without authorizing an empty or mismatched command line; the deterministic regression and the full rerun passed. Earlier source-specific failures and successful runs remain distinct from the final results.

The current standalone PostgreSQL18.6 lifecycle passed the five-scenario fault demo at 09:25:05 UTC, then stopped all seven runtime roles, restarted from prepared artifacts, retained all four workflow instances, and stopped again. No volume was deleted. The [hardening evidence](../evidence/runtime-hardening-2026-10-20.json) records commands, exact test/mutation denominators, source/receipt identities, image pin, observed demo outcomes and local log digests. A final diagnostic-only script change makes restricted listener ownership actionable; it does not relax the failure policy.

## Historical correctness evidence

The retained [verification envelope](../evidence/verification.json), [environment](../evidence/verification-environment.json) record a successful integration build on 2026-10-18: **68 ordinary methods and 13 PostgreSQL methods**, with no failures, errors, or skips. That historical build used PostgreSQL 18.3; current Compose and integration tests pin PostgreSQL 18.6. Source identity and exact commands are recorded in the evidence files.

That suite exercises:

- Typed scalar/schema validation, vector cardinality, predecessor propagation, and fixed-seed replay properties.
- Atomic history/scheduling, explicit version checks, killed-before-commit rollback, duplicate delivery, and signal/timer races.
- Stale dispatch, heartbeat, and completion; worker termination after independently recorded provider effects; uncertain outcomes and audited resolution.
- Cancellation, late completion, reverse compensation, failed compensation, and authorized retry.
- JWT signatures and original claim types, tenant/scope enforcement, chunked body limits, problem responses, and OpenAPI drift.
- Streaming recovery of a retained provider receipt from a 40 MiB log in a 32 MiB child JVM.

The historical model coverage was 94.20% lines / 88.70% branches; engine coverage was 96.34% / 84.76%, against 85% / 75% thresholds. The selected `Data*` mutation run killed 41 of 41 generated mutants. [Mutation results](../evidence/mutations.xml) retain the full denominator. These figures describe their recorded source revision; they are not current or engine-wide mutation claims.

## Standalone recovery and performance

The [lifecycle evidence](../evidence/lifecycle/envelope.json) records 14 successful command steps, including two fault-demo rounds, retained data across stop/start, reset to an empty database, and independent stopped checks. The demos observed one idempotent effect despite repeated dispatch, one non-idempotent effect with visible uncertainty and reconciliation, failed compensation without an invented rollback, and a pinned definition across upgrade.

The [100-execution smoke run](../evidence/smoke-100/envelope.json) completed 800 activities and unique provider effects, 160 first-attempt rejections, 100 accepted signals, and 50 timers with an empty final queue. Driver elapsed time was 33.877 seconds on the recorded shared environment. This checks the workload machinery; it is not a full capacity baseline.

A subsequent full reference attempt completed two 10,000-execution runs in 962.372049 and 1611.414379 driver seconds. The third was refused before reset because available workspace storage was below the unchanged 2 GiB reserve. The attempt therefore failed. [Storage evidence](../evidence/quiet18-storage/README.md) preserves the archive/recovery accounting for 44 files and 3,912,701,887 bytes. The two results must not be combined with a later run to claim a complete study.

The [gzip capture repair](../evidence/gzip-capture-repair.json) records post-measurement compression and reader controls. It does not change workload dimensions or certify a new full run. Three fresh complete runs, same-environment variability checks, and a declared regression budget remain required; see [performance protocol](PERFORMANCE.md).

## Remaining validation

- Run the complete three-round reference workload on an exclusive measurement host with adequate retained-storage capacity.
- Verify clean-checkout restoration and the hosted release/security pipelines independently of local build success.

Earlier failures remain recorded in the evidence. Review scopes are described in [the review record](REVIEW.md); successful tests do not close unrelated operational or performance requirements.

## 2026-10-21 production hardening validation

The transaction boundary now preserves acknowledged committed results when connection cleanup fails, while lost commit acknowledgements and domain/rollback failures remain visible. Public JSON requests reject duplicate fields, fractional integer truncation, null primitive fields, numeric enum ordinals, trailing values, and number/boolean coercion into strings. A direct default-mapper reproduction accepted batch 4.9 as 4 and duplicate batch 4/batch 1 as 1; the new configured-mapper regression rejects both.

A current-source combined integration/mutation build passed **98 ordinary Java methods and 22 PostgreSQL integration methods**, with zero failures, errors or skips. Strict mutation results were 41/41 Data mutants, 158/162 current-interpreter mutants and 54/55 selected persistence-policy mutants, against the unchanged 70% per-module threshold. The five survivors remain un-killed; there were no timeouts or runner/memory errors. Nine preparation and ten lifecycle helper tests also passed with JDK 25 activated.

[Raw reports and source manifest](../evidence/production-hardening-20261021/validation.json) identify the tested source. This does not replace the incomplete three-round 10,000-execution performance baseline, certify a stable capacity budget, or establish hosted CI/security results.
