# IndexForge status: frozen24 evidence checkpoint

Owner A07. Implementation and correctness certification are complete for this snapshot; full project acceptance is not. P07-A4 failed the first frozen held-out evaluation. P07-A5 reference measurement and repository clean release remain pending. No source/configuration was tuned after held-out results.

## Exact source and executable

Build24 completed2026-10-18T04:10:12Z:65 tests, zero failures/errors/skips; selected PIT98/98 KILLED, no MEMORY_ERROR/survivors/no-coverage mutants. EffectiveJUnit6.1.3. Core ingest/index/query line/branch coverage gates85/75 pass. Exact counters and report hashes: `evidence/certification-build24.json`; raw `.work/A07/verify-twentyfourth.log`; copied XML/HTML/Surefire/Failsafe `.work/A07/artifacts/build24-reports/`.

Source fingerprint `a94bed568597a6746b3d01bee127e3157a2f2c34255b3d6d867a321ee765f0de`. Copied CLI `.work/A07/artifacts/build24-cli.jar`, SHA `6c2ae0c414013a55e8d83bcfd9e65707ccfb984fa684054bf83910f08c4d564d`. Frozen CODE_HIGH model `cbf17ef0b814d9b56db61ed33bd8d72d1855be7ceb6eef8a10b5a5597ebd6997`. Java/POM/scripts froze at queue submission and remain unchanged. Documentation/evidence changes are outside the source fingerprint.

Executed verification:

```
source scripts/cloud-env.sh
./mvnw -f projects/07-indexforge/pom.xml -Dmaven.repo.local=/workspace/Java-portfolio/.work/A07/m2 -B -ntp com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration,mutation verify
```

## Implemented and verified boundaries

Six modules deliver bounded UTF8/directory/ZIP ingestion, CAS/manifests/idempotent jobs/checkpoints/resume; committed checksummed atomic generations/recovery/leases/retirement; original identifier analysis/bounded grammar/planning/reranking/cursors; Boot authenticated HTTP with strict original signed JWT claim-type checks/raw admission/bounded jobs; CLI, original metrics, deterministic workload and application-boundary benchmark.

P07-A1:10,000-record idempotency and explicit deletion regressions pass. P07-A2:100 seeded failure schedules plus ambiguous-publication live/restart A→B→resumeA regressions pass. P07-A3:tenant/repository filtering covers results/counts/facets/suggestions/snippets/explanations/cursors/warmed caches and final permission-version recheck. P07-A6:malformed UTF8/oversize/archive/path/symlink/cancellation/staging and kernel /dev/full prepublication failures are explicit; /dev/full is a real write failure, not a filled index-volume test. Residual tenant-reader BM25 numeric score/order statistical coupling is documented; ACL statistical noninterference is not claimed.

Independent A06 reviewed17→24 and closed durable job replay/receipt corruption, stable root-relative paths, raw explanation-stat disclosure and the latest filter-context collision. Corrected24 uses versioned length-prefixed UTF16 request/filter tuples, with direct warm-cache/cursor/reorder/restart/revocation regression. REVIEW records exact source/artifact and the review's exclusion of quality/performance/clean release. A07→A08 review findings and bounded closure remain recorded separately.

## First frozen held-out result: P07-A4 FAIL

V1 smoke and reviewedV2/correction audit are retained; independently approvedV3 has70 substantive authored documents and80 graded queries40development/20validation/20held-out. Corpus SHA `0162c35bd70d995e2ab0feb14ccf96c7cec76666338b54a6d4a8689efd786ed9`; judgments SHA `77a370782ec74c14036ad641f727fb41283e9baceb85ee2fdf2671a3d750b7ec`. No labels changed after metrics. Unjudged documents have zero pooled gain, not universal irrelevance; authored-corpus results are not an external benchmark.

All unsuccessful rounds and raw rankings remain under `evidence/relevance/`. Prespecified CODE_HIGH/SOURCE_AWARE won the four-profile validation objective (.5804173420 nDCG/.7833333333 identifierMRR versus baseline .4369789084/.75). Corrected24 replayed every540 development/validation metric/path row exactly equal22 with the same semantic selection; only the executable/model identity changed. `code-build24/code_high/attempt-1/revalidation.json` records the comparison.

