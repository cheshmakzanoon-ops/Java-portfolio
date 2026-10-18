# Case study: recoverable accounting without a false watermark

The hard requirement is a single auditable accounting result even when a client does not know whether commit happened. A privileged SQL boundary atomically inserts journal/postings, updates authoritative totals, preserves a canonical request receipt and writes an outbox event. The adapter retries the complete serializable transaction and never puts network side effects inside it.

The rejected reporting design used the greatest processed sequence ID as a checkpoint. Sequence allocation precedes transaction commit, so a slow transaction can receive ID1, another commit ID2 and advance that checkpoint, then the original commit becomes permanently invisible. No production incident is claimed. The adversarial delayed-commit integration test demonstrates this schedule and motivated durable `(generation,event)` membership.

The chosen design makes event application and membership one transaction; a restarted projector scans for missing memberships. Replay uses a separate generation and verifies source totals before promotion. The same commit-order reasoning prompted transaction-visibility tokens for keyset statements rather than pretending recording time alone freezes a multi-page statement.

Regression evidence includes a real JVM killed before commit and a second killed after commit,64 duplicate submissions, racing reversals/closure, exact reconciliation statuses and a clean-instance dump/restore audit. Consult STATUS for observed passes and remaining gates; source presence is not a passed acceptance item.

A consequential defect did occur during real-role integration testing: Java attempted `LOCK TABLE outbox_event IN SHARE MODE`, but PostgreSQL correctly denied this to the restricted runtime role. Granting source-table UPDATE would have broadened the ledger integrity boundary. The fix moved the fence, source comparison and promotion into a narrow schema-qualified SECURITY DEFINER `promote_shadow(uuid)` function. The failing first run is recorded in evidence; its runtime-role replay regression must pass before release.

Review also identified an amount-only corner case: two blank references could satisfy a SQL equality predicate and look like a reference match. Automatch now requires a nonblank reference; a matching amount with a missing reference remains UNMATCHED/MISSING_REFERENCE. An explicit PostgreSQL regression protects this rule.
