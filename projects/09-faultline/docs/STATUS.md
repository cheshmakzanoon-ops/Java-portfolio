# Verification status

Faultline's current correctness and packaged-CLI checks pass. Performance, mutation results, and earlier demo receipts apply to their recorded source revisions; they are not automatically inherited by later changes. Repository-wide clean-checkout restoration, hosted CI, and final release remain separate checks.

## Post-rename repair (2026-10-29)

Running `verify` on the renamed tree showed two failures that the 21 October results below predate. (1) The rename had rewritten the `BYTEGUARD_INPUT` literal in all 240 frozen corpus fixtures, so `CorpusTest` failed its source-hash check; the original bytes were restored and checked against both frozen label sets, and the corpus labels, results and thresholds are untouched ([corpus note](../corpus/README.md#byte-immutability-across-project-renames)). (2) `StableIdentityTest.structuredHashesRetainIndependentPublishedTupleVectors` still held vectors computed for the old hash domain (`byteguard/structured/v1/...`). The production code is self-consistent with an independent Python computation for the new domain `faultline/structured/v1/...`, so the vectors were regenerated rather than the code changed. The consequence is real and bounded: fingerprints of findings or methods whose names contain `|` or an unpaired UTF-16 surrogate differ from any produced before the rename; ordinary names keep their legacy hashes and are unaffected. After both repairs, ordinary `verify` passes for the whole reactor (JDK 25.0.4.1).

## Current verification

The configuration-boundary pass completed on **2026-10-21 at 20:15:49 UTC** with **143 ordinary tests and 3 packaged-CLI integration tests**, zero failures, errors, or skips. The full `-Pintegration,mutation verify` reactor and independent freshness/completeness readers passed. Core aggregate coverage was **1,735 / 1,822 lines (95.23%)** and **1,111 / 1,303 branches (85.26%)**, above unchanged 85% / 75% gates. The frozen 120-case corpus and its labels remain unchanged.

New controls cover exact model fields and integer types, required arrays, duplicate keys/declarations, trailing documents, file/byte/depth/token limits, descriptor validity, constructor semantics, and redacted pre-scan configuration failures. Baselines now require explicit Faultline/model identity, supported rules, and exact fingerprints without rejecting normal SARIF extension fields. An independent review found that a blanket string cap rejected the analyzer's own legal long-identity report; the document byte budget now bounds those strings. A real packaged JVM regression generates a report over 700 KiB, then successfully reads it as an unchanged baseline.

The existing mutation scope was rerun without weakening targets or thresholds: **527 / 727 core mutants killed (72.49%)** and **30 / 30 report identity/publication mutants killed**. The **106 survivors and 94 uncovered core mutants remain in the denominator**. The new configuration parser classes are covered by direct boundary and packaged-process tests; they are not claimed as part of the existing PIT target set. No fresh performance benchmark or demo lifecycle was run for this change.

The [configuration-hardening receipt](../evidence/configuration-hardening-20261021/validation.json) records commands, artifact/source hashes, test and mutation outcomes, coverage, and limitations. Its source manifest identifies this verification separately from the earlier evidence below.

## Prior runtime-hardening verification

The deeper runtime verification completed on **2026-10-20 at 09:56:55 UTC**: **106 ordinary test methods and 2 packaged-CLI integration methods**, with zero failures, errors, or skips. The frozen 120-case corpus remains unchanged: **60 true positives and 60 true negatives**. Core aggregate coverage is **1,701 / 1,793 lines (94.87%)** and **1,073 / 1,273 branches (84.29%)**, above unchanged 85% / 75% gates.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/09-faultline/pom.xml \
  -Dmaven.repo.local=.work/A09/m2 -B -ntp -Pintegration,mutation verify
```

The added semantic controls exercise mixed native/clean dispatch, fresh/aliased opaque mutable returns, helper write effects and allocation-length dependencies in both worklist schedules. Serialized classfile controls distinguish valid branch-local reuse from invalid arithmetic/receivers/returns, incoming handler overflow, missing concrete bodies, reachable fall-through and malformed exception ranges. Archive controls cover complete ZIP/ZIP64 indexes, CRC/size/count corruption, metadata bounds, directory payloads, manifest ambiguity and nonregular files. Reporting controls preserve actual scanned bytes through DOT symbolic/hard links and keep delimiter/unpaired-UTF-16 identities distinct through real finding/explanation output.

A deterministic parser-budget test includes every unanalyzed method from the completed class prefix in its frontier. An instrumented label-index test performs16,384 lookups through4,096 metadata nodes without forward rescanning; this verifies an algorithmic boundary, not a performance benchmark. Duplicate declarations, whole field descriptors, provenance tuple joins and ordinary fingerprint stability have direct controls.

Packaged-process checks exercise all four exit codes, SARIF schema validity, a nonexecuted initializer sentinel, the hostile descriptor regression and a legal long-identity case in a 64 MiB child JVM. The actual local up/demo/down lifecycle passed after a fresh ordinary source-cache build at **09:58:38 UTC**. Its four scans returned the asserted1/0/3/0 exits for a cross-method finding, corrected parameter binding, explicit partial frontier and unchanged baseline. No background service remains. The [hardening evidence](../evidence/runtime-hardening-2026-10-20.json) records source/artifact identity, exact commands, test/corpus/coverage counts and local log digests.

The expanded mutation campaign passed the unchanged **70%** gates with the full generated denominator:

| Scope | Killed / generated | Survived | No coverage | Timeout / runner error |
| --- | --- | --- | --- | --- |
| Nine core components | 527 / 727 (72.49%) | 106 | 94 | 0 / 0 |
| Report identity and owned publication | 30 / 30 (100%) | 0 | 0 | 0 / 0 |

The core scope includes the prior lattice/frame/worklist/CFG components plus transfer functions, invocation effects, hierarchy resolution, summaries and ZIP-index validation. All19 invocation-effect mutants were killed; summary composition killed20/22, with two equivalent comparison-boundary survivors. Uncovered core mutants comprise84 in `TransferFunction` and10 in `TypeHierarchy`: evaluation-module tests do not enter the core-unit PIT classpath. Those94 and all106 survivors remain counted. This scope is larger than the historical177-mutant scope, so their percentages describe different denominators.

The initial expanded campaign exposed missing direct assertions for exported opaque writes, seeded mutable return identity, summary sink/field substitution and tuple encoding. Added contract tests close those gaps. A final low-surrogate pair distinguishes two names that lossy UTF-8 would merge; the unchanged production implementation rejects that mutation. Earlier516/727 core and23/30 report results, then527/727 and29/30, remain separately recorded in the hardening evidence. No threshold, target scope, fixture label or survivor exclusion was relaxed. No fresh benchmark was run.

Five controlled shell-cache regressions also pass. Source drift during verification refuses receipt publication; build/publication failures preserve prior evidence; atomic receipt replacement preserves a linked target. The actual final source-cache preparation and demo above exercised the corrected path. Independent [packaged-CLI counterexamples](../evidence/independent-cli-2026-10-20.json) record six additional observed closures against the same final CLI hash. Passing these bounds and synthetic controls is not complete JVM verification or a field-accuracy estimate.

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
