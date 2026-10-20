# Engineering review

The current review follows physical Kafka records through pure candidate state, fenced SQL commit, offset mirroring, result delivery, and owner shutdown. It covers all 19 production classes, the immutable V1 schema, and event/HTTP contracts. [Architecture](ARCHITECTURE.md) links every production class and shows the transaction/state boundaries; [invariants](INVARIANTS.md) identifies the enforcement and falsifying fixture for each guarantee.

## Findings and resolutions, 2026-10-20

| Severity | Confirmed failure | Resolution and regression |
| --- | --- | --- |
| High | Shutdown could finish a timed join and release/close resources beneath an engine owner that remained active. An interrupted cleanup caller could skip the wait entirely. | The engine stops before poller/publisher/owner release. Verified bounded joins preserve interruption and fail visibly if an owner remains alive. Real-thread fixtures distinguish blocking cleanup from a busy loop; PostgreSQL/Kafka coverage forces an in-flight commit while an interrupted caller closes the processor. |
| Medium | Removing a queued batch preceded setting the in-flight flag, briefly reporting no local backlog and invalidating the source-silence proof. | One outstanding count spans admission through SQL acknowledgement. A controlled MockConsumer schedule captures an observation with an empty queue and an uncommitted batch and proves it remains ineligible for source silence. |
| Medium | A closed/unstarted pump could start against an already-closed consumer, and shutdown during initialization could leave readiness pending. | Start/close synchronize their ownership transition, closed pumps reject startup, and readiness completes exceptionally on close/failure. Tests cover real unstarted-client JMX cleanup, closed-before-start, retained-range startup failure, and consumer termination. |
| Medium | SQL transaction paths handled failures differently: rollback could replace the primary failure; output failures relied on connection close for rollback; acknowledged cleanup failure looked like an unresolved commit. | `SqlTransactions` owns explicit READ COMMITTED work/commit/rollback/close across owner acquisition, state commits, and output delivery. SQL/runtime/fatal failures retain rollback/close diagnostics. Acknowledged commits retain their result; unknown outcomes are never replayed. Partial output failure proves all delivery marks roll back and stable identities repeat. |
| Medium | The broad `java.base/*` snapshot filter admitted unrelated serializable classes, had no array bound, and object-filter byte checks did not bound every decompressed/skipped byte. Null roots could escape typed decoding. | `SnapshotCodec` keeps V1 bytes and permits the concrete retained graph/collections/scalars only, with depth/reference/array/decompressed-byte budgets and root validation. Legacy-format state/result fixtures, unrelated-class rejection, and inclusive byte/read/skip boundaries cover compatibility and rejection. |
| Medium | A query-only process reported ready while PostgreSQL was unavailable. | Readiness validates the SQL connection and exposes `storeAvailable`; unavailable storage returns 503 without JDBC details. A driver-independent outage regression and live protected API health checks exercise both outcomes. |
| Medium | Failed dependency/topic/readiness startup could leak a new JVM or leave acquired containers running; PID-only shutdown could signal a reused PID, and artifact replacement could reuse an older JVM. | Startup snapshots existing all/running containers and rolls back only acquired resources. Kernel start/boot and exact-artifact records, launch-hash reuse gates, PID file descriptors, bounded waits, and deferred cancellation make ownership explicit. Build preparation refuses replacing live artifacts. Real-child/control-adapter tests cover failures, identity mismatch, resistant termination, and repeated cancellation. |
| Medium | The bounded command controller could leave descendants after leader exit, abandon cleanup on TERM, or signal a reused process group after reaping its leader. | A fresh group is cleaned on every outcome; cancellation is deferred during acquisition and ignored during cleanup. `waitid(WNOWAIT)` retains the leader through the final group signal. Real resistant-descendant and zombie-leader authority fixtures prove the cleanup order. |
| Low | Owner-release failure at shutdown was silent; a later cleanup failure could overwrite an earlier processor diagnostic. | Shutdown records a SQL-state-only warning while the lease expires, and the first processor failure remains authoritative. Constructor cleanup suppresses secondary release errors on the original restore failure. |

Current execution outcomes and remaining mutation limitations are recorded in [verification status](STATUS.md). No claim here converts bounded fixtures into an exhaustive protocol proof or a production-capacity result.

## Production coverage

