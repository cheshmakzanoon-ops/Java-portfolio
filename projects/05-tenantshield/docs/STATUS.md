# Verification status

TenantShield has executable coverage for policy semantics, OIDC identity validation, tenant isolation, revocation, immutable publication, and mandatory audit. Results below distinguish current hardening from historical correctness and measurement evidence; historical source fingerprints do not certify subsequent code changes.

## Current hardening: 2026-10-20

Ordinary Maven verification passed **21 test methods with zero failures, errors, or skips** on JDK 25.0.4.1 and Maven 3.10.0. The six-module build includes 12 policy-core tests, eight decision-service tests, and one protected-service claim test. Policy-core coverage thresholds and format checks passed.

The current changes enforce a single strong `If-Match` version for publication and rollback, validate malformed preconditions before database access, and reject ambiguous JSON with duplicate keys or trailing documents. Three new controller test methods cover malformed and overflow preconditions, exact boundary values, and immutable rollback. The Spring mapper test loads actual application properties and checks duplicate keys and trailing input alongside prior coercion and unknown-field cases.

Full integration verification passed on 2026-10-20: **31 methods (21 ordinary and 10 PostgreSQL/Keycloak integration), with zero failures, errors, or skips**. The additional integration method exercises those rejections over authenticated HTTP against the actual service. The historical results below remain separate.

Reproduce from the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/05-tenantshield/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A05/m2 -B -ntp verify
./mvnw -f projects/05-tenantshield/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A05/m2 -B -ntp -Pintegration verify
```

## Historical correctness and lifecycle

| Scope | Recorded result | Evidence |
| --- | --- | --- |
| Parser, compiler and evaluator | 2,500 generated comparisons with an independent interpreter; original core line/branch gates passed | `evidence/correctness.json`, `evidence/correctness-methods.json` |
| OIDC, tenant isolation and revocation | 18 unit plus nine PostgreSQL/Keycloak methods passed under JUnit 6.1.3 with no failures, errors or skips | `evidence/correctness.json` |
| Selected mutation analysis | 58/58 top-level `PolicyEngine` mutants killed; parser and adapters were outside this selection | `evidence/mutation.json`, `evidence/mutations.xml` |
| Protected demo | Two 21-assertion lifecycle runs; fixture reset preserved workload tenants | `evidence/demo.json`, `evidence/preparation.json` |
| Dependency refusal | Protected response changed 403 → 503 → 403 when the project's database was paused and recovered | `evidence/protected-outage.json` |
| Runtime integrity | Source/JAR binding, packaged-byte extraction, eight tamper probes and JMH discovery passed | `evidence/cache-preparation.json`, `evidence/jmh-fix-verification.json` |

The database concurrency suite exposed an audit foreign-key conflict with `FOR UPDATE`. The retained regression and repair use `FOR NO KEY UPDATE` while preserving writer serialization, repeatable-read semantics, and mandatory audit. See [architecture](ARCHITECTURE.md) and [case study](CASE_STUDY.md).

The earlier executable fingerprint `832dfc3ccd0bf9399528721f8a31710c4b0197129b0b183aadcd5a1afeb92c11` identifies the completed 2026-10-18 reference study. Later hardening requires fresh build and test evidence; the earlier artifact receipts remain historical.

## Historical reference study

The study completed both three-fork JMH methods and three authenticated 100,000-request HTTP runs. Every HTTP run recorded 20,000 denials, zero errors, 700,150 application prepared statements, 50 cached policies, and maximum client concurrency 16. All repetitions remain visible.

Throughput declined **593.275 → 504.958 → 367.263 requests/s**, a 38.10% first-to-third decline with sample CV 23.32%. Per-run p99 values were 133.285, 121.010, and 176.966 ms. Clients were fresh processes, but the database, service, cache, and audit history continued between runs. Stable capacity and the cause of the slowdown are unestablished.

The first reference attempt failed before JMH because extraction omitted archive-root benchmark metadata. Its failure remains in `evidence/reference-attempt-5-failed.json`; the later metadata repair passed discovery and integrity checks before the successful study. The [measurement packet](../evidence/reference-20261018/README.md), [performance report](PERFORMANCE.md), and [scoped review](REVIEW.md) retain identities, all runs, and measurement limits.

## Open release work

Clean-checkout reproduction, repository-wide release checks, and hosted CI must be assessed separately from these results. Neither a successful local build nor the historical study establishes a production security certification, service-level objective, or complete repository release.
