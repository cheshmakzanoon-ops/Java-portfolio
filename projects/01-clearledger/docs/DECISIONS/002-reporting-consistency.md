# ADR002: membership, generations and visibility

Accepted: reporting processes each durable outbox event at least once, atomically recording generation/event membership with its delta. Commit order is independent of sequence allocation. The regression test allocates an event in an open transaction, commits a later event in another tenant, projects it, then commits the earlier one and proves it is still applied.

Rejected: treating MAX(outbox.id) as an applied watermark. That design permanently loses a lower event that commits later. No real incident is claimed: this adversarial schedule motivated the design.

A replay writes a separate generation, compares all authoritative debit and credit totals bidirectionally under an outbox SHARE fence, and promotes only after equality. Live tables are never truncated by replay. A concurrent commit can cause a safe failed comparison and a new-generation retry. The API reports pending event count; authoritative statements never come from reporting.

Statement cursors carry a PostgreSQL xid8 visibility snapshot, avoiding the analogous backdated/later-commit pagination problem. Immutable source rows make this token usable without retaining MVCC row versions or a long-lived connection.
