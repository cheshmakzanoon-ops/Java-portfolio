# Running and recovering

Run `scripts/up.sh`, `scripts/demo.sh`, `scripts/down.sh` from this project or the root portfolio launcher. The default product workflow is CLI-first and has no background processes. Down preserves journals. `scripts/reset-demo.sh` removes only generated `projects/06-matchcore/.runtime/demo.*` directories. An optional HTTP server stays in the foreground under `scripts/serve.sh`; Ctrl-C closes it. A service manager may own that foreground process.

Health is `GET /health`. Unhealthy after storage/writer failure means stop, retain journal and investigate storage; never switch to an in-memory success mode. Stable-ID retries after timeout are necessary: an unacknowledged command can have durable effect. `GET /commands/{id}` only returns the authenticated account's results. `GET /book?minimumSequence=N` waits up to two seconds; an unachievable sequence returns unknown/pending, not a stale promised read.

A second writer cannot acquire the journal lock. A complete corrupt header, checksum, schema, payload or sequence gap fails startup. Do not manually delete records until the underlying corruption is understood. Incomplete last frames alone are truncated with a force operation. Keep a cold copy before diagnostic experiments. Fault tests operate on disposable directories.

Snapshots are optional: deleting a bad snapshot is safe because the full log remains authoritative. Snapshot writes require idle admission. Cold backup: stop the writer, copy the entire journal and optional `.snapshot` together, then restart. Restore by placing those retained files in a new dedicated run directory and specifying the original run ID. Do not run two writers against the same file or claim recovery after local disk loss.

The journal format is schema version 1; no online migration exists. Run identities and immutable initial balances are explicit command inputs. Random generators exist only in workload generation; replay consumes recorded commands without wall clock or randomness.

Snapshot envelopes are capped at 16 MiB; larger checkpoint publication rejects explicitly and retained full-log recovery continues. Recovery also refuses logs exceeding the finite 1,100,000-entry run cap. This baseline favors inspectable bounded laboratory runs over unlimited history or snapshot pruning.
