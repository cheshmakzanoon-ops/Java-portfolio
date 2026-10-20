# Verification status

MatchCore provides executable evidence for deterministic matching, checked risk reservations, forced-journal recovery, bounded admission, and HTTP ownership. Current validation below is separate from historical mutation and performance results; an old source fingerprint does not certify later changes.

## Current hardening: 2026-10-20

Executable/build-input fingerprint: `094f64bbceacdd785ff28414c955a5463647ab23f136d94bba682e52ce436d28`.

The six-module `-Pintegration verify` build passed **24 test methods with zero failures, errors, or skips** on JDK 25.0.4.1 and Maven 3.10.0. This includes 23 unit/property methods and the separate-process `CrashIT`. Core coverage thresholds and format checks passed.

Three new methods cover strict HTTP command/query types, invalid-command rejection before journal writes, and checksum-valid frames with ill-typed durable payloads. The parser rejects numeric enum ordinals, scalar coercion and null primitives. Query validation detects duplicate decoded names, unknown keys and negative sequence bounds. Invalid append input leaves the journal unchanged and reusable at the same next sequence.

Reproduce from the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/06-matchcore/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A06/m2 -B -ntp -Pintegration verify
./scripts/portfolio.sh demo matchcore
```

No Docker or external credentials are needed. The demo uses an injected storage fault; `CrashIT` separately SIGKILLs child JVMs at four boundaries. The supported CLI demo also passed: four executions, eight lots, Alice cash 19,200, inventory eight, zero open orders, twelve results, and replay/reference digest `4e226aa11dbed09f79d746c5df50ac857e30804a2e43d3ccf39893d656f959e3`.

## Correctness boundaries

| Property | Evidence |
| --- | --- |
| Price-time matching agrees with a structurally independent reference | 120 seeded histories of 350 transitions; comparison after every command |
| Cash, inventory and reservation invariants hold | 12,000 generated transitions plus targeted overflow, maximum-supply, FOK, replacement and self-trade cases |
| Durable outcomes precede responses | Force barrier and child-process crashes before append, during a frame, after force and after apply |
| Corrupt complete records fail recovery | Header/payload corruption, every-byte torn-tail checks, strict payload-type regressions and snapshot fallback |
| Admission and reads are bounded | Queue/run caps, overload responses, stable retry identity and sequence-tagged immutable reads |
| HTTP enforces identity and ownership | Actual local RSA issuer/JWKS, signed claim negatives, scope checks and cross-account submission/result denial |

The finite run retains at most 1,100,000 unique command results and 100,000 open orders. Recovery assumes retained local storage honoring `force(true)`; it provides neither disk-loss tolerance nor replicated availability.

## Historical mutation and artifact checks

The 2026-10-18 full integration/mutation run passed 21 methods and killed **119/133 selected `Book` mutants**, leaving 14 survivors and no uncovered mutations, timeouts, or runner errors. [The survivor report](../evidence/mutation-survivors.json) retains all outcomes; equivalence is not assumed. That mutation result is historical and was not rerun for the later HTTP/journal boundary changes.

Seventeen artifact-integrity probes checked source binding, compiled/resource bytes, runtime classpath, dependency JARs and symlink rejection. [The receipt](../evidence/runtime-artifact-cache.json) records 67 bound artifacts for the earlier build. Current source changes intentionally invalidate older prepared-artifact receipts; supported scripts rebuild and verify before reuse.

## Historical reference study

Executable/build-input fingerprint `62fc0b0351864486ffa736bd64f444393e3dda894f93677c7ca95448dab1ce83` identifies the completed historical study. It ran three one-million-command repetitions in core mode, three in forced-journal mode, and three-fork JMH. Every full run produced the same replay/reference digest and business histograms. No repetition was discarded.

The workload peaked at **27 open orders**, despite a configured 100,000-order cap. The results therefore do not establish deep-book performance. Fixed-seed generation, closed-loop sampling, process-wide profiling boundaries, and warm recovery remain explicit limitations in [performance](PERFORMANCE.md).

[The measurement envelope](../evidence/reference-20261018/measurement-envelope.json) and [review](REVIEW.md) retain identities and arithmetic checks for 83 raw artifacts and 67 runtime artifacts. Large recordings, journals and logs were preserved in a verified local archive. [The manifest and recovery/removal receipts](../evidence/reference-20261018/README.md#restore-the-original-raw-files) explain physical restoration of all 98 files and 15 directories. That local archive is not included in a Git clone. The derived CSV's CRLF and LF identities remain recorded; parsed values and original raw identities are unchanged.

## Release limits

Clean-checkout reproduction, repository-wide checks, hosted CI, and release publication require their own evidence. Current local verification does not imply a production service-level objective, hardware power-loss proof, exchange certification, or complete repository release. Historical failures and smoke captures remain separate from successful reference measurements.
