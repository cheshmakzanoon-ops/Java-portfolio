# Walkthrough and interview route

For a 3–5 minute demo, start the project and run its asserted demo. Show the live independent provider log, the rejected stale completion, the unknown-outcome state, operator-decision row, visible failed compensation, and preserved old definition hash.

For 10–15 minutes, trace POST execution → tenant/idempotency check → instance transaction → STARTED/history → pure replay → unique task insert → claim/lease → committed dispatch intent → HTTP provider → fenced completion. Then inspect the killed-transaction and killed-worker tests.

Questions grounded in code:

- Why does a database fencing generation fail to prevent duplicate external effects?
- Which exact transaction contains history append and scheduling, and what does a pre-commit SIGKILL leave behind?
- How can an empty SKIP LOCKED claim coexist with unfinished work?
- What happens when a worker heartbeats after expiry or cancellation races an authorized call?
- Why is reverse completion order a domain assumption for parallel compensation?
- Which inputs make replay deterministic, and how does a definition upgrade preserve old executions?
- Why are dormant timers rows instead of sleeping threads?
- How would you read the timer/task indexes and the raw workload percentile queries before changing scheduling?
- What is the next scaling bottleneck? Connection setup per transaction, replaying bounded full histories, and a single PostgreSQL instance deserve measurement before partitioning.

A useful live change is adding a new compiled pure activity plus a validator/worker test. Arbitrary uploaded executable activity code is deliberately outside the trust model.
