# Validation status

## Query explanation and positioned syntax errors — 2026-10-29

`stacks-cli explain-query '<text>'` and `POST /v1/query/explain` show how the query parser reads a query: the fully parenthesised form, a note when adjacent terms were combined with an implicit OR, or the exact character offset and a fixed sentence for a syntax error. `search` rejections now carry the same offset. The HTTP route is admitted exactly like `/v1/search` (bearer token with the search scope, no ambient cookies, the strict JSON policy) and, because it only parses, touches no index, tenant or permission state. The response contains only fixed sentences and an offset, never an exception message. Parser behaviour is unchanged: `QuerySyntaxException` extends `IllegalArgumentException` and the accepted language and limits are the same.

Executed against this source with `-Pintegration verify` (JDK 25.0.4.1): **256 ordinary and 12 integration test cases, zero failures, errors or skips**, and the repository's test checker accepted the fresh reports. New cases: seven in `QueryExplainTest` (including 3,000 generated queries whose canonical form re-parses to an equal tree and is a fixed point), one CLI case and one signed-HTTP integration case. The first run of the HTTP case failed because my expected offset was a miscount (19 for an 11-character query); the product value, the end of the text, was correct and the test was corrected.

Not done: the query parser's compiled code changed, so the earlier ranking, relevance and performance receipts below do not describe this artifact; none were rerun. `QueryAst`, `QueryExplainer` and `QuerySyntaxException` were never in the query module's mutation scope (it targets cursors, ranking, snippets and request identity), so the strict-kill table below is unaffected and says nothing about the new code; no mutation campaign was run. No hosted-CI result is claimed.

## Current authenticated tenant verification — 2026-10-21

The full Java 25 reactor passed **259 cases: 248 ordinary cases and 11 integration cases, with zero failures, errors or skips**. Formatting, the unchanged core coverage gates, real HTTP/OIDC/JWKS requests, Linux `/dev/full` handling and packaged CLI integration all passed. The current-report completeness checker independently confirmed every selected module's fresh reports. [The tenant-binding receipt](../evidence/tenant-binding-20261021/validation.json) records the exact command, report hashes and a complete source manifest.

Search and suggestions now carry their admitted principal and tenant through every permission-refresh attempt. Current repository grants still come from the server directory. Moving a subject to another tenant rejects the in-flight request with `403`; the old token cannot read that tenant through a fresh result, cache hit or suggestion retry. The existing principal-based Java APIs remain available and capture their tenant at the start of each call.

Eleven new cases cover the controller admission gap, reassignment before engine admission, current versus captured grants, final checks for fresh and cached responses, same-tenant permission refresh, and both signed HTTP routes during a controlled reader-acquisition race. The HTTP regressions populate both tenant indexes and verify that a fresh matching token can still read the reassigned tenant. They require no new production test hooks.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/07-stacks/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration verify
```

The earlier ingestion-lifecycle receipt below is preserved unchanged as evidence for its recorded source. This tenant-binding run did not repeat mutation campaigns, demonstration scripts, relevance studies or performance measurements; those earlier outcomes do not certify the changed artifact. Hosted CI and repository release certification remain separate checks.

## Earlier HTTP lifecycle verification — 2026-10-21

The seven-entry Java 25 reactor passed **248 cases: 239 ordinary cases and 9 integration cases, with zero failures, errors or skips**. The command ran formatting, the unchanged core coverage gates, packaged-runtime tests, real Linux `/dev/full` handling, and real HTTP/OIDC/JWKS flows. The current-report completeness checker also passed for every selected module. [The execution receipt](../evidence/ingestion-lifecycle-20261021.json) records source and report hashes, module totals, the exact command and the earlier failed attempts.

The HTTP ingestion scheduler now owns running cancellation until actual worker exit, removes cancelled queued tasks immediately, keeps active work separate from a bounded terminal-status cache, serializes shutdown against admission, and allows retries from immutable input after the original checkout disappears. `CANCELLING` is an additive in-progress state; durable `COMPLETE` remains authoritative. Cooperative cancellation does not inflate failure metrics. Seventeen task-lifecycle tests cover bounded admission, duplicate/race schedules, failure recovery, tenant metrics and shutdown boundaries; five new actual HTTP cases verify queue capacity, input recovery, grant revocation and stopping behavior.

Typed request conversion rejects duplicate keys, trailing values, unknown fields and scalar coercion before source acquisition or job registration. Deletion paths are validated and copied at admission. Nine JSON regressions include empty-string Boolean conversion, a defect caught by the first run. The full integration run also exposed a prior CSRF configuration gap: Spring's resource-server bearer exemption bypassed the intended cookie matcher. Installing the matcher on the finished filter and writing a direct redacted 403 denial now passes the existing real cookie-plus-bearer assertion; four additional credential-boundary tests cover raw Cookie headers, safe methods and browser-marked Basic credentials.

This receipt remains functional evidence for its measured source; the newer tenant-binding source is covered by the receipt above. No fresh mutation campaign, relevance study, performance study, hosted-CI success or repository release certificate is claimed. The existing relevance failures, exposed-heldout limitation, and historical benchmark records below remain unchanged.

## Previous source verification — 2026-10-20

The complete seven-entry reactor passed **198 cases: 194 unit/property cases and 4 integration cases, with zero failures, errors or skips**. The invocation checked formatting, passed the unchanged coverage gates and installed the current CLI and Boot artifacts. The source review covered all 87 production classes in ingest, index, query, service, evaluation and CLI.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/07-stacks/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration install
```

