# CURRENT: wave3 development29 tests PASS; coverage certification FAIL; later source is uncompiled WIP

As of 2026-10-18, development29 ran `spotless:apply test` through the ordinary wrapper and passed125 actual methods:16 ingest,42 index,45 query,2 service and20 evaluation;0 failures/errors/skips. All seven modules compiled, including CLI. The same-source `verify` rerun failed the unchanged index coverage gate:2389/2825 lines=84.566% and1483/2275 branches=65.187%, below required85%/75%. Its58 ingest/index tests passed; downstream verify modules were not reached. This is development evidence, not a full integration/PIT certification. Raw logs and fresh reports are preserved under `.work/A07/artifacts/development-build29-reports/` and `development-build29-verify-reports/`.

After29, actual catalog-field lexeme/prose/endpoint handling, synthetic archive-selection/provenance/quota tests, the finite wave3 audit→DEV-fit→VAL operator and boundary tests, explicit CLI phase routing, and expanded critical extractor/binder/unknown/quota/mask/packet/selector PIT targets were source-added. These later changes are **UNCOMPILED, UNRUN WIP**. No threshold/exclusion change bypasses the failed gate. The final wave3 protocol/freeze and root-prepared `r0-reference.tsv` remain pending; operator whole-phase execution, actual packaged integration,85/75 coverage and70% strict mutation must pass before feature export or scores.

**Quiet18 is active. No Java/Maven/Docker/JFR/feature export/training/validation/held-out access or new metrics are authorized until root explicitly releases execution.** Project/scratch source writes are paused for the requested checkpoint, apart from this narrow status/handoff correction. No build or Java process is queued/running by A07. Build25, both prior finals, labels, raw outputs, seals and bound historical artifacts remain immutable. No wave3 feature export, model fit, new quality metric or final evaluation has occurred. OriginalBASELINE remains the deployment recommendation; the mandatory quality targets remain unmet.

The previous status body below is historical build25 evidence. Its quiet10/source-freeze statements describe that earlier checkpoint and do not describe the later authorized wave3 WIP. Current next steps and exact paths are in `.work/A07/HANDOFF-wave3-development29.md`.

---

# IndexForge status: certified25; finite wave2 validation complete

Owner A07. Strict25 and the finite preregistered DEV/VAL wave are complete; full quality acceptance is not yet established. Root completed the exact separately reviewed versioned final at06:53:47Z: execution PASS, quality FAIL; baseline retained. All inference Java/POM/scripts, copied artifact,24 bound input files/operator/context/classes/fixtures remain frozen. No A07 final or Java invocation is active. Quiet10 is active: no Java/Maven/Docker/JFR/training/evaluation/new metrics or inference edits. Final seals/raw and all24 bound files remain immutable; only this small packet/docs/handoff update is authorized before write pause.

## Actual strict25 source/artifact and checks

Source `c0ab80c6a5e14d5836b9e69813f18d16acda382a97686df2d020c45622a2e8d2`. Copied CLI `.work/A07/artifacts/wave2-build25-cli.jar` SHA `e2d947c97a6b5b5a5fc6d6696d1ca56241eed11f9cfbb772b0d1880a6217dbc7`. Effective Jupiter6.1.3. `evidence/certification-build25.json` binds source/rootPOM/artifact, all fresh test/PIT/coverage reports and raw log. Exact reports are also retained at `.work/A07/artifacts/build25-reports/`; no earlier report/history was deleted.

Actual command, through ordinary shared wrapper with a positive cutoff:

```bash
source scripts/cloud-env.sh
export PORTFOLIO_TEST_RUN_STARTED=$(date +%s)
./mvnw -f projects/07-indexforge/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A07/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration,mutation verify
```

BUILD SUCCESS. Fresh required-report checks independently pass88 tests/0 failures/0 errors/0 skips. Expanded targeted PIT has524 KILLED/628 total=83.43949% strict killed rate;100 SURVIVED,1 terminal TIMED_OUT,3 NO_COVERAGE,0 MEMORY_ERROR/RUN_ERROR/unknown statuses. All outcomes/XML/HTML are preserved, not described as100% mutation strength. Core line/branch: ingest95.73/82.21,index92.16/81.72,query96.76/87.50. Selected original evaluation scaler/folds/trainer/selector/metrics each satisfy85/75; broad evaluation/service adapter bundles are not claimed to meet those class gates. All declared70 mutation and85/75 coverage gates pass.

