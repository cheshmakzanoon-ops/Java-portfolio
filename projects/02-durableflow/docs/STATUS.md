# Verification status

DurableFlow has executable replay, persistence, security, process-crash, and standalone recovery checks. The prescribed three-run performance baseline is incomplete, so no stable capacity budget or production-readiness claim is made.

## Current hardening

The provider transport now bounds response buffering, rejects redirects as acknowledgements, validates its fixed endpoint, and decodes receipt bytes as strict UTF-8. Generic HTTP errors remain uncertain; only the configured provider's explicit pre-effect refusal permits a definitive retry. Typed text screening also rejects credential markers across line separators.

Current verification passed on **2026-10-20**: **74 ordinary methods and 14 PostgreSQL integration methods**, with zero failures, errors, or skips. The build completed at `2026-10-20T07:31:38Z`. Its checks include formatting, coverage thresholds, actual process-crash recovery, and the new HTTP boundary regressions. A provider effect followed by HTTP 500 reaches manual review with exactly one effect and no successor claim.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/02-durableflow/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A02/m2 -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration verify
```

The five new transport tests exercise actual fixed-length and chunked responses, redirect refusal, valid and malformed Unicode, endpoint validation, and explicit refusal classification. The additional model regression covers multiline markers; the additional PostgreSQL regression verifies non-idempotent uncertainty after a server error. The standalone lifecycle and full reference workload were not rerun for this change.

## Historical correctness evidence

The retained [verification envelope](../evidence/verification.json), [environment](../evidence/verification-environment.json) record a successful integration build on 2026-10-18: **68 ordinary methods and 13 PostgreSQL methods**, with no failures, errors, or skips. PostgreSQL 18.3 uses the same digest as Compose. Source identity and exact commands are recorded in the evidence files.

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
