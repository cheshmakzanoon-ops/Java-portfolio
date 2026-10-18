# ADR 001: separate recorded-state consistency from external effects

Accepted. PostgreSQL atomically appends history and materializes tasks. A logical ID prevents duplicated scheduling. This cannot atomically commit an external provider's independent effect.

Rejected: treat timeouts as negative responses and automatically retry every activity. A process can die after the provider performs the effect and before acknowledging it; fencing the next engine write does not retract that effect.

Chosen: activities explicitly declare PURE, IDEMPOTENT_WITH_KEY, or NON_IDEMPOTENT. Dispatch intent must commit while the generation is current before any external I/O. Repeatable activities keep `instance:logical-task` keys across attempts; the fake provider retains its effect receipt and original parameters for the lifetime of its append-only log. Same key and changed parameters returns conflict. Production providers must retain keys for at least the workflow/reconciliation retention period; this demo makes no claim for a provider that forgets its keys.

Non-idempotent dispatch without a definitive receipt becomes UNKNOWN_OUTCOME and pauses the execution for authorized reconciliation. APPLIED/NOT_APPLIED is a new history and operator-decision row, not a rewritten event. NOT_APPLIED fails the step instead of silently allowing a new effect. Compensation is durable business work with the same possible uncertainty, not a database rollback.
