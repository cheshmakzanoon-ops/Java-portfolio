# Accounting vocabulary and invariants

Debit and credit are sides. Asset cash is normally debit; revenue is normally credit. Posting CAD12.34 debit cash and CAD12.34 credit revenue gives cash signed debit-minus-credit+1234 and revenue−1234. Presented normal-side balance is `(debits−credits) × (normal_side==DEBIT ?1:−1)`, so both display12.34. A reversal swaps every side and brings their signed balances to zero while retaining both journals.

| Invariant | Enforcement | Regression evidence |
|---|---|---|
| Positive integral bounded line amounts,2–100 lines, one currency, equal debit/credit sums | Journal and privileged SQL function plus column checks | AccountingProperties; databaseMatchesIndependentGeneratedModelAndPrivileges |
| No partial journal/balance/receipt/outbox commit | Single SQL transaction/function | actualProcessTerminationBeforeAndAfterCommit |
| At most one result per tenant+operation+key; changed intent conflicts | Immutable digest/response receipt; unique key; serializable retry | sixtyFourDuplicateRequestsAndRacingReversals |
| Account currency immutable and same tenant | Runtime INSERT-only accounts; composite FKs; function validates currency | databaseMatchesIndependentGeneratedModelAndPrivileges |
| Full reversal exactly swaps source; one full reversal only | EXCEPT ALL multiset comparison, unique `(tenant,reverses)` | racing reversals |
| No posting into a closed or wrong-dated period | Locked period row and date boundaries | closeRacesWritersWithoutPostingIntoClosedPeriod |
| Runtime cannot bypass posting boundary | Explicit grants; hardened function search_path; no public function execute | actual runtime-role mutation attempts |
| Projection redelivery is idempotent; no missed late commit from sequence IDs | Durable applied-event membership committed with deltas | projectionHandlesEarlierAllocatedEventCommittedLater |
| Reporting promotion matches source | Shadow generation and two-way set comparison | replay and backupRestoreReauditsImmutableSource |
| Reconciliation does not invent matches or consume a posting twice | Exact reference/currency/signed amount, ambiguity state, unique candidate | reconciliationDistinguishesRowsAndPreservesDuplicateImport |

The independent reference computes debit/credit sums in memory without SQL or database functions. Currency fixture version1 is immutable for this release. DBA writes and host compromise are outside the integrity threat model.
