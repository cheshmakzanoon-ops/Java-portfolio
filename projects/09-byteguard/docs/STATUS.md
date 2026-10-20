# Verification status

ByteGuard's current correctness and packaged-CLI checks pass. Performance, mutation results, and earlier demo receipts apply to their recorded source revisions; they are not automatically inherited by later changes. Repository-wide clean-checkout restoration, hosted CI, and final release remain separate checks.

## Current hardening

The 2026-10-20 verification completed at `07:42:41Z`: **70 ordinary test methods and 2 packaged-CLI integration methods**, with zero failures, errors, or skips. Formatting and configured coverage gates passed.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/09-byteguard/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A09/m2 -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration verify
```

Seven new regression methods exercise:

- A valid JVM method name containing `|`, `@`, and `:`, preserving the correct source instruction and descriptor through finding/report generation.
- Entry-limit enforcement across individually selected application and classpath classfiles.
- Output alias protection for direct paths, hard links, symbolic parent directories, rules, and baselines, including the failure-report path.
- Filesystem-correct resolution of a symbolic parent followed by `..`, preserving an existing input and refusing a new report inside the aliased scanned directory.
- Preservation of an earlier report when serialization fails, with temporary-file cleanup.

The frozen 120-case corpus remains unchanged and reports 60 true positives and 60 true negatives. Packaged-process checks still exercise all four exit codes, SARIF schema validity, the invalid descriptor regression, and a legal long-identity case in a 64 MiB child JVM.

The full benchmark and mutation profile were not rerun for this hardening change.

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
