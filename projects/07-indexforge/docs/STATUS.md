# Validation status

## Current maintenance verification — 2026-10-20

The project-scoped integration build passed with **169 tests: 165 unit/property methods and 4 integration methods, zero failures, errors, or skips**. The same invocation checked formatting, produced current packaged CLI and Boot artifacts, and passed the unchanged coverage gates.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/07-indexforge/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration verify
```

The integration checks include actual signed HTTP/JWKS requests, the packaged CLI/Boot structural-index migration path, and a real Linux `/dev/full` storage failure. The [maintenance evidence](../evidence/maintenance-20261020.json) records report counts, digests, and changed source inputs.

New regressions cover enum constant-specific overrides, code-point tokenization with bounded UTF-16 identifiers, supplementary declaration hints, and cold/warmed Unicode queries at the optional usage lane's 128-unit bound. No ranking experiment, label change, dependency change, or threshold reduction was part of this maintenance.

The supported `up → demo → down` lifecycle also passed with 2,000 documents, 439,546 UTF-8 source bytes, three tenants, and all demo assertions satisfied. This was a functional smoke check, not a performance measurement.

IndexForge has no browser UI. Its interactive surfaces are the CLI and JSON HTTP API; this verification does not claim browser UX testing.

## Historical measurements

| Evidence | Result and scope |
| --- | --- |
| Build-25 certification | 88 tests; 524 / 628 strict mutation kills, with 100 survivors, 1 timeout, and 3 uncovered outcomes retained. This applies to that historical artifact. |
| First final relevance study | Candidate nDCG@10 0.6267961004 / identifier MRR@10 0.8000000000, versus baseline 0.6012721858 / 0.8833333333. Quality guard failed; baseline retained. |
| Later fixed candidate C1 | nDCG@10 0.6594266978 / identifier MRR@10 0.8000000000. Quality guard failed; baseline retained. |
| Frozen build-24 baseline performance | 100,000 documents, three 900-request runs at 30 offered requests/s, zero errors; p95 4.727653 / 6.977346 / 2.607306 ms with 2 GiB heap. Application boundary excludes HTTP. |

The 0.75 nDCG / 0.90 identifier-MRR target remains unmet. Original fixtures, protocols, failed attempts, seals, and result envelopes remain under `fixtures/` and `evidence/`. Repeated exposure of the authored held-out corpus is disclosed; it is not a fresh blind test.

## Remaining work

- Run the expanded mutation profile against the current maintenance source before making a new mutation-strength claim.
- Evaluate future relevance changes through a separately declared development/validation protocol; preserve the baseline guard and existing failures.
- Rebuild the matching catalog and explicitly reindex structural generations when parser identity changes.
- Rerun performance on a quiet reference host before extending historical measurements to current source or HTTP transport.
- Complete repository-wide release and hosted CI checks separately; project verification is not a release certification.
