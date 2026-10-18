# Five-minute demo and technical walkthrough

Run `scripts/demo.sh`. Minute0–1: balanced post, unbalanced rejection and repeated receipt. Minute1–2: kill committed JVM writer and recover the original receipt. Minute2–3: full reversal and preserved source. Minute3–4: import the same CSV twice and inspect visible ambiguity/invalid/duplicate rows. Minute4–5: shadow replay, freshness and zero-imbalance audit.

For a10–15 minute walkthrough:

1. Trace Jwt principal→DTO amount validation→canonical intent→serializable transaction→privileged function→receipt/outbox commit.
2. Explain why debit/credit differ from increase/decrease, and calculate cash/revenue display balances and reversal.
3. Read post_journal's period lock, sorted account locks, exact multiset reversal check and grants. What does DBA exclusion mean?
4. Read the64-request race and crash barrier tests. Why does an HTTP timeout not authorize a new key? Where does complete retry restart?
5. Run the delayed event-ID commit schedule. Why does MAX(id) fail? How do membership and shadow generations survive restarts?
6. Inspect the reconciliation ambiguity fixture. Why is amount-only matching forbidden? Show the actor/reason/evidence decision record and candidate uniqueness.
7. Inspect raw benchmark percentiles, retry/lock observations and generated expected file. Use EXPLAIN on the statement's tenant/date/id keyset join; discuss index order and the per-period fence bottleneck.
8. Make a small live change to a fixture amount, predict the reference sum, run the relevant property/database test and explain any rejected bound.

The project is agent-assisted synthetic portfolio work, not a claim of production money movement or prior employment.
