# Verification status

Gatehouse has executable coverage for policy semantics, OIDC identity validation, tenant isolation, revocation, immutable publication, and mandatory audit. Results below distinguish current hardening from historical correctness and measurement evidence; historical source fingerprints do not certify subsequent code changes.

## Current CSRF enforcement, 2026-10-21

The complete ordinary reactor passed **49 methods: 19 policy-core, 26 decision-service and four protected-service**, with zero failures, errors or skips. Fresh-report completeness checks passed. Six new configured-chain regressions cover bearer plus cookie, raw Cookie headers, mutation and safe methods, browser Basic credentials, valid/invalid CSRF tokens, and retained authentication requirements.

The decision-service now applies its ambient-credential matcher after resource-server setup and writes a bounded HTTP 403 problem response directly. The [CSRF verification receipt](../../../docs/execution/csrf-enforcement-20261021.json) records failing counterexamples, changed source hashes and passing report hashes. The new filter-chain fixtures isolate JWT decoding; existing ordinary signed-token tests also ran. PostgreSQL/Keycloak integration and mutation profiles were not rerun for this change. The demonstration document service remains a GET-only resource and its security configuration is unchanged.

## Historical verification: 2026-10-20

The combined ordinary, integration and mutation workflow passed **56 Java test methods: 41 ordinary and 15 PostgreSQL/Keycloak integration**, with **zero failures, errors or skips**. The ordinary breakdown is 19 policy-core, 18 decision-service and four protected-service methods. The runtime baseline is JDK 25.0.4.1, Maven 3.10.0, PostgreSQL 18.6 Alpine and Keycloak 26.8.1. Both container definitions pin the exact tested digests; shared dependency versions come from the repository parent.

The suite covers compiler-owned immutable plans and scope indexes, an independent interpreter, exact parser/evaluator work budgets, Unicode digest safety, concurrent cache loading, stable quota ownership, bounded typed decision responses, all nine deployed RLS/ownership boundaries, membership enforcement independent of callbacks, transaction setup/commit/rollback faults, and stored policy/attribute/membership corruption with repair. Existing real OIDC, revocation, publication and mandatory-audit regressions remain active.

Broader mutation analysis selected `PolicyEngine*` and `PolicyLanguage*`, including nested types: **271 of 276 mutants killed (98.19%)**, five survived, and none were uncovered, timed out or had runner errors. The 70% gate was retained. Survivors remain visible in parser token/end/depth guards and `Compiled.hashCode`; this is not a claim of complete mutation detection. Core coverage exceeded the unchanged 85% line and 75% branch gates; format checks passed. [Historical verification evidence](../evidence/current-verification.json) retains individual suites, report hashes, coverage counters, mutation survivors and baseline identities.

**Six process/control tests passed with zero failures or skips.** They exercise resistant descendants after normal exit/deadline, terminal and repeated terminal signals, and startup migration/readiness failures removing only newly acquired resources. These use real subprocesses and controlled external adapters; they do not simulate a real Docker daemon outage.

Reproduce from the repository root:

```bash
source scripts/cloud-env.sh
./mvnw -f projects/05-gatehouse/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A05/m2 -B -ntp verify
./mvnw -f projects/05-gatehouse/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A05/m2 -B -ntp -Pintegration,mutation verify
python3 projects/05-gatehouse/scripts/test_process_lifecycle.py -v
```

The supported startup/demo/shutdown lifecycle also passed on a fresh isolated PostgreSQL 18.6 Alpine volume: **21 asserted OIDC/protected-resource checks**, including a fresh allow followed by denial after committed revocation. [Historical demo](../evidence/current-demo.json), [output](../evidence/current-demo-output.txt) and [runtime/encoding](../evidence/current-runtime.json) identify this execution. The historical demo files were restored byte-for-byte; only the disposable certification resources were removed. No non-certification data volume was modified.

The current PostgreSQL suite uses a fresh cluster and exercises UTF-8 supplementary text as part of stored-attribute failure/recovery. It does not certify transplanting the historical Debian-created data volume into Alpine; [operations](OPERATIONS.md) requires export/restore and locale validation. No benchmark was rerun for these changes. Historical measurements below retain their original source and image identities.

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
