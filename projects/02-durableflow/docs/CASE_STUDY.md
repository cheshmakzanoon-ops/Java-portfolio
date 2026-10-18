# Case study: cancellation while a parallel worker is still in flight

The difficult boundary is a durable state decision versus an already authorized external business effect. The initial cancellation design immediately scheduled compensation from the successful completions recorded so far. An adversarial review exposed a real defect: another parallel forward worker could acknowledge a later completion after cancellation, changing the required reverse completion order while the earlier compensation was already queued.

The chosen correction makes the persistence adapter wait for outstanding forward leases to settle before scheduling reverse-order undo. New dispatch is blocked by CANCEL_REQUESTED. A worker already dispatched may acknowledge a real result; an expired non-idempotent dispatch becomes unknown and requires reconciliation. This retains evidence instead of pretending cancellation retracts an effect.

The regression test completes one parallel child, cancels with another child leased, then records the late real completion. Only after that does compensation begin. The pure interpreter separately checks reverse recorded-completion order and visible failed compensation. The process-kill test independently checks provider effects across engine acknowledgement loss.

A second review found that an unknown task ID could enter replay's success map, potentially satisfying a count-based completion condition. Replay now rejects unknown logical IDs, mismatched event/task kinds, invalid prerequisite ordering, repeated starts and invalid operator decisions. These are development defects with executable regressions, not invented production incidents.

Limits: this is a bounded single-database engine with trusted compiled workers. Its versioned interpreter is original; database locking, HTTP, JWT, migrations and test containers use mature libraries. External uniqueness depends on a cooperating provider and retention policy. Compensation remains domain-dependent business work.

The independent A01 review also found repeated non-idempotent dispatch authorization within a single current lease. Fencing alone did not prevent that same worker from calling twice. A compare-and-set on dispatch intent now grants a non-idempotent attempt exactly one authorization; its regression counts independent provider CALL and EFFECT rows. The protection still cannot prove an external effect happened exactly once if a provider or authorized caller violates its own execution contract.
