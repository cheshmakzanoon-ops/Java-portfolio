# Processing invariants

## Event-time state

| Invariant | Enforcement | Falsifying regression |
| --- | --- | --- |
| Half-open one-minute tumbling and five-minute sliding membership; exact integral aggregates | `Engine.assigned`, checked pane/window sums | `EnginePropertyTest.panesAgreeWithIndependentMembershipOracle`: 120 seeded histories with a separate value-membership model |
| Progress, idle classification, duplicates, conflicts, restart, and watermark agree with an independent model | Partition maxima/activity, explicit observations, canonical identity hash | `SchedulePropertyTest`: 80 schedules; oracle derives its own watermark |
| Invalid measurements or overflow never partially change aggregates | Validate all dependent sums before updating panes/results | `EngineTest.invalidRecordsConflictOverflowOffsetsAndQuotasAreAtomic` |
| Watermark is monotonic; unseen active source blocks; all-idle holds | Minimum active source maximum minus five seconds, bounded below by prior watermark | Focused silence/restart/resume cases |
| Paused, broker-backlogged, queued, or in-flight work cannot prove source silence | Poll observations plus one admitted-through-acknowledgement outstanding count | `KafkaPumpLifecycleTest.uncommittedHandoffContinuesToBlockSourceSilenceAndOnlyAcknowledgedOffsetsMirror`; real backpressure integration |
| Final windows cannot reopen; lateness is evaluated separately per dependent window | `watermark >= window.end + 30 seconds` | Replay: old resumed event is rejected for tumbling yet contributes to four sliding windows |
| Restore restarts the entire silence interval | `State.afterRestart` clears idle flags and rebases activity | Idle/restart tests and process recovery |
| New keys, panes, identities, and durable diagnostics stay finite | Explicit run quotas; SQL diagnostic cap `6 × event quota` | Engine quota cases and durable quota/rollback integration |

Deduplication history records stable decisions, not solely accepted aggregate contributions. Invalid envelopes do not reserve identity; structurally valid records reserve it before quota/arithmetic admission. A repeated identical rejected identity is a duplicate; changing its payload conflicts. Admitted keys and identity rows persist until explicit run archival.

## Commit and recovery

| Invariant | Enforcement | Falsifying regression |
| --- | --- | --- |
| Candidate state never mutates the committed engine state | Copy-on-transition; adopt only after SQL acknowledgement | Engine atomicity cases and database-outage session test |
| State, dedup, positions, results, diagnostics, and pending output share one decision | One fenced PostgreSQL transaction | Actual process termination before commit and after commit/before offset mirror |
| An obsolete owner cannot write after takeover | Database-time lease, monotonically increasing epoch, common owner row lock | `DurabilityIT.fencedOwnerRollbackQuotasAndTopicIdentity` |
| A slow locked transaction renews before relinquishing ownership | Renew while holding the same owner lock before commit | Slow-output transaction exceeds its two-second lease and still retains ownership |
| SQL defaults cannot silently change the row-lock isolation model | `SqlTransactions` explicitly selects READ COMMITTED | Transaction regression observes isolation and disabled autocommit |
| Failed/unknown commit is never blindly retried in memory | Session becomes `SESSION_RESTART_REQUIRED`; no SQL utility retries | Lost acknowledgement unit case and real database outage/restart |
| Cleanup cannot erase a known commit or its primary failure | Explicit acknowledged phase; suppressed rollback/close failures | `SqlTransactionsTest` covers SQL/runtime/fatal primary failures and close-after-commit |
| Next source position may include offset gaps but cannot move backward | `Input` bounds and engine retained positions | Gap properties and restored SQL position checks |
| Missing retained positions and changed topic identity never silently reset | Broker range/UUID/partition checks before processing | Real deleteRecords/topic recreation/partition growth cases |
| Invalid snapshot roots/classes or exceeded graph/byte bounds never become state | Concrete class filter, root type, graph/array/decompressed-byte budgets | Legacy-format restoration, unrelated-class rejection, byte/skip boundary regressions |

The source checkpoint is a **next position**, not the offset of the last business record. Kafka transactions and control records can create gaps. SQL snapshot positions and normalized checkpoints commit together; Kafka's group position is an asynchronous mirror.

## Delivery, lifecycle, and access

| Invariant | Enforcement | Falsifying regression |
| --- | --- | --- |
| Output publication may repeat but never invents a new identity on retry | Durable `(run, window, revision)` key | Process termination after Kafka acknowledgement before SQL mark, externally consumed duplicate keys |
| Partial output failure cannot mark earlier records in the failed SQL transaction delivered | Explicit rollback in the common transaction boundary | `failedOutboxPublicationRollsBackAllDeliveryMarksAndRetriesStableRevisionIdentities` |
| Resource release follows actual owner termination | Engine first, verified joins, then poller/publisher/lease cleanup | `ThreadTerminationTest`; interrupted shutdown during a forced in-flight SQL commit |
| Startup failure or pre-start close completes readiness and releases the consumer | Synchronized start/close; exceptional readiness completion | MockConsumer retained-range and closed-before-start tests; actual unstarted-client JMX cleanup |
| Only the poll thread calls KafkaConsumer methods except documented `wakeup` | Consumer ownership is contained in `KafkaPump` | Production consumer path plus controlled MockConsumer schedules |
| Tenant request identity cannot select another tenant's rows | Verified signed tenant, matching query tenant, membership and SQL predicates | Actual RSA/JWKS HTTP integration, tenant mismatch and nonmember cases |
| Request/envelope sizes and scalar types are explicit | No-body filter; header/page bounds; strict JSON/identifier/routing checks | Actual chunked GET rejection; envelope bounds, unknown/duplicate fields, exact partition vectors, malformed `recordType` through restart |

The replay independently expects a finalized first tumbling window with count **3**, sum **35**, minimum **5**, maximum **20**, one logical duplicate, and one late tumbling contribution. The resumed value contributes to still-open sliding windows. The batch oracle retains values and enumerates timeline intervals; it does not call pane assignment/merge or consume the engine watermark as its expected state.

## Evidence limits

Tests demonstrate bounded histories and named failure schedules, not an exhaustive protocol proof. Historical workload recovery compares a documented semantic projection; it excludes rebased activity/idle fields, run identity/limits, diagnostics, output delivery, and finalized result history. Its SQL oracle independently checks key/end/aggregate/finality fields and positive revisions, not exact revision sequences. Those limits and failed attempts remain visible in [verification status](STATUS.md) and [performance](PERFORMANCE.md).
