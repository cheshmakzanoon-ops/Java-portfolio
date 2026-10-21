# Demonstration and technical walkthrough

## Five-minute demonstration

Start the project and run `./scripts/portfolio.sh demo daybook` from the repository root. Follow the assertions and inspect the corresponding records:

1. Post a balanced journal, reject an unbalanced one, and retry the retained receipt.
2. Terminate a writer after commit and recover the original result with the same key.
3. Reverse the complete journal and show that the original source remains immutable.
4. Import a statement twice; inspect ambiguous, invalid, and duplicate rows.
5. Replay reporting into a shadow generation and verify freshness and the source audit.

## Design discussion

A 10–15 minute walkthrough should connect the request boundary to the durable result:

1. Trace a verified principal through strict amount parsing, canonical intent, the serializable transaction, the privileged posting function, and receipt/outbox commit.
2. Explain why debit and credit are not synonyms for increase and decrease. Calculate displayed cash and revenue balances, then their reversal.
3. Inspect `post_journal` for the period lock, UUID-ordered account locks, exact reversal multiset, fixed schema lookup, and restricted grants. State what the administrator exclusion means.
4. Read the duplicate-request race and process-crash tests. Explain why a timeout preserves the original key and where a complete transaction retry begins.
5. Show the delayed event-commit schedule. Explain why `MAX(id)` loses events and how per-event membership and reporting generations avoid that failure.
6. Inspect the reconciliation ambiguity fixture. Explain why amount-only coincidence is insufficient and show candidate uniqueness plus the actor/reason/evidence record.
7. Review actual retry counts, latency percentiles, and expected-balance files. Use `EXPLAIN` on the tenant-qualified statement keyset query and discuss the per-period write bottleneck. Distinguish a functional smoke from a completed capacity study.
8. Change a fixture amount, predict the reference total, and run the relevant property or PostgreSQL regression. Explain the bound if the new input is rejected.

The demonstration uses synthetic data and makes no claim of production money movement or prior deployment history.
