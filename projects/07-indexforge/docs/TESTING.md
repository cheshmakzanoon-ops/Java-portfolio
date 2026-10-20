# Testing

Run commands from the repository root with JDK 25. In the managed workspace, source `scripts/cloud-env.sh` first so the wrapper uses the configured proxy and system CA trust.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/07-indexforge/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp verify
./mvnw -f projects/07-indexforge/pom.xml \
  -Dmaven.repo.local="$PWD/.work/A07/m2" -B -ntp -Pintegration verify
```

`verify` runs unit, property, format, and coverage checks. The integration profile adds actual Boot HTTP, local OIDC/JWKS, and packaged runtime tests through Failsafe; no Docker daemon is required. The mutation profile targets selected original algorithms with nonzero-report and 70% gates. Core coverage thresholds are 85% lines and 75% branches. Consult each module POM for the exact selected classes; adapters are not evidence of algorithm coverage.

## Regression boundaries

| Area | Behaviors exercised |
| --- | --- |
| Acquisition | Invalid UTF-8, parent and symlink escape, directory traversal limits, malformed ZIP containers, expansion bounds, cancellation, corrupt content and manifests. |
| Generations | Explicit deletions, idempotent retries, old leases during publication, corrupt-pointer fallback, ambiguous publication reconciliation, and interrupted builds. |
| Authorization | Denied repositories and foreign tenants across results, counts, facets, suggestions, explanations, cursors, warmed caches, and revocation. |
| HTTP | RSA/JWKS validation, issuer/audience/time claims, original claim types, write scope, request-size limits, and source-root binding. |
| Source analysis | Parser process limits, exact framing, watchdog cleanup, uncertain bindings, enum overrides, supplementary identifiers, and wire quotas. |
| Evaluation | Independent metric calculations, cutoff denominators, grouped development folds, finite selection, and artifact/model identity. |

The 100 seeded failure schedules inject bounded faults; they are not 100 physical power cuts. The Linux `/dev/full` integration fixture produces a real `ENOSPC` at a pre-pointer boundary without filling the index volume. Both checks require the previous committed generation to remain searchable.

Frozen relevance fixtures contain 80 queries split into development, validation, and held-out sets. They must not be relabeled in response to results. Existing held-out measurements have failed the declared targets; repeating that corpus is not a new blind test. Correctness regressions use synthetic fixtures and do not run a relevance study.

[Validation status](STATUS.md) records executed commands and outcomes. Historical result files certify their original artifact, not later source changes.
