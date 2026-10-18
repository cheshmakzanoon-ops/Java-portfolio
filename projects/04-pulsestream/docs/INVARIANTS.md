# Invariants and checks

| Invariant | Enforcement and falsifying tests |
|---|---|
| Half-open tumbling and sliding membership with exact integer aggregates | `Engine.assigned` / pane merge; `EngineTest.panesAgreeWithIndependentMembershipOracle` (120 histories, seed 4404) |
| Reordered/late arrival, retry and conflict behavior matches a separate model | `SchedulePropertyTest` (80 histories, seed 4411), exhaustive per-event memberships in `BatchOracle` |
| Invalid measurements and any checked overflow change no aggregate | `Engine.accept`; `EngineTest.invalidRecordsConflictOverflowOffsetsAndQuotasAreAtomic` |
| Watermark never decreases, unseen source blocks, all-idle holds | `Engine.advance`; `EngineTest.sourceSilenceRequiresEligibleNoBacklogAndAllIdleHolds` |
| Paused/queued/broker-backlogged input cannot prove silence | Explicit `Observation`; focused engine test and real pump backpressure/retention integration |
| Final windows never reopen; lateness is separate per dependent window | `closed` evaluated before each assignment; demo resume contributes only to four sliding windows |
| Restart never trusts previously idle classifications | `State.afterRestart`; engine idle/restart tests and real subprocess recovery |
| SQL outputs/checkpoints, dedup and outbox are one atomic decision | Fenced transaction; process kills before commit and after commit/before mirror in `DurabilityIT` |
| Former epoch cannot write after takeover | `run_owner FOR UPDATE`, database-time lease, `DurabilityIT.fencedOwnerRollbackQuotasAndTopicIdentity` |
| Dedup scope is run + tenant + event ID; conflicting payload quarantined | Full canonical-payload SHA256; finite non-expiring `seen_event` rows, retry/conflict tests |
| Offset checkpoint is next position, accepts gaps and forbids backward movement | `Input` bounds and `Engine.accept`; gap property histories and SQL recovery offset assertions |
| Topic recreation and missing retained positions are fatal | UUID on durable run; real broker deleteRecords retention test |
| New keys/panes/events over quota are explicit durable rejections | Per-run finite limits; unit and PostgreSQL integration tests |
| Outbox delivery can duplicate, with stable run/window/revision identity | Real process halted after Kafka publication before delivery mark, retry externally consumes duplicate keys |
| Tenant query isolation and protected token validation | Parameterized SQL and API tenant check; real local RSA issuer integration tests for signature/issuer/audience/expiry/scope/tenant |