Actual packaged Boot HttpSecurityIT tests pass. The actual CODE_HIGH12→24 bytecode restart regression passes with the same profile name/model bytes/principal/scope/version, changed real score and old learned/baseline cursor rejection. Complete supported artifact origins or all exploded original classes/nested/anonymous classes and actual numeric profile values are now bound at startup. Code-bound baseline v2 is only in25; frozen24 v1 remains immutable history.

## Finite wave2 results and fixed selection

ApprovedV3 remains70 substantive authored source/doc documents and80 graded queries40DEV/20VAL/20held-out; original labels/corpus/provenance/first-final seals/results are unchanged. Preregistration amendment5 and its preceding pre-execution amendments are preserved in `fixtures/ranking-wave2/`. Runtime syntax, not fixture type/ID, gates the new lane; no query/project/document/role prior was added. All no-regression/objective/tie rules were frozen before measurements.

Exact copied25 CLI ran audit→DEV-fit→VAL. Label-blind40-query top500 export hash `6dadc45d53a0ed8be2dd8dd4f8e690eb8be971912b9bef8987c16e01ec27d024`. Identical full DEV judgment groups remain together in five fixed folds; scaling is DEV-only and equal-query. L2 .01 FAILED the100-iteration convergence bound and is preserved, with no optimizer rescue. L2 .1 OOF nDCG=.7143448006/identifierMRR=.925; L2 1 OOF=.7311403168/.925 and wins the fixed DEV objective. The once all-DEV refit and every attempted fold model/path/failure remain.

All2160 per-query rows and72 aggregate rows are retained under `evidence/relevance/ranking-wave2-build25/attempt-1/`. C0 exactly replays every540 previous DEV/VAL metric/path row. BASELINE matches all60 rows across every strategy. An independent Python recomputation of2160 metrics using only A06's filtered DEV/VAL labels agrees within2.22e-16. Copied-context feature/protocol/model tampering is actually refused before training/evaluation/data creation; original attempt files remain byte-identical. `verification.json` and `phase-identity-refusals.json` bind those proofs.

| Family/mode | DEV nDCG10 | DEV identifierMRR10 | VAL nDCG10 | VAL identifierMRR10 |
| --- | ---: | ---: | ---: | ---: |
| Original BASELINE | .4974954616 | .6754761905 | .4369789084 | .75 |
| C0 CLASSIC/SOURCE_AWARE | .6802153485 | .925 | .5804173420 | .7833333333 |
| C1 SYNTAX_GATED/SOURCE_AWARE | .7066751871 | .925 | .6585820908 | .7833333333 |
| D1 DECLARATION_TIER/SOURCE_AWARE | .7066751871 | .925 | .6585820908 | .7833333333 |
| C2 PAIRWISE/SOURCE_AWARE | .7333543986 | .925 | .6727222173 | .7833333333 |

Fixed winner is C1/SYNTAX_GATED/SOURCE_AWARE/CODE_HIGH/modelNONE, evaluation identity `f938b3fea0324fdbeac8bb781b42bec7f38f99af4f86cda5db8a30495bc98399`. C1 and C2 tie on the identifier-limited objective .7833333333/.90; fixed family order chooses C1 despite higher C2 nDCG. D1 equals C1 metrics. C2's diagnostic model/scaler/provenance and all failed/ablation scores are retained; it is not selected after seeing final results. DEV/VAL improvement does not pass mandatory held-out .75/.90.

## Separate versioned final operator

The external helper/launcher/protocol are under `evidence/relevance/ranking-wave2-build25/operator/`; they do not rebuild or modify the certified inference artifact. Java source SHA `e06aabe3151011fdc1687ebe27e12dd9f68189ee8bec6b2a7a846296484b9e75`, launcher SHA `6bbc15be953678d6c10eab1c7cf8172dad6a4cc3d9dc887dd545e907e99b852b`, protocol SHA `0a447addfa23a68cd711a6202a212220ea0d32fe3cb66fcb206b629c435b8bda`.

