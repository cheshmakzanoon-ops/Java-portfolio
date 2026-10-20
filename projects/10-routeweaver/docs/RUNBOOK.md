# Recovery quick reference

The full command and configuration guide is [OPERATIONS.md](OPERATIONS.md). Keep a database backup before administrative repair. A JVM and a durable job have separate lifecycles; a published plan has a third lifecycle.

1. **Stop further writes intentionally.** Use the owned launcher's `down.sh`, or stop a manually started foreground process through its terminal. An ownership refusal is a diagnostic, not permission to kill a PID by name.
2. **Identify the boundary.** Inspect the job's tenant, input version/hash, source, state, fence, lease and retained incumbent. Use the incident ID for unexpected HTTP failures. Do not copy tokens into logs or tickets.
3. **Restore matching execution.** Start the verified source/artifact pair. Live leases are not stolen. Expired running work receives a new generation; expired cancellation is acknowledged. Shutdown-fenced work is already queued.
4. **Validate before publication.** Confirm the job's independently valid same-input incumbent. Publication still needs current active input and plan revision. An old successful job cannot bypass a 409.
5. **Check cleanup.** `down.sh` waits for the owned process and removes only its Compose container/network. The named volume remains. A foreign listener, stale receipt or mismatched live PID requires inspection before a new launch.

For corrupted or inconsistent restored state, use a separate restore database and the integration restore/constraint checks. Do not rewrite immutable snapshots, bypass fencing, discard audit history, or change failed study records to make a readiness check pass. The system supports retained-storage process recovery; it does not claim disk-loss tolerance or production availability certification.
