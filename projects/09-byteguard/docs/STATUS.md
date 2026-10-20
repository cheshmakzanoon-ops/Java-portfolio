# Verification status

ByteGuard's current correctness and packaged-CLI checks pass. Performance, mutation results, and earlier demo receipts apply to their recorded source revisions; they are not automatically inherited by later changes. Repository-wide clean-checkout restoration, hosted CI, and final release remain separate checks.

## Current verification

The deeper runtime verification completed on **2026-10-20 at 09:32:37 UTC**: **99 ordinary test methods and 2 packaged-CLI integration methods**, with zero failures, errors, or skips. The frozen 120-case corpus remains unchanged: **60 true positives and 60 true negatives**. Core aggregate coverage is **1,700 / 1,793 lines (94.81%)** and **1,071 / 1,273 branches (84.13%)**, above unchanged 85% / 75% gates.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/09-byteguard/pom.xml \
  -Dmaven.repo.local=.work/A09/m2 -B -ntp spotless:apply -Pintegration verify
```

The added semantic controls exercise mixed native/clean dispatch, fresh/aliased opaque mutable returns, helper write effects and allocation-length dependencies in both worklist schedules. Serialized classfile controls distinguish valid branch-local reuse from invalid arithmetic/receivers/returns, incoming handler overflow, missing concrete bodies, reachable fall-through and malformed exception ranges. Archive controls cover complete ZIP/ZIP64 indexes, CRC/size/count corruption, metadata bounds, directory payloads, manifest ambiguity and nonregular files. Reporting controls preserve actual scanned bytes through DOT symbolic/hard links and keep delimiter/unpaired-UTF-16 identities distinct through real finding/explanation output.

A deterministic parser-budget test includes every unanalyzed method from the completed class prefix in its frontier. An instrumented label-index test performs16,384 lookups through4,096 metadata nodes without forward rescanning; this verifies an algorithmic boundary, not a performance benchmark. Duplicate declarations, whole field descriptors, provenance tuple joins and ordinary fingerprint stability have direct controls.

Packaged-process checks exercise all four exit codes, SARIF schema validity, a nonexecuted initializer sentinel, the hostile descriptor regression and a legal long-identity case in a 64 MiB child JVM. The actual local up/demo/down lifecycle passed after a fresh ordinary source-cache build at **09:36:38 UTC**. Its four scans returned the asserted1/0/3/0 exits for a cross-method finding, corrected parameter binding, explicit partial frontier and unchanged baseline. No background service remains. The [hardening evidence](../evidence/runtime-hardening-2026-10-20.json) records source/artifact identity, exact commands, test/corpus/coverage counts and local log digests.

The expanded mutation campaign is running separately against the frozen source; no new benchmark was run. Current mutation results will state their complete expanded scope and denominator rather than inheriting the historical four-class score.

The first deeper-pass semantic run failed on a shared-receiver clean control: the existing weak object-insensitive heap conservatively contaminates that call. The regression now records that known false positive and separately requires the isolated clean control to remain clean; corpus labels are unchanged. A later full build stopped on a test import ambiguity before execution; the explicit import correction preceded the successful full result above. These source-specific failures are distinct from the final successful result.

## Historical evidence

| Check | Recorded result | Evidence |
| --- | --- | --- |
| Clean reactor, integration, critical mutation profile | 65 methods; no failures, errors, or skips; exit 0 on 2026-10-18 | [Verification](../evidence/verification.json) |
| Core coverage | 1,475 / 1,564 lines (94.31%); 893 / 1,069 branches (83.54%); gates 85% / 75% | [Coverage](../evidence/coverage.json) |
| Selected algorithm mutations | 164 / 177 killed; 13 survivors; no uncovered, timeout, or runner-error results | [Mutation](../evidence/mutation.json) |
| Frozen v2 corpus | 60 TP / 60 TN / 0 FP / 0 FN; 20 TP and 20 TN per rule | [Every case](../evidence/corpus-results.json) |
| Three full 10,000-class scans | 3.9325 / 4.0275 / 4.3934 seconds; complete, 10,000 findings each; `-Xmx2g`, four-core quota | [Benchmark envelope](../evidence/benchmark-envelope.json) |
| CLI lifecycle | Reset, preparation, two asserted demos, and shutdown returned 0 | [Demo](../evidence/demo.json) |

The measured source fingerprint was `a6055cdfb18245240a3341c4a712c256e5502541dcdb85523e662423d7edf09c`; its packaged CLI SHA-256 was `390a7a39ef6041fdcbc347b5a0be437eead09a9feb0bb2277b11951a6783db93`. These historical artifact identities do not identify the current build.

The descriptor-allocation regression limits JVM parameter slots and array dimensions before entry allocation, uses compact lossless UTF-16 method scopes, and charges entry setup to the work budget. The original 65,627-byte invalid input returned exit 2 without exhausting a 64 MiB heap; its [exact input and child metrics](../evidence/descriptor-regression/evidence.json) are retained.

Earlier failures remain visible: an initial mutation score below threshold, alias/lambda regressions, a missing-time-utility benchmark preflight, and an enclosing-session exit discrepancy despite reactor success. A later independent clean command returned 0; this does not erase or explain that earlier discrepancy. Prior identities remain in `evidence/verification-before-r11.json`, `evidence/benchmark-before-r11.json`, and `evidence/history-before-r11/`.

The analyzer's bounded model, cross-call heap false positives, shell positional-argument false positives, and unsupported behaviors remain documented in the [threat model](THREAT-MODEL.md) and [review record](REVIEW.md). Passing the synthetic corpus is not a security certification or a field-accuracy estimate.