The integration checks exercise signed HTTP/JWKS requests, the packaged structural-index migration path and a real Linux `/dev/full` storage failure. Deterministic regressions cover publication/recovery failure, multi-tenant lease admission and shutdown, interrupted staging disposal, malformed archives, Unicode term/snippet bounds, renewed cached cursors, tenant metrics and offline data/artifact isolation. The [verification receipt](../evidence/maintenance-deep-20261020.json) records module counts, report and artifact digests, source inputs, command scopes and earlier failed attempts. The [review record](REVIEW.md) describes the corrected defects and regression boundaries.

## Mutation verification

The selected modules use the unchanged 70% **strict kill** gate. Strict rates count only `KILLED` as success and keep timeouts and uncovered mutations in the denominator; nonviable mutants are excluded. Run errors, memory errors and unknown outcomes fail validation. The [verification receipt](../evidence/maintenance-deep-20261020.json) records the completed module outcomes, source bindings and command scopes; all four selected modules passed the strict gate without runner errors.

| Completed source-bound scope | Strict kills / viable mutants | Strict rate | Other outcomes |
| --- | ---: | ---: | --- |
| Ingest | 111 / 136 | 81.62% | 25 survived |
| Index | 910 / 1,232 | 73.86% | 245 survived, 75 uncovered, 2 timed out |
| Evaluation | 417 / 533 | 78.24% | 111 survived, 5 uncovered |
| Query | 463 / 549 | 84.34% | 63 survived, 14 uncovered, 9 timed out |

These are separate command scopes. Ingest and index completed in an earlier combined integration/mutation invocation before query stopped on an obsolete whole-response assertion. Their production sources, tests and POM hashes remain unchanged. Evaluation completed in a later query/evaluation reactor. That reactor's query result was correctly rejected by the strict completion gate: an increment mutation exhausted the heap while filling a growable list of candidate ordinals. The failed report remains preserved.

Scoring now allocates its ordinal array to the known candidate count, bounded by 500, and preserves document-order scoring and original candidate ownership. The existing independent three-leaf, unordered-candidate regression exercises those semantics. The final source passed a fresh full query campaign with every declared target and no exclusions:

```bash
./mvnw -f projects/07-stacks/pom.xml -pl query \
  -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp -Pmutation verify
```

The original combined reactor is recorded as failed; no successful combined mutation run is claimed. The cursor assertion now compares every immutable response field and independently decoded continuation semantics under a fixed clock; separate tests still reject expired tokens. The receipt also retains the earlier invalid-disk-tenant exception mismatch and JAR-versus-exploded fingerprint-fixture failure, both corrected before the final integration pass. Mutation coverage applies to the explicit classes in each module POM, not every adapter or branch in the repository.

## Packaged workflows and compatibility

The previously recorded `up → demo → down` scripts passed with 2,000 documents, 439,546 UTF-8 source bytes, three tenants and all transactional/restart/isolation assertions satisfied. The [HTTP example](../demo/http_workflow.py) passed actual ingestion, bounded polling, two signed one-hit pages, suggestions, completed-job resume, reindex and tenant metrics. Its service was stopped afterward. These are functional checks of that earlier source, not performance measurements.

Existing manifest, job, generation and cursor request-frame formats remain compatible. Extracting parser responsibilities changes the parser-bundle identity: structural strategies require a newly prepared matching API catalog and explicit reindexing. Configuring new metadata over existing structural facts is rejected.

Stacks has no browser UI. Its user-facing surfaces are the CLI and JSON HTTP API; this verification does not claim browser UX testing.

## Historical measurements

| Evidence | Result and scope |
| --- | --- |
| Build-25 certification | 88 tests; 524 / 628 strict mutation kills, with 100 survivors, 1 timeout and 3 uncovered outcomes retained. This applies to that historical artifact. |
| First final relevance study | Candidate nDCG@10 0.6267961004 / identifier MRR@10 0.8000000000, versus baseline 0.6012721858 / 0.8833333333. Quality guard failed; baseline retained. |
| Later fixed candidate C1 | nDCG@10 0.6594266978 / identifier MRR@10 0.8000000000. Quality guard failed; baseline retained. |
| Frozen build-24 baseline performance | 100,000 documents, three 900-request runs at 30 offered requests/s, zero errors; p95 4.727653 / 6.977346 / 2.607306 ms with 2 GiB heap. Application boundary excludes HTTP. |

The 0.75 nDCG / 0.90 identifier-MRR target remains unmet. Original fixtures, protocols, failed attempts, seals and result envelopes remain under `fixtures/` and `evidence/`. Repeated exposure of the authored held-out corpus is disclosed; it is not a fresh blind test. No new ranking experiment, held-out study or performance benchmark was run. Training within correctness tests uses synthetic fixtures.

Future relevance work needs a separately declared development/validation protocol and the existing baseline guard. Extending historical performance claims to current source or HTTP requires a quiet reference-host measurement. CAS/job retention, local filesystem assumptions, conservative source binding and tenant-wide BM25 statistical coupling remain documented limits. Repository-wide release and hosted CI checks are separate from this project verification.