Root conditionally authorized one final run after strict24/exact replay. Frozen selection/final-freeze metadata preceded execution; fixture-global and output-local seals bind that first decision. Final execution completed once with no retuning or defaults changes. Exact freeze/execution/log hashes and all9 modes/per-query failures are under `evidence/relevance/code-build24/`.

| Mode | Held-out nDCG10 | Held-out identifierMRR10 | Recall20 |
| --- | ---: | ---: | ---: |
| BASELINE | .6012721858322372 | .8833333333333332 | .6833333333333333 |
| SOURCE_AWARE selected candidate | .6267961004421798 | .8 | .7833333333333334 |
| NO_IDENTIFIERS | .6672467584649933 | .9 | .7916666666666667 |
| NO_PATH | .6405486272650978 | .8 | .7833333333333334 |
| NO_PROSE | .6044552464314228 | .7833333333333333 | .7166666666666666 |
| NO_CONCEPTS | .6371059206970471 | .8 | .7833333333333334 |
| NO_COVERAGE | .6267961004421798 | .8 | .7833333333333334 |
| NO_SIMILARITY | .6237107028295322 | .8 | .7583333333333334 |
| NO_CODE_FEATURES | .6427902339418031 | .8 | .7833333333333334 |

No mode meets both .75/.90 targets. The frozen adoption guard retains BASELINE because the selected candidate regresses identifierMRR. Diagnostic ablations are not substituted after seeing held-out. Compiled defaults remain BASELINE/CURRENT. The reference explicitly uses effective BASELINE with frozen CODE_HIGH profile; baseline bypasses feature weights and uses default content BM25, keeping the sealed artifact/profile identity. The original first-run seals/results remain immutable history. Future explicitly preregistered versions may be studied with all comparisons/repeated-test limitations disclosed; the master brief does not impose a permanent one-model-ever prohibition. No further held-out per-query inspection, tuning or metrics occurs in the current pause.

## Final demonstrations and performance preparation

Actual final24 reset/up/2k/20k/down/reset passed; up created absent local data, both demos exercised identifier/prose retrieval, concurrent replacement/deletion, denied repository, interrupted build and restart. Seed7007;2k=439546 source bytes,20k=4435559 bytes; three tenants. `evidence/demonstrations/build24-final-scripts.json` binds exact source/JAR/scripts/config/logs. These functional demonstrations used SOURCE_AWARE/CODE_HIGH; they do not override the baseline deployment recommendation. The tested-source marker was written only after strict24 and current source/target-JAR equality checks; verify.sh itself was not rerun.

100k index reuse is explicitly audited, not stale identity stamping: only SearchEngine.class differs23→24; every other packaged indexer/dependency/resource entry is byte-identical. Prior three CURRENT hashes remain unchanged. Fresh24 runtime verified checksums/counts33334/33333/33333, actual BASELINE/CODE_HIGH and bm25-content-v1 scoringVersion,2GiB cap, model identity. Source bytes22266651,seed7007,measuredRuns=0. `evidence/performance/index-reuse-build23-24.json` and `preparation-build24.json` bind the audit, prior preparation, fresh runtime/log/helper/new artifact/source. Original23preparation remains unchanged.

No-Java preflight24 passed. Actual quiet reference is NOT_RUN. Coordinator after actual lock/process/stack isolation can execute `INDEXFORGE_QUIET_GRANT=window-id python .work/A07/run-reference-build24.py`. It uses copied24JAR/JDK25, effectiveBASELINE/CODE_HIGH,-Xmx2g,three30second runs at30scheduledRPS, fresh attempt directories and workload/errors/p95 assertions. Raw latency/GC/CPU/allocation/queue/environment/argv evidence is preserved on success or failure. Boundary excludes HTTP transport; no HTTP end-to-end latency claim. Repository fresh-checkout release is coordinator-owned and pending.

All A07 Java/Maven/evaluation/preparation processes are stopped. All owned paths pause after `.work/A07/HANDOFF.md` pending explicit root reactivation. Next work may design development/validation-only generic improvements during the long quiet interval; no new code/build/metrics starts during it.
