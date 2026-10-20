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
| Every legitimate maximum-fan-in worker claim fits its bounded acknowledgement envelope; tampering cannot authorize dispatch | Shared task/body byte budgets and comparison with the authoritative leased row | `WorkerEnvelopeFixture.verify`, exercised by the protected HTTP integration test |

Commands: `./mvnw -f projects/02-durableflow/pom.xml -B -ntp verify` and the same command with `-Pintegration`. Database tests require a working container engine and fail when it is unavailable.

## Transaction failure and runtime isolation

| Boundary | Required outcome | Regression |
| --- | --- | --- |
| Command fails; rollback and connection close also fail | The command remains the primary exception; cleanup failures are suppressed in occurrence order | `JdbcTransactionsTest.domainFailureRemainsPrimaryWhenRollbackAndCloseBothFail` |
| Commit acknowledgement fails | No success is returned; the original commit failure and rollback evidence remain available | `JdbcTransactionsTest.failedCommitPreservesItsCauseAndNeverReportsSuccess` |
| One instance cannot append because its history is full | Its timer/lease mutation rolls back; another instance commits; restart retains the failed instance's retry delay | `RuntimeRecoveryIT.fullHistoryFailureIsIsolatedAndDeferralSurvivesRestart` |
| Another scheduler or command holds an instance row | Skip that instance without blocking unrelated due timers; each timer is recorded once | `RuntimeRecoveryIT.lockedInstanceDoesNotBlockAnotherTimerOrReceiveDuplicateFiring` |
| A command advances history between scheduler rollback and error recording | The old version cannot receive a retry delay, error, or failure-counter increment | `RuntimeRecoveryIT.successfulCommandAfterRollbackCannotInheritStaleSchedulingDeferral` |
| Claim acquisition fails before acknowledgement, then storage recovers | No speculative lease dispatches; polling resumes and records exactly one effect | `RuntimeRecoveryIT.claimStorageOutageRecoversWithoutDispatchingAnUnacknowledgedLease` |
| Shutdown interrupts claim backoff | Polling exits, no dispatch occurs, the task remains READY; contract errors still propagate | `RuntimeRecoveryIT.claimBackoffIsInterruptibleAndContractErrorsRemainTerminal` |
| Scheduler storage acquisition recovers after an outage | The same runtime continues, records one timer firing, and still propagates terminal SQL contracts | `RuntimeRecoveryIT.schedulerStorageOutageRecoversAndTerminalStorageErrorsPropagate` |
| SQL fails after the scheduler locks an instance | Roll back the timer/history transaction and propagate storage failure; do not write a domain deferral | `RuntimeRecoveryIT.sqlFailuresAfterTheInstanceLockRollbackWithoutBecomingDomainDeferrals` |
| Shutdown follows a committed provider effect but precedes its receipt | Accepted work can commit its receipt before heartbeat/client shutdown; interrupt status survives cleanup | `RuntimeRecoveryIT.interruptedShutdownDrainsAnAcknowledgedEffectBeforeClosingLeaseResources` |
| Persisted compensation event names an older pending obligation | Reject the history instead of silently accepting out-of-order undo | `InterpreterTest.replayRejectsOutOfOrderCompensationRatherThanTrustingPersistedTaskNames` |

The implementation boundaries are [transaction ownership](../workflow-persistence/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow/JdbcTransactions.java), [scheduler isolation](../workflow-persistence/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow/SchedulerBatch.java), [retry policy](../workflow-persistence/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow/ActivityFailure.java), and [current replay](../workflow-engine/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow/Interpreter.java). JDBC fault tests exercise resource failures directly; PostgreSQL tests exercise actual locking and durable rows. Mutation analysis of pure code is separate evidence and does not substitute for database/process tests.

## State and failure transitions

| Current condition | Recorded fact | Result |
| --- | --- | --- |
| Ready activity | Current lease and committed dispatch intent | External call may begin |
| Repeatable or undispatched work fails before attempt 4 | Attempt outcome and retry deadline | Same logical task returns to READY with a new future generation |
| Non-idempotent dispatched work has no definitive result | `UNKNOWN_OUTCOME` | MANUAL_REVIEW; no automatic replacement call |
| Forward attempts exhausted | `FAILED` | Stop new forward dispatch; settle leases before compensation |
| Cancellation with leased forward work | `CANCELLED` plus bounded lease evidence | CANCEL_REQUESTED until outstanding forward leases settle |
| Compensable completions remain | Next reverse-completion obligation | One COMPENSATING task at a time |
| Compensation attempts exhausted | Failed compensation task and obligation | COMPENSATION_FAILED until authorized retry |
| Operator confirms applied effect | `OPERATOR_RESOLVED` plus typed result and reason | Resume from the recorded real result |
| Operator confirms no effect | `OPERATOR_RESOLVED` with NOT_APPLIED and no result | Forward failure or failed compensation; no fabricated successful value |
| Storage/commit is unavailable | No successful command acknowledgement | HTTP 503; retry an idempotent request with the same key |
| Scheduler cannot transition one instance | Rolled-back instance transaction and persisted deferral | Other instances continue; the failure remains observable |

A terminal domain state does not imply that an external business effect was rolled back. MANUAL_REVIEW, COMPENSATION_FAILED, and scheduler deferral have different causes and recovery actions.
