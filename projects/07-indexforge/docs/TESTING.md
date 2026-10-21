# Testing

Run commands from the repository root with JDK 25. In the managed workspace, source `scripts/cloud-env.sh` first so the wrapper uses the configured proxy and system CA trust.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/07-indexforge/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp verify
./mvnw -f projects/07-indexforge/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp -Pintegration verify
```

`verify` runs unit, property, format, and coverage checks. The integration profile adds actual Boot HTTP, local OIDC/JWKS, and packaged runtime tests through Failsafe; no Docker daemon is required. The mutation profile targets selected original algorithms and extracted source/binding/ingestion/study boundaries with nonzero-report and 70% gates. Core coverage thresholds are 85% lines and 75% branches. Consult each module POM for the exact selected classes; adapters are not evidence of algorithm coverage.

## Regression boundaries

| Area | Behaviors exercised |
| --- | --- |
| Acquisition | Invalid UTF-8, parent and symlink escape, directory traversal limits, malformed ZIP containers, expansion bounds, cancellation, corrupt content and manifests, inconsistent ZIP local/central identity and payload-bearing directory entries. |
| Generations | Explicit deletions, idempotent retries, old leases during publication, corrupt-pointer fallback, ambiguous publication reconciliation, interrupted-build cleanup, failed-first-publication cleanup, fallback repair failure, writer shutdown and atomic multi-publisher close rejection. |
| Authorization | Denied repositories and foreign tenants across results, counts, facets, suggestions, explanations, cursors, warmed caches, virtual-clock cursor renewal/expiry, revocation and tenant-scoped administrator metrics. |
| Feature extraction | Sparse scorers across three committed Lucene leaves, reversed candidate order, missing vectors, original candidate/score ownership and bounded ordinal storage. |
| HTTP | RSA/JWKS validation, issuer/audience/time claims, original claim types, write scope, request-size limits, source-root binding, typed JSON/duplicate/trailing-value rejection, and actual cookie-plus-bearer CSRF enforcement. |
| Background ingestion | Duplicate submission, running cancellation/resume ownership, immediate queued cancellation capacity, old-runnable pickup races, tenant metrics, retention bounds, interrupted/deadline shutdown, missing-CAS repair, persisted-input retry after source deletion, revoked queued grants, and `503` after shutdown admission closes. |
| Source analysis | Parser process limits, exact framing, watchdog cleanup, uncertain bindings, enum overrides, supplementary identifiers, and wire quotas. |
| Evaluation | Independent metric calculations, cutoff denominators, grouped development folds, finite selection, complete class-origin identity with isolated helper-byte changes, split-before-parse poison-heldout fixtures and UTF-8 packet bounds. |

`IngestionTasksTest` uses latch-controlled workers to observe cancellation before actual termination; it does not infer termination from a Future's cancellation flag. It also races worker pickup and cancellation, and verifies that no two attempts for one registration execute simultaneously. `HttpIngestionLifecycleIT` holds the real tenant writer while filling the HTTP queue, then cancels and resumes work through signed requests. These checks exercise the service boundary and durable registration rather than only a mock executor.

The executable [HTTP example](../demo/http_workflow.py) additionally exercises actual ingestion/job polling, signed pagination, suggestions, resume, reindex and tenant metrics without displaying credentials. CLI malformed-command regressions verify that failures precede data creation.

The 100 seeded failure schedules inject bounded faults; they are not 100 physical power cuts. The Linux `/dev/full` integration fixture produces a real `ENOSPC` at a pre-pointer boundary without filling the index volume. Both checks require the previous committed generation to remain searchable.

Frozen relevance fixtures contain 80 queries split into development, validation, and held-out sets. They must not be relabeled in response to results. Existing held-out measurements have failed the declared targets; repeating that corpus is not a new blind test. Correctness regressions use synthetic fixtures and do not run a relevance study.

[Validation status](STATUS.md) records executed commands and outcomes. Historical result files certify their original artifact, not later source changes.
