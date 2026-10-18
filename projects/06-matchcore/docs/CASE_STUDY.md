# A deterministic command can still have an unknown client outcome

The hard part of a matching simulator is accounting for every unit and explaining what survives a crash. A fast in-memory allocation loop alone cannot answer whether a timed-out cancellation happened.

The first design decision was to journal and force **before** applying a command to committed live state. One writer owns sequence, matching and deduplication. Retained stable command identities make a retry return an earlier durable outcome. The queue and pending-ID map coalesce concurrent retries before a sequence is allocated. This avoids the subtle error of checking deduplication only after a batch has been applied.

The matching algorithm indexes prices and order identities while using a linked FIFO within a level. Its independent test oracle instead sorts a flat list on every command, recomputes held balances and uses BigInteger supply arithmetic. Neither shares the production transition implementation.

That independent structure found a real defect: a zero-remaining replacement with an opposite side field returned SIDE_CHANGE in production while the reference treated zero as cancellation. jqwik minimized the generated seed to -6026787 at step 186. The fix moves zero-cancellation before side-change validation; a three-command regression lives in InvariantsTest and the preserved fixture in fixtures/regression-zero-replace.json. This was a specification interpretation defect, not a fabricated production incident.

Another deliberate choice is immutable aggregate depth rather than copying every result and execution on every acknowledgement. The finite run retains all identities and outcomes, while ordinary reads publish account balances and price aggregates only. Full state is materialized at an idle verification/snapshot boundary. This avoids O(history) publication per command while preserving reader consistency.

The demo shows a cancellation forced before its client response, then resolves the unknown outcome by restart and stable-ID retry. Separate child-JVM tests kill processes before append, during a frame, after force and after application. The test compares recovery with the entire durable prefix, not just observed responses. Neither fault injection nor SIGKILL proves hardware power-loss safety. The guarantee depends on retained local storage honoring force; replication and disk-loss tolerance are intentionally excluded.

Measured baseline and methodology are maintained in PERFORMANCE.md; incomplete gates remain visible in STATUS.md. This repository was developed with AI coding assistance against the supplied project contract; no employment history, customer deployment or independently operated exchange is implied.
