# Why a late value changes four sliding windows and leaves another untouched

The hard part is not counting telemetry. It is deciding what a count means when sources advance at different speeds and a process dies between SQL commit and Kafka mirroring.

PulseStream defines an original pane algorithm and an explicit source-progress state machine. The first-minute demo initially publishes 30 from values 10 and 20. A late 5 at second 50 creates revision two with total 35. Killing the process immediately after that transaction leaves SQL authoritative: recovery seeks after the committed next offset rather than reapplying the late value. Once an idle source lets another source advance watermark to second 100, the first minute is final. A resumed event at second 40 is late for the tumbling result and the oldest overlapping sliding result, while four other sliding results still accept it. This is why one whole-event "late" flag would be wrong.

An early fixture defect used the same source-progress event ID on two partitions. Run/tenant event dedup correctly suppressed the second source's progress, so the expected watermark never appeared. The failed property/demo assertions caught the fixture's invalid assumption; source-progress IDs now include source identity. Another correctness choice is to retain SQL results while removing finalized engine windows: a shared pane may legally change for a later sliding window without reopening the old result.

Atomicity uses a row-locked ownership epoch and one PostgreSQL transaction for event decisions, state, results, next offsets and outbox. The real subprocess test halts before commit, after commit and during Kafka outbox delivery. SQL output/checkpoint equality and externally observed duplicate outbox keys establish the tested boundary. This is deliberately not a global exactly-once claim.

Write amplification from full current-state snapshots and pane replacement is an acknowledged tradeoff. The measured workload and actual unresolved gates are documented in PERFORMANCE and STATUS; neither the prescribed million-event workload nor a clean unit build is treated as an already achieved end-to-end performance result.
