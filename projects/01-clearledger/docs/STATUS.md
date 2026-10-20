# Verification status

ClearLedger implements atomic posting, tenant-scoped idempotency, full reversals, period closure, statement reconciliation, and reporting replay. The full performance study and clean-checkout release validation remain incomplete. The project makes no production-capacity or regulatory claim.

## Current verification, 2026-10-20

Project-scoped `spotless:apply -Pintegration,mutation verify` passed at **08:16:26 UTC** on the updated dependency baseline with **67 test methods: 45 ordinary and 22 PostgreSQL, zero failures, errors, or skips**. The property suite also generated 500 accounting examples. [Current evidence](../evidence/engineering-verification-2026-10-20.json) binds these results to root/project POMs and module sources.

```bash
source scripts/cloud-env.sh
export PORTFOLIO_TEST_RUN_STARTED="$(date +%s)"
./mvnw -f projects/01-clearledger/pom.xml \
  -Dmaven.repo.local=/workspace/Java-portfolio/.work/A01/m2 -B -ntp \
  com.diffplug.spotless:spotless-maven-plugin:3.0.0:apply \
  -Pintegration,mutation verify
python3 scripts/check-tests.py projects/01-clearledger integration
python3 scripts/check-mutations.py projects/01-clearledger
```

The PostgreSQL cases include a V2-to-V3 upgrade with retained receipts and grants, malformed privileged function inputs, forced concurrent projection and manual matching interleavings, projection failure after membership insertion, actual signed malformed subjects, and the existing before/after-commit process termination and backup/restore cases. Transaction regressions distinguish acknowledged commit plus cleanup failure from unknown commit outcome. CSV preparation, immutable retry input, exact boundaries, and export column order have ordinary regressions.

| Current mutation scope | Strict killed count | Other outcomes | Required kill rate |
| --- | --- | --- | --- |
| All domain classes | 35/36, 97.22% | One decimal-length boundary survivor | 70% |
| Retry backoff, transaction executor, and statement CSV | 102/105, 97.14% | Two survivors and one timeout | 90% |

The denominator includes survivors and the timeout. The persistence survivors change the first-position NUL error classification and remove a cleanup warning; the quoted-CSV increment mutation times out. None is presented as killed. This is targeted mutation coverage, not mutation analysis of every production class. The expanded persistence gate initially failed at 89/106 killed with one timeout and one uncovered mutant; the final result followed additional boundary, transaction-state, and SQL-state regressions. No threshold or exclusion was relaxed.

The refreshed working-checkout lifecycle also passed: runtime preparation ran six cache regressions, startup became healthy, the live demo passed 9/9 HTTP/SQL/process assertions, and the exact documented POST/repeat/receipt commands passed three checks. Shutdown stopped the owned API and PostgreSQL service. [Demo evidence](../evidence/engineering-demo-2026-10-20.json) retains its executed source manifest; this is not a clean-checkout release run.

## Local lifecycle ownership, 2026-10-20

Sixteen process and control-adapter regressions passed in 69.480 seconds. They exercise actual child processes, exact kernel start identity, complete argument hashes, artifact provenance, restricted procfs executable observations, resistant shutdown, cancellation during shutdown, repeated signals during rollback, preservation of preexisting running and stopped containers, retained volume state, and failures in Compose, readiness, seeding, and cleanup. [Scoped evidence](../evidence/lifecycle-ownership-2026-10-20.json) records the six executable/test source hashes and a separate passing six-case runtime-cache regression run. Compose and HTTP are controlled adapters in this suite; it does not certify the PostgreSQL image or replace the live lifecycle run.

Startup now acquires resources under a lifecycle lock and restores only resources it changed when startup fails. Shutdown waits for the recorded process to exit, escalating through a PID file descriptor when available. A changed JAR cannot silently reuse an older JVM. Explicit image replacement requires stopping and removing the selected stopped container while retaining its volume, as described in [operations](OPERATIONS.md).

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
| P01-A3 | Matched, unmatched, ambiguous, duplicate, and invalid rows; duplicate-file identity; manual decisions; bounded formula-safe CSV | Current encoding, Unicode, immutable-import, and competing-decision regressions passed |
| P01-A4 | Late event commit, redelivery, shadow promotion, backup restoration with original ACLs, source audit and isolated corruption diagnostics | Clean-checkout release reproduction |
| P01-A5 | Working-checkout demo and bounded profiled workload | Full reference study and release validation |

## Failed measurements and their limits

The full reference attempt ending at 2026-10-18T09:13:34Z failed after 1,513.716 seconds. The 100,000-input trials with 1 and 8 writers passed; the 32-writer trial completed 99,998 input groups and failed two. One six-call client budget was exhausted, with 2,506 additional calls and 2,508 temporary responses. PostgreSQL reported a serialization pivot at receipt insertion; this does not identify the complete SSI dependency cycle. Six later trials did not run. Teardown passed and a separate final-copy disk guard failed.

The failed attempt remains identified in [performance](PERFORMANCE.md). Its preserved source, runtime, raw data, and database dumps document the failure; preservation does not convert it into acceptance. An earlier smoke failure is recorded separately in `evidence/system-smoke-first-failed.json`.

The subsequent retry policy samples independent exponential windows of 4, 8, 16, 32, 64, 128, and 256 ms, capped at 256 ms. Eleven possible retry sleeps request at most 1,532 ms in total; database execution, lock waits, and scheduling can make wall time longer. Connections close before backoff. The database budget remains 12 attempts; clients allow at most six temporary-only calls with the same key and intent. Functional regressions passed, including a synchronized 32-writer case; no completed full study establishes the policy's capacity or improvement at 100,000 inputs.

## Outstanding release checks

Run the complete 100,000-journal workload at 1, 8, and 32 writers for three independent rounds on an idle reference host, followed by its validators. Preserve the prescribed seed, 20 tenants, 10,000 accounts, currency mix, duplicates, reversals, latency boundary, and failed outcomes. JMH requires three forks, three one-second warmups, and five one-second measurements per case with GC profiling. Prepared runtime receipts prevent Maven work from entering the measured interval.

Clean-checkout lifecycle reproduction and repository-wide release checks remain separate obligations. Cached artifacts do not authorize skipping correctness tests. See [operations](OPERATIONS.md), [performance](PERFORMANCE.md), and [review](REVIEW.md) for the commands and boundaries.
