# ADR001: one privileged posting boundary

Accepted: PostgreSQL owns posting invariants through a restricted SECURITY DEFINER function. The function fixes `search_path=pg_catalog,ledger`, uses explicit schema names, has no dynamic SQL, and revokes PUBLIC execution. The runtime login has SELECT plus narrowly granted operational writes; no direct journal/posting/balance mutations.

Rejected: an ordinary CHECK for cross-row equality (PostgreSQL cannot enforce a whole journal sum that way), ORM callbacks (other writers bypass them), and application-only serializability. Application transactions use SERIALIZABLE, period locking, stable UUID account lock order and complete bounded retries. The period lock is deliberately conservative and can become the first write scaling bottleneck.

A receipt digest covers normalized intent; its result, source, totals and outbox commit atomically. A timeout is ambiguous. Query the receipt or retry the same operation/key, never generate a new key merely because the first response was lost.
