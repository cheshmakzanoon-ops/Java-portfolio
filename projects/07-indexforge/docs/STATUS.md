# IndexForge execution status

Owner A07; writes confined to this project and `.work/A07`. This is an incomplete acceptance checkpoint: current review fixes, relevance quality/held-out evaluation, final-source demonstrations and quiet reference performance remain open. No final acceptance or performance pass is claimed.

Implemented across six modules: bounded UTF8/directory/ZIP ingestion, content addressing, canonical manifests/checkpoints/resume; committed Lucene generations, checksummed atomic pointers, startup verification/fallback/quarantine, leases and retirement; original identifier analyzer/parser/planner/reranker/cursors; Spring Boot authenticated HTTP and local administrative CLI; original relevance metrics, reproducible workload generation and application-boundary benchmark. See INVARIANTS, ARCHITECTURE, SECURITY, OPERATIONS and contracts for concrete limits.

## Verification checkpoint

Latest completed full certification is build18:55 tests, zero failures/errors/skips, effectiveJUnit6.1.3; selected PIT95/95 KILLED across CAS, anonymous-tokenizer-inclusive analyzer, cursor and metrics. Whole ingest/index/query bundles enforce85%line/75%branch; original metric algorithm has a class gate. Reports contain nonzero XML/HTML. Exact counts and identities are in `evidence/certification-build18.json`; source fingerprint `2f31ecea5b8d7add7220c0794ea6e7c0bbf1136deeeb608cd87da11cc790e8cf`, CLI SHA `a7c61014fcae7edd259b1a54860901bc704da3bc4a408fba7630eb26ee0c827f`. Copied historical artifacts remain in `.work/A07/artifacts`.

Command (source freezes when queued):

```
source scripts/cloud-env.sh
./mvnw -f projects/07-indexforge/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A07/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration,mutation verify
```

Build17 completed FULL PASS at2026-10-18T02:21:41Z, `.work/A07/verify-seventeenth.log`. All Java/POM/scripts were frozen from queue submission through exit. Core line/branch coverage: ingest95.73%/82.21%, index91.34%/78.48%, query97.09%/85.60%. It adds independent A06 review fixes: committed job identity and parsed checksummed completion receipts reconciled before later tenant writes/startup; AFTER_POINTER/RETIRE × live/restart stale-replay regressions; corrupt receipt status/resume/startup failures; repository-root-relative selected-file/directory identity and replacement regression; safe explanation feature summaries with denied-repository fresh/cache/count/facet/snippet/suggest/revocation regression. All54 tests and selected95 mutations passed; A06 independently reinspected this exact source and closed the reviewed correctness/security findings after evidence acknowledgment.

Build15 failed newly authored concept/query-budget fixtures: lowercased repeated alphabetic input did not expand;60 distinct term-number tokens exceeded the768-leaf query budget. Corrected fixtures retain budget semantics. Build16 ordinary tests passed, but PIT baseline rejected the former raw-long-explanation test without mutation; its failure log is preserved. Build17 replaces that raw-output contract with the required safe explanation contract rather than exposing Lucene collection statistics.

Earlier complete builds:12 had43 tests,13 had46,14 had48; exact envelopes remain under evidence. Build8 used effectiveJUnit6.0.3, whereas12 onward used6.1.3. Earlier failures9–11 preserve compile/scanner/query-fixture defects in their raw logs. Real regressions include100 seeded publication/build failure schedules,10,000-record idempotency, immutable resume after source deletion, interrupted rebuild recovery, concurrent committed-reader availability, explicit deletion, reader retirement/pruning, kernel `/dev/full` ENOSPC before publication, malformed ZIP metadata, strict original signed JWT claim types, raw-byte admission and actual HTTP ingestion-queue saturation. `/dev/full` is a real kernel write failure; it does not simulate a filled index volume.

## Demonstrations

Actual build8 2k demo passed (seed7007,439546 bytes,3 tenants). Actual certified build12 20k demo passed (seed7007,4,435,559 bytes,3 tenants), including concurrent replacement, explicit deletion, denied content, interrupted build and restart. `evidence/demonstrations/build12-20000.json` binds jar/source/log identities. These are historical-source evidence; final-source script demonstrations remain required.

## Frozen relevance data and observed results

V1 toy smoke corpus was rejected by independent coordinator review before metrics and preserved under `fixtures/smoke-v1`. V2 contains70 substantive original sources with provenance and plausible near-domain distractors. Independent review removed16 unsupported secondary labels before any measurement; V2 and correction audit remain preserved. V3 was independently approved before metrics with80 queries split40 development/20 validation/20 held-out. Corpus SHA `0162c35bd70d995e2ab0feb14ccf96c7cec76666338b54a6d4a8689efd786ed9`; judgments SHA `77a370782ec74c14036ad641f727fb41283e9baceb85ee2fdf2671a3d750b7ec`. Labels have not changed after results. Unjudged documents contribute zero pooled gain, not universal irrelevance. Generic identifiers use documented intended task labels.

| Experiment/mode | Development nDCG10 | Validation nDCG10 | Validation MRR10 | Validation Recall20 |
| --- | ---: | ---: | ---: | ---: |
| Original baseline | .497495 | .436979 | .644722 | .733333 |
| Build8 source aware | .597963 | .454669 | .505754 | .716667 |
| Build12 declaration candidate | .556967 | .534909 | .655000 | .716667 |
| Build12 no-path ablation | .554456 | .553540 | .688333 | retained raw |
| Build13 prose candidate | .610129 | .500717 | .575972 | .750000 |
| Build13 no-identifier ablation | .557010 | .455762 | .593948 | retained raw |
| Build13 no-path ablation | .596364 | .500689 | .574365 | retained raw |
| Build13 no-prose ablation | .597877 | .484083 | .550198 | retained raw |
| Build17 concept candidate | .623410 | .509058 | .626389 | .766667 |
| Build17 no-identifier ablation | .572540 | .516168 | .680833 | .816667 |
| Build17 no-path ablation | .616638 | .510042 | .628056 | .766667 |

