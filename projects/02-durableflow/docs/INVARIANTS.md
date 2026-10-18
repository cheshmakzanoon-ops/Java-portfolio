# Invariants and executable checks

| Invariant | Enforcement | Evidence command/test |
|---|---|---|
| Definitions are finite, typed, acyclic, immutable and bounded | Definition.validate; definition unique constraints | DefinitionTest |
| Runtime types are real JSON scalar types and task arguments derive from predecessor outputs | Data values, exact execution schema, typed events and completion checks | DataTest; TypedReplayTest; typedRuntimeRejectsAtomicWrongTypesAndSendsActualPredecessorValues |
| Existing semantics1 histories retain their original interpretation across upgrade | additive migration and frozen adapters | persistedLegacyPinsRemainSemanticsOneAcrossTypedUpgrade |
| A history starts once, has contiguous ordering and legal task/event identities | Interpreter.replay | InterpreterTest corruptAndOutOfOrderHistoriesAreRejected |
| Replay creates the same task IDs/state without I/O | pure interpreter; instance-scoped node IDs | ReplayPropertyTest (500 jqwik cases, fixed seed); generated 1,000-history reference-prefix test |
| History decision and queued records commit together | WorkflowStore.tx, instance lock/version | PostgresWorkflowIT killedTransactionLeavesNeitherHistoryNorScheduling (actual killed process) |
| Duplicate start/signal cannot change request intent | tenant/start key; request comparison; signal inbox | duplicateStartsSignalsAndTenantIsolation |
| Stale generations cannot dispatch, heartbeat or complete | exact generation-bearing credential verification | leaseFencingAndHeartbeatLate |
| Provider idempotency is verified from effects, not HTTP status counts | independent fsynced CALL/EFFECT log | workerKillAfterEffectIdempotencyAndUncertainty; asserted demo |
| Non-idempotent ambiguity does not dispatch twice automatically | dispatch intent + UNKNOWN_OUTCOME | same real-process kill test; asserted demo |
| Signal/timer has one serialized winner; losing evidence is retained | instance row lock and timer status transition | timersAndSignalRaceSerializeAndDormantTimersDoNotAllocateThreads |
| Parallel undo follows reverse completion order, cancellation settles outstanding leases | pure event fold + persistence lease barrier | reverseCompletionCompensationAndVisibleFailure; parallelCancellationCompensationFailureAndPinnedUpgrade |
| A definition upgrade does not mutate a running pin | immutable version content and pinned hash/semantics | pinned-upgrade integration test and demo |
| Tenant and role boundaries include protected token validation | Spring Security/OIDC and tenant SQL scope | protectedApiVerifiesRealOidcTokensAndTenantPermissions |

Commands: `./mvnw -f projects/02-durableflow/pom.xml -B -ntp verify` and the same command with `-Pintegration`. Database tests require a working container engine and fail when it is unavailable.