Under explicit finite preparation grant, javac25 passed,19 synthetic assertions passed, and actual frozen C1/baseline identity preflight passed without opening corpus/judgment content, data or seals. Prepared context `.work/A07/final-operator-build25-C1/prepare-1/context.properties` SHA `9ffea68f5ec220eee3a6665e474a73d001255c3c92cddab1320fb6884e9ac5ee`; helper bundle SHA `bc1711be47cce6ef920ab7be236e8e483f6c47d2ba278fdc7bc9ec544ce35ab5`. Root independently reviewed the exact context/proofs and owns the once-only final execution/approval. No A07 final launch or per-query held-out inspection occurred. Root execution completed once, exit0,180 rows in9620ms; `execution-evidence/` copies exact approval/context/preparation logs and small aggregate/completion metadata. Worker did not open held-out per-query output.

New global/local CREATE_NEW seals are separate from original first-final seals; any partial failure/timeout consumes this context, with no automatic rerun. Final compares fixed C1 to actual CLASSIC/CODE_HIGH content BASELINE and reports all20 queries/all9 existing modes without test-winner reselection. This is an explicitly disclosed repeated test on an authored corpus, with reused validation; not a fresh blind external benchmark.

## Historical boundaries, first final and performance

Six modules retain bounded UTF8/directory/archive ingestion/CAS/manifests/jobs/recovery, atomic checksummed generations/leases, bounded Lucene query/planning/cursors, Boot verified OIDC/auth/admission and CLI/evaluation/workload boundaries. Actual regressions retain10k idempotency,100 seeded crash schedules, malformed/path/symlink/archive/cancel/kernel-/dev/full cases, unknown-outcome A→B→resumeA reconciliation/checksummed receipts, root-relative partial updates, safe explanation and warmed ACL/cache/cursor/revocation checks. Residual tenant-reader BM25 score/order statistical coupling remains documented; statistical noninterference is not claimed.

Frozen24 source/artifact/first-final results/seals remain unchanged. First baseline held-out nDCG=.6012721858/identifierMRR=.8833333333, first selected CODE_HIGH/SOURCE_AWARE=.6267961004/.8; first P07-A4 FAIL, baseline retained. All first-final failed ablations remain diagnostic, never silently adopted. Prior detailed status is retained as `docs/STATUS-before-build25.md`.

Root completed frozen24 application-boundary100k/2GiB reference:3×900 requests at30offeredRPS,0errors,p95=4.727653/6.977346/2.607306ms, warm bounded cache, leases/ACL/facets/explanations included, HTTP excluded. Exact evidence is `evidence/performance/reference-build24-attempt1/`. This does not certify latency for newly selected25 C1. New configuration performance and root fresh-checkout release remain pending. Compiled runtime defaults remain BASELINE/CURRENT/CLASSIC.


## Actual versioned final: P07-A4 remains FAIL

Root directly executed once after reviewing the exact prepared context/binary and writing the matching approval. COMPLETED at2026-10-18T06:53:47.012781673Z,9620ms,20 held-out queries/all9 existing modes/180 rows; no rerun, inference change or held-out-winning ablation selection. Original first-final and versioned wave2 seals/raw/labels/selection remain immutable. `execution-evidence/packet.json` and copied small approval/context/preparation/completion/summary files record the result outside the24 frozen bindings.

| Frozen comparison | Held-out nDCG10 | Held-out identifierMRR10 | Decision |
| --- | ---: | ---: | --- |
| Original BASELINE | .6012721858 | .8833333333 | Retained |
| C1 SYNTAX_GATED/SOURCE_AWARE | .6594266978 | .8 | Reject adoption; quality target fails |

C1 improves nDCG over baseline and first candidate, but identifierMRR regresses versus baseline. The fixed guard retains ORIGINAL_BASELINE; neither required .75nDCG/.90identifierMRR is achieved by C1 and all9 diagnostic target flags are false. Diagnostic test winners cannot be adopted. Per-query output SHA from root completion is96db19ecb434f2059cbb358a79a422e81fdb61df3d97a82c8de611e5a2a59f0e; aggregate summary SHA2e58b7ebe7d3c35a905a444ba9b9276adddaa41fb2fbcd0c599897e36a2d14cd. Worker read only permitted aggregate/completion metadata, never these per-query rows.

The intended mandatory quality goal is still unmet. After this immutable packet/root commit, the coordinator will separately authorize a DEV/VAL-only representation audit and preregister any next finite wave. No further inference edits, new metrics, held-out diagnostics, relabeling or model selection is authorized now. All owned writes pause after the current handoff.
