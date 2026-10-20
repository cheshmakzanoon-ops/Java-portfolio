# Accounting invariants

## Vocabulary and arithmetic

Debit and credit are accounting sides, not synonyms for gain and loss. Cash is normally debit; revenue is normally credit. Posting CAD 12.34 as debit cash and credit revenue produces signed balances +1,234 and −1,234 minor units. Normal-side presentation is:

```text
normal balance = (cumulative debits − cumulative credits) × normal-side sign
sign(DEBIT) = +1; sign(CREDIT) = −1
```

Both accounts therefore display CAD 12.34. A full reversal swaps every side and retains both immutable journals. The signed totals return to zero, while cumulative debit and credit histories remain visible. Negative normal balances are possible; the model does not silently reinterpret debit/credit as nonnegative inventory.

[Money](../ledger-domain/src/main/java/io/github/cheshmakzanoon/portfolio/clearledger/Money.java) uses exact `BigInteger` minor units. CAD/USD have two decimals and JPY none, according to [currency-scales-v1.csv](../fixtures/currency-scales-v1.csv). Scientific notation, excess precision, binary floating-point input, and out-of-bound values are rejected.

## Commit invariants

| Invariant | Enforcement boundary | Falsifying regression |
| --- | --- | --- |
| Every line is positive integral money; 2–100 lines share one currency; total debit equals total credit and is at most `10^30 − 1` | `Journal`, current `post_journal`, numeric/check constraints, deferred journal trigger | `AccountingProperties`; `databaseMatchesIndependentGeneratedModelAndPrivileges`; malformed raw SQL cases |
| Account cumulative debit and credit totals each stay within the same bound | Atomic numeric(30,0) balance update; independent reference accumulation | Generated reference comparisons and maximum-balance rejection |
| Source, totals, receipt, and outbox either all commit or all roll back | One database transaction and function | Actual writer termination before and after commit |
| Operation identity is complete before receipt lookup; SQL NULL never matches a digest | V3 input checks and `IS DISTINCT FROM`; `receipt_operation` constraint | `privilegedEntryPointRejectsNullDigestAndInvalidOperationBeforeReceiptLookup` |
| One immutable result per tenant + operation + key; changed intent conflicts | Unique receipt key and canonical intent digest | 64 same-key submissions and retained-receipt retries |
| Canonical references are valid PostgreSQL UTF-8 text and cannot collapse through replacement encoding | `Journal` rejects malformed surrogates and NUL before digest construction | `malformedUnicodeCannotCollapseDistinctReferencesIntoOneUtf8Digest` |
| Account currency and tenant relationships cannot change through the runtime role | Account insert-only grant; composite tenant foreign keys; posting-function currency check | Runtime-role mutation and tenant-crossing tests |
| A target journal has at most one exact full reversal | Target lock, unique `(tenant,reverses)`, bidirectional `EXCEPT ALL` side comparison | Concurrent reversal races |
| Closed/wrong-dated periods reject new postings | Period row lock shared with close operation | `closeRacesWritersWithoutPostingIntoClosedPeriod` |
| An acknowledged commit is never replayed because connection cleanup failed | Explicit committed phase in `SerializableTransactions` | Pool-close failure with retryable SQL state still executes callback once |
| Unknown commit outcome does not cause blind internal replay | Connection-error classification and same-key receipt recovery | Lost commit acknowledgement regression and real process-crash recovery |
| Missing fields are explicit validation; unexpected null dereferences remain server defects | Domain/DTO checks; HTTP handler catches known `IllegalArgumentException` only | Required journal-field regressions and HTTP validation suite |

A digest ignores line order but retains line multiplicity, account, side, amount, period, date, currency, and reference. Different Unicode normalization forms remain distinct references; the service does not rewrite submitted text. A journal, including a prior reversal, can itself be referenced by at most one reversal.

## Derived-state invariants

| Invariant | Enforcement boundary | Falsifying regression |
| --- | --- | --- |
| Reporting applies each event once per generation despite redelivery/concurrent processors | Membership and deltas commit together; unique generation/event key; serializable retry | `concurrentProjectorsApplyEachEventExactlyOnceAfterAForcedLockInterleaving` |
| Projection failure cannot leave applied membership without its balances | Same transaction | `projectionFailureRollsBackMembershipAndDeltasBeforeRetry` injects a database error after membership insertion |
| Late commit of an earlier allocated event is still applied | Membership rather than `MAX(event_id)` | `projectionHandlesEarlierAllocatedEventCommittedLater` |
| Promotion preserves the active generation on mismatch | Outbox fence, two-way total comparison, pointer update in one transaction | Replay and restore tests |
| Reporting is explicitly non-authoritative and tenant freshness does not leak another tenant's pending count | Generation/snapshot metadata and tenant-qualified queries | Protected reporting authorization/freshness cases |
| A statement page and its summary retain the original source-visibility boundary | Immutable journals, xid8 visibility snapshot, recording-time bound, keyset cursor | Late-commit and keyset integration scenarios |
| A statement row and candidate posting are each consumed at most once | Unique match keys, row lock, current candidate-availability check | `competingManualDecisionsCannotConsumeOnePostingTwice` |
| Repeated import content preserves identity and results | Tenant/content-hash unique key; immutable prepared rows reused across retries | Duplicate-import and Unicode import/recovery regressions |
| CSV export order and escaping do not depend on JDBC map iteration | Fixed contract columns, quoting, formula-prefix neutralization | `exportUsesContractColumnsRatherThanMapIterationOrder` |

The projection concurrency fixture independently expects cash debits 350/credits 100 and revenue debits 100/credits 350 after journals of 100 and 250 plus reversal of the first. It derives those totals directly, rather than invoking the production projector as its oracle.

## Scope and audit

[SourceAudit](../ledger-verification/src/main/java/io/github/cheshmakzanoon/portfolio/clearledger/SourceAudit.java) reads the shared [SQL audit](../ledger-verification/src/main/resources/clearledger/source-audit.sql). It begins with every journal, including zero-posting journals, and checks count, positive bounded balanced totals, currency/date relationships, and exact reversal multisets. Separate grouped source totals must equal authoritative balances. A zero-row audit is evidence for those predicates in that snapshot, not a proof against arbitrary administrator corruption.

The runtime database credential is trusted application infrastructure. DBA writes, host compromise, external misuse of that credential, disk loss without a restorable backup, and regulatory compliance are outside this integrity model. Historical benchmark outcomes remain source-bound and do not establish unmeasured capacity for later changes.