| Module and files | Reviewed boundaries |
| --- | --- |
| Engine: `Engine` | Half-open membership, negative initial sliding starts, checked aggregate/window arithmetic, immutable-input transitions, exact finality/revisions, watermark monotonicity, unseen/idle/backpressured sources, dedup/conflicts, source gaps and finite quotas. Identity reservation before aggregate quota/overflow admission is explicit. |
| Adapter: `EventCodec`, `KafkaPump`, `KafkaResultPublisher`, `ThreadTermination` | Exact JSON scalar/version/type rules, duplicate/unknown fields, byte bound and canonical routing, fixed partitions/retention/UUID, consumer ownership, admitted-through-acknowledgement accounting, asynchronous full-checkpoint mirroring, Kafka acknowledgement and stable output keys, startup/close races, interruption/deadlines. |
| Storage: `PostgresStore`, `SqlTransactions`, `SnapshotCodec`, V1 migration | Database-time epoch/lease and shared owner row lock, renewal before commit, atomic snapshot/normalized writes, diagnostic growth, output row locks/publication/marks, query tenant predicates, same-version serialization compatibility, filtered/bounded decoding, primary/secondary transaction diagnostics. No schema or valid retained snapshot encoding was rewritten. |
| Service: `DurableSession`, `PulseApplication` | Committed/candidate lifetime, poisoned-session recovery, constructor cleanup, first failure, bounded owner-thread shutdown, resource release order, query-only startup and readiness. The API owner remains separate from the deterministic engine. |
| HTTP: `QueryApi`, `ApiSecurity`, `StrictIdentityDecoder`, `NoBodyFilter` | Trusted-loopback versus verified-RSA deployment, original signed identity types, issuer/audience/expiry/scope/tenant, run membership, tenant-filtered rows/late counts, body/header/page bounds, explicit status codes without storage diagnostics, SQL availability. |
| Verification: `BatchOracle`, `WorkloadOracle`, `ReplayWorker`, `Workload`, `EngineBenchmark` | Independent interval/value/progress models, literal process-crash boundaries, SQL-field/digest checks, seeded source preparation, functional versus measured intervals, CPU-only benchmark boundary, retained historical limitations. No new workload/JMH capacity study was executed. |
| Build/deployment/contracts | Six conventionally indented POMs, unchanged existing coverage/mutation thresholds, additional adapter/storage mutation targets, digest-pinned official PostgreSQL18.6/Kafka4.2.2, compatible patched application dependencies, source/artifact-bound lifecycle, v1 envelope and query contracts. Seven upstream Kafka image findings remain separate from the clean application-library boundary. |

## Operational limits

This remains one active processor per finite run, with one owner row serializing state/output writes and takeover. Slow output acknowledgement holds that row and delays processing; SQL and broker acknowledgement do not form a distributed transaction. Output can duplicate. A final Kafka offset mirror can lag shutdown; SQL remains authority. State copies and identity history scale with retained run history, so quotas and archival are required.

Snapshot bytes require compatible application classes and original limits; rolling migration and rescaling are unsupported. Trusted database/broker/deployment administration can alter state outside the tenant-user model. Local volumes are not a replacement for verified source-retaining backups. Reference performance is historical and cannot be transferred to later source/dependency/image versions.

## Earlier source-bound review record

## Resolved correctness findings

An earlier review identified three consequential defects:

| Finding | Resolution | Regression boundary |
| --- | --- | --- |
| The property oracle consumed the production watermark instead of deriving progress independently | The oracle tracks its own partition maxima, source activity, idle state, and progress deduplication | Generated record, observation, and restart schedules compare independent states |
| Duplicate JSON members were silently accepted | `EventCodec` enables strict duplicate detection | Malformed envelopes are quarantined |
| A session could continue after an ambiguous database exception | `DurableSession` requires SQL-authoritative restart after a failed commit | A real database outage preserves committed state and refuses subsequent session reuse |

The initial follow-up covered Java fingerprint `72a7d5d015c406f50b075ff3f0f7f7d7006166b6d17623f39735fd4f266735a8`. Later storage/startup review covered tree `d56210af3b61490dfe5f5c31f2a746d9040c705514efde1b58f79b596ce94f42`, including client-before-lease startup, unstarted-consumer cleanup, broker log-volume alignment, and orderly container recreation with retained topic UUID and SQL offsets.

The subsequent engine optimization avoids rescanning all pending windows/panes when the watermark is unchanged. Newly due late windows publish directly. Review checked finality, exact-end revisions, and the frozen `1dcc6557f80d9025111654ae5041d99c2bd6321752a6caa39db207103838fb99` inputs. Its verification evidence contains 15 ordinary methods, 8 real integration methods, and 131 strict mutation kills of 133 outcomes. These are historical source-bound results.

## Wire-type finding, 2026-10-20

`JsonNode.asText(default)` treats null and container nodes as the supplied default. The envelope decoder therefore accepted an explicitly supplied null, object, or array `recordType` as a measurement, despite the schema requiring a recognized string.

The decoder now checks the original node type before applying the omission default. Records without `recordType` remain valid legacy measurements. Supplied values must be `MEASUREMENT` or `PROGRESS`. Regressions verify malformed-type quarantine, checkpoint advancement without aggregate/identity mutation, and legacy omission compatibility. A real Kafka/PostgreSQL regression passed quarantine and a valid same-identity retry through restart. That maintenance verification passed 26 methods with zero failures, errors, or skips; see [status](STATUS.md).

## Measurement interpretation

The three-round quiet12 packet retains all 25 indexed raw files and original source/input identities. Review qualified two overbroad raw labels: recovery equality compares a defined semantic projection, and the SQL oracle checks aggregate/key/end/finality fields with positive revisions rather than independently deriving revision sequences. The [scope clarification](../evidence/reference-20261018-quiet12/scope-clarifications.json) preserves raw bytes and states those limits.

JMH retained all three forks' numeric samples but originally overwrote recordings within each variant. A separate capture-only repair verified six distinct PID-named recordings without changing application bytes. That later profiling window is not interchangeable with the earlier scores.

Current-source execution outcomes are recorded in verification status. Clean-checkout and repository release checks remain separate.
