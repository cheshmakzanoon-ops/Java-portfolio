# Versioned build25 C1 final operator — preparation protocol

Status: source preparation only; Java helper compilation/self-tests/checks and final execution have NOT_RUN. Root has not authorized opening wave2 held-out. Runtime preparation requires a separate explicit coordinator window; do not infer release from elapsed time. The current Java/POM/scripts/inference JAR remain frozen.

The finite wave2 experiment is complete before this operator: strict88 tests pass, fixed grouped DEV fitting and all60 DEV/VAL comparisons are preserved. The frozen validation tie rule selects C1 SYNTAX_GATED/SOURCE_AWARE/CODE_HIGH/modelNONE, not C2's higher-nDCG tied objective. No feature, model, label, ablation selection or compiled-default change is permitted here.

Frozen inputs:

- Source `c0ab80c6a5e14d5836b9e69813f18d16acda382a97686df2d020c45622a2e8d2` and `evidence/certification-build25.json`.
- Copied inference CLI `.work/A07/artifacts/wave2-build25-cli.jar`, SHA `e2d947c97a6b5b5a5fc6d6696d1ca56241eed11f9cfbb772b0d1880a6217dbc7`.
- C1 evaluation identity `f938b3fea0324fdbeac8bb781b42bec7f38f99af4f86cda5db8a30495bc98399`; actual baseline CLASSIC/CODE_HIGH identity `75a5f85ef95c35c0a1f77d1cc4a35b3cca8fe1a90eef9ad8664cb85656316c0f` with BASELINE mode.
- Frozen selection/2160 DEV-VAL rows/72 summaries/pre-VAL boundary/family identities/audit export/fit report and model; all hashes are added to the prepared context. The rejected C2 artifact and lambda.01 convergence failure remain history.
- Ranking preregistration amendment5 and manifest `da02dee92134b3b12a44aa8d6e37e808590b4d96304122d5825960003bdae1e7`, both fold files and C0 comparator; original fixture FREEZE.sha256 and independent review marker. Preparation reads these metadata hashes, not actual corpus/judgment content.
- Operator Java source, Python launcher, this protocol and the exact compiled helper-class bundle. The prepared context hash is the final approval target; no approval file is created by preparation.

`FinalWaveOperator.java` is an external local helper in an evidence directory, compiled separately against the copied CLI. The inference JAR is FIRST on its runtime classpath, so all original inference/metrics classes and artifact identities remain frozen. This helper is outside the certified inference source fingerprint and is not covered by the88-test certification; its synthetic correctness checks and actual identity preflight must pass separately before root final approval. Different code here requires a fresh preparation/context and review, never an inference rebuild or rescoring-based selection.

Preparation/check operations validate exact hashes, pinned family/configuration and actual C1/baseline model fingerprints without opening search data, corpus, judgments or final seals. The synthetic self-test uses only newly authored temporary rows. It checks split-before-content parsing, query/grade/path/type/duplicate/count refusals, missing-positive metric policy, irreversible CREATE_NEW seal refusal, path/context bounds, target thresholds and baseline adoption guard. Compiler/check logs and class bundle persist in a fresh preparation attempt; failures are retained, never overwritten.

After root's separate explicit final approval, the fixed execution path verifies approval context/source/binary identities, rejects any existing versioned seal/output/data, and creates durable CREATE_NEW seals before the first access to corpus/judgment bytes. New global seal is `fixtures/ranking-wave2/FINAL-ONCE-build25-C1.properties`; output is `evidence/relevance/ranking-wave2-build25/final-attempt-1`. These fixed paths cannot be replaced by a fresh output argument. Original first-final global/local HELDOUT-OPENED.txt files are never opened or written by the helper. Any claimed attempt, partial failure, timeout, crash or existing output consumes this one-shot context; no automatic resume/retry/delete is supported.

Actual execution verifies original frozen bytes after sealing, imports the unchanged70-document corpus into two fresh local indexes, and checks actual baseline/C1 identities again. Only then does it filter and parse the20 held-out rows, requiring ten identifiers/ten prose. Query IDs/types/grades are orchestration metadata; only original query text reaches inference. All20 queries run in all nine existing enum modes TOTAL: BASELINE, SOURCE_AWARE and seven declared feature ablations (180 per-query rows). BASELINE uses the CLASSIC baseline engine; the other eight modes use the frozen C1 engine. No family grid is opened on held-out.

Only the frozen SOURCE_AWARE candidate enters the adoption guard: retain C1 only if held-out nDCG strictly improves versus baseline with no identifier-MRR regression; otherwise retain original baseline. Diagnostic modes are never eligible for selection. Report .75nDCG/.90identifierMRR target attainment separately from the adoption guard. Defaults stay unchanged; any deployment remains an explicit later task. All results, failures, generation identities, exact model/runtime identities, input hashes and output hashes persist. COMPLETED is written only after both engines close successfully.

This is an explicitly versioned repeated test on an authored corpus. The first-final results/seals remain immutable history. Validation has been reused; earlier first-final aggregate results and two identifier failures were disclosed before the no-further-inspection directive. No new held-out per-query inspection, relabeling or held-out-based feature/ablation choice informed wave2. This is not a fresh blind external benchmark. First-final quality failure and every unsuccessful wave2 candidate remain visible.

Pending commands, from repository root, ONLY after explicit runtime-preparation grant:

```bash
python3 projects/07-indexforge/evidence/relevance/ranking-wave2-build25/operator/run_operator.py prepare --attempt 1 --grant ROOT_PREPARATION_WINDOW
```

This compiles only the helper, runs synthetic self-tests and label-blind identity preflight, then prints the context/binary hashes for independent root review. It does not execute final. A root-authored approval file must then contain the actual prepared values:

```text
action=execute-once
wave=ranking-wave2-build25-C1-final
approvedBy=root
contextSha256=ACTUAL_PREPARED_CONTEXT_SHA
operatorSourceSha256=ACTUAL_REVIEWED_SOURCE_SHA
operatorBinarySha256=ACTUAL_PREPARED_BINARY_SHA
```

Only after that review and a distinct explicit final execution grant:

```bash
python3 projects/07-indexforge/evidence/relevance/ranking-wave2-build25/operator/run_operator.py execute --context .work/A07/final-operator-build25-C1/prepare-1/context.properties --approval ROOT_AUTHORED_APPROVAL_PATH --grant ROOT_FINAL_WINDOW
```

The600-second outer timeout preserves partial outputs/seals and does not authorize a rerun. There is no benchmark or performance claim in this operator. Actual syntax, helper behavior, Boot-independent model identity preflight and all final results remain unverified until the corresponding commands execute.