All per-query rankings, failures, summaries and selections remain under `evidence/relevance/`. Build8 predates the stronger compiled-model fingerprint and is preliminary only. English stopwords/Porter stemming and BM25 are reused Lucene algorithms, not claimed original. Conservative Java declaration hints and their development influence are documented. Current new concept associations are handwritten software-topic associations, not synonyms/external-thesaurus output; catalog/provenance/hash frozen in `fixtures/CONCEPTS-v1*` before concept measurement, with explicit Boolean/phrase/field/single-identifier controls and NO_CONCEPTS ablation. Certified build17 concept dev/validation ran once; validation identifier-only MRR=.783333 for SOURCE_AWARE/NO_PATH versus baseline .750000 and no-identifiers .700000. The latter has the highest validation nDCG but worsens identifier quality, so it is not an acceptable final choice. The raw initial selection is preserved; it has not authorized any held-out use.

Held-out queries have NEVER been executed or measured. Required quality nDCG10>=.75 and identifierMRR10>=.90 are not established. Runtime default remains BASELINE. Final development/validation decision must bind compiled-model bytecode and fixture hashes before held-out is opened once; preserve baseline/ablations and retain baseline unless the candidate improves held-out nDCG without identifier-MRR regression. Continue general development-driven improvements before final selection; no query-to-label lookup or post-result relabeling.

## Independent reviews and remaining gates

A06→A07 review identified a high ambiguous-publication stale replay, medium selected-path identity defect and raw explanation collection-statistic disclosure. Build17 fixes/regressions passed full certification and independent A06 final review closed these findings at the exact captured source fingerprint. SECURITY explicitly records residual tenant-reader BM25 score/order statistical dependence; ACL statistical noninterference is not claimed. Candidate/count/facet/suggest/snippet/cursor/cache authorization and final version recheck remain mandatory.

A07→A08 review found high accepted timing-tail shutdown loss and medium false active-limit metadata, both fixed and independently inspected. Final read-only check observed100 tests with zero failures/errors/skips and matched raw1200-second stress SHA to its envelope:174659 exported healthy agent timings matched monitor host operations; JFR queue loss remained explicit and all affected windows incomplete. A08 overhead and unsupported-platform gates remain separately owned and unclaimed.

Build18 completed FULL PASS at2026-10-18T02:31:01Z,55 tests0 failures/errors/skips, PIT95/95 KILLED. Core line/branch coverage: ingest95.73%/82.21%, index91.40%/78.48%, query96.73%/85.79%. Source was frozen from submission through exit; raw `.work/A07/verify-eighteenth.log`. Before measurements, `fixtures/WEIGHT-COVERAGE-v1.md` freezes six conventional field-weight presets plus original bounded query-term coverage over authorized candidate term vectors and NO_COVERAGE ablation. Selection rejects validation nDCG/identifierMRR regressions against baseline, then maximizes the weaker normalized nDCG/.75 and identifierMRR/.90 target. Preset identity is part of compiled-model fingerprint and per-engine scoring version. No corpus/project prior or label change is used. Context-free generic identifiers remain genuinely ambiguous across real declarations (publish, parse, apply, update); target failures remain visible.

Its fixed six-preset development/validation comparison COMPLETED from the copied certified artifact. Every profile retains per-query output/settings/failures, and baseline metrics were verified identical across profiles. Frozen rule selects CONTEXTUAL/NO_COVERAGE: development nDCG .653163, validation nDCG .5417399157401148 and identifierMRR .7666666666666666, versus baseline validation .43697890841425036/.75. SEMANTIC/NO_COVERAGE had nDCG .576122 but identifierMRR .733333 and was correctly rejected by the baseline constraint. Coverage did not consistently improve quality and is disabled in the selected candidate. All six results and exact commands are in `evidence/relevance/weight-coverage-build18/grid-selection.json`. Selected compiled model SHA `977f8a9dd5849d1e42b9d9d7396aee7fc0d5e5f94b2931605909721f053e4496`. The .75/.90 targets remain unmet; no held-out ranking has run.

RESOURCE PAUSE: coordinator requested all-owned-path handoff and worker inactivity after build18/grid completion. No Java/Maven processes remain. All project and .work/A07 writes are paused until explicit coordinator resume. This is a resource pause, not task completion. `.work/A07/HANDOFF.md` contains exact artifacts, commands, profile order, current gaps and next general experiment.

Remaining: continue general BM25 length/saturation experiments and quality improvements after explicit resume, freeze final selection before held-out; run final2k/20k script demonstrations; prepare100k documents using exact final artifacts/settings then obtain coordinator quiet grant for three30RPS reference measurements under declared2GiB heap. No measured100k performance run has occurred. Current harness measures shared application search (leases, ACL, counts, facets, explanations/cache), excludes HTTP transport and cannot substantiate HTTP end-to-end latency. Script postprocessing asserts document/run/request/heap/error/p95 boundaries and preserves failures; its synthetic unit fixtures are not performance evidence. Final source/jar/report/environment identity must accompany every final claim.
