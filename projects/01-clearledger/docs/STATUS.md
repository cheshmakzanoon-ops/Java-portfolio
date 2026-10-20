# Verification status

ClearLedger implements atomic posting, tenant-scoped idempotency, full reversals, period closure, statement reconciliation, and reporting replay. The full performance study and clean-checkout release validation remain incomplete. The project makes no production-capacity or regulatory claim.

## Current maintenance verification

On 2026-10-20, project-scoped `spotless:apply -Pintegration verify` passed with **44 test methods: 27 ordinary and 17 PostgreSQL, zero failures, errors, or skips**. The additional regressions cover malformed UTF-8 imports, NUL-containing imports, unchanged Unicode reconciliation, rejection without partial imports, and preservation of the original transaction exception when rollback also fails.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/01-clearledger/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A01/m2 -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply -Pintegration verify
```

This result verifies the current ordinary and PostgreSQL integration suites. Historical mutation results below apply to their recorded source manifests; mutation analysis was not rerun for this maintenance change.

## Historical integration and mutation evidence

| Scope | Observed outcome | Evidence |
| --- | --- | --- |
| Retry-policy integration and mutation verification, 2026-10-18 | 39 methods: 23 ordinary and 16 PostgreSQL; no failures, errors, or skips. Domain mutations: 30/31 killed; retry-policy mutations: 12/12 killed. | `evidence/retry-jitter-certification.json` |
| Source audit, signed HTTP identities, and PostgreSQL restore | 25 methods, including 15 PostgreSQL methods, plus 500 generated property cases. Core coverage: 76/79 lines and 51/56 branches. | `evidence/integration-current.json` |
| Selected domain mutation analysis | 30/31 killed; the length-64 boundary survivor remains visible and in the denominator. No uncovered mutants, runner errors, or timeouts. | `evidence/mutation-current.json` |
| Repeatable lifecycle and deterministic demo | Two demos passed 9/9 HTTP, SQL, and process assertions, including recovery of the original committed receipt. | `evidence/demo-current.json` |
| Functional workload smoke | 1,000 input groups at 1, 8, and 32 writers; expected balances, complete source audit, SQL cardinalities, and shadow replay passed. | `evidence/system-smoke-current.json` |

The functional smoke used 20 tenants and 1,060 of 10,000 seeded accounts. Each writer-count run committed 1,001 journals, receipts, and outbox events. Transaction retries were 0, 406, and 1,552; the 32-writer run required 24 additional client calls and exhausted no client budgets. These are functional observations on a shared host, not capacity measurements.

## Acceptance scope

| Acceptance | Historical demonstrated scope | Remaining work |
| --- | --- | --- |
| P01-A1 | Exact money/reference properties, invalid SQL inputs, posting permissions, bounded balances, reversal and races | Current integration suite passed; clean-checkout release reproduction remains |
| P01-A2 | Duplicate submissions, period/reversal races, tenant isolation, two RSA/OIDC API instances, strict signed-claim types, HTTP process death and retained receipt recovery | Clean-checkout release reproduction |
| P01-A3 | Matched, unmatched, ambiguous, duplicate, and invalid rows; duplicate-file identity; manual decisions; bounded formula-safe CSV | Current encoding and Unicode integration regressions passed |
| P01-A4 | Late event commit, redelivery, shadow promotion, backup restoration with original ACLs, source audit and isolated corruption diagnostics | Clean-checkout release reproduction |
| P01-A5 | Working-checkout demo and bounded profiled workload | Full reference study and release validation |

## Failed measurements and their limits

The full reference attempt ending at 2026-10-18T09:13:34Z failed after 1,513.716 seconds. The 100,000-input trials with 1 and 8 writers passed; the 32-writer trial completed 99,998 input groups and failed two. One six-call client budget was exhausted, with 2,506 additional calls and 2,508 temporary responses. PostgreSQL reported a serialization pivot at receipt insertion; this does not identify the complete SSI dependency cycle. Six later trials did not run. Teardown passed and a separate final-copy disk guard failed.

The failed attempt remains identified in [performance](PERFORMANCE.md). Its preserved source, runtime, raw data, and database dumps document the failure; preservation does not convert it into acceptance. An earlier smoke failure is recorded separately in `evidence/system-smoke-first-failed.json`.

The subsequent retry policy samples independent exponential windows of 4, 8, 16, 32, 64, 128, and 256 ms, capped at 256 ms. Eleven possible retry sleeps request at most 1,532 ms in total; database execution, lock waits, and scheduling can make wall time longer. Connections close before backoff. The database budget remains 12 attempts; clients allow at most six temporary-only calls with the same key and intent. Functional regressions passed, including a synchronized 32-writer case; no completed full study establishes the policy's capacity or improvement at 100,000 inputs.

## Outstanding release checks

Run the complete 100,000-journal workload at 1, 8, and 32 writers for three independent rounds on an idle reference host, followed by its validators. Preserve the prescribed seed, 20 tenants, 10,000 accounts, currency mix, duplicates, reversals, latency boundary, and failed outcomes. JMH requires three forks, three one-second warmups, and five one-second measurements per case with GC profiling. Prepared runtime receipts prevent Maven work from entering the measured interval.

Clean-checkout lifecycle reproduction, current-source mutation verification, and repository-wide release checks remain separate obligations. Cached artifacts do not authorize skipping correctness tests. See [operations](OPERATIONS.md), [performance](PERFORMANCE.md), and [review](REVIEW.md) for the commands and boundaries.
