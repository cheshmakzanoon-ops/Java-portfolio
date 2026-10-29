# Protocol and recovery invariants

These invariants describe the stable state exposed after a matching persistence acknowledgment. While persistence is pending, prospective term/log/snapshot state may exist inside the transition owner, but dependent messages, application and client results remain held.

| Invariant | Enforcement | Executable regression |
| --- | --- | --- |
| At most one leader per term | Persistent single vote, candidate log freshness and correlated vote replies | `RaftTest.voteWaitsForFlushAndRestrictsLog`; per-event simulator leader map |
| Committed prefixes agree | Log matching, committed-truncation refusal and current-term majority commitment | `RaftTest.actualOldTermMajorityCannotCommitWithoutCurrentTermMarker`; simulator prefix/result comparisons |
| Application never exceeds known commitment or decreases across restart | Apply only after commit persistence; replay only persisted commit | `RaftTest.commitApplicationSurvivesCrashOnlyAfterDiskAck`; seed-27 schedule |
| A successful append response implies stable entries | Persist effect holds dependent response | Direct follower barrier checks and WAL fault tests |
| Empty heartbeats preserve retained suffixes | Only a conflicting supplied entry can truncate | `RaftTest.heartbeatNeverTruncatesAndConflictingSuffixWaitsForDisk` |
| Delayed replies cannot reverse established replication progress | Peer/term/correlation lookup and monotonic match position | `RaftTest.delayedRejectionCannotUndoSuccessfulProgress` |
| An isolated leader cannot serve a register read from its local map | Current-term marker, fresh majority and safe applied index | Partition schedules and the actual TLS-process fault demo |
| Slow/lost snapshot acknowledgments do not restart healthy progress | Preserve outstanding request identity; duplicate chunk acknowledgment | `SnapshotTransferTest.snapshotProgressSurvivesRepliesSlowerThanHeartbeatAndLostChunkAcknowledgments` |
| Partial snapshot data never becomes active | Complete decoding, coordinate validation and persistence barrier | Chunked, corrupted, interrupted and conflicting transfer cases |
| Equal committed commands produce equal register results | Deterministic integer/byte transitions and persistent receipts | Simulator application-result comparisons and generated register histories |
| Retry identity cannot silently change on timeout, overload or restart | Latest sequence/digest/result; unknown outcomes retain the command | `MutationIdentityTest`, snapshot/restart deduplication and transport close tests |
| Same-coordinate WAL entries retain their actual payload | Full semantic entry comparison when finding shared delta prefixes | `JournalTest.sameCoordinatesDoNotHideChangedUncommittedPayloadOnRestart` |
| Granted votes and committed entries are immutable | Storage transition tripwires before writing | `JournalTest.committedCommandsAndVotesCannotBeRewrittenWithinTheirTerm` |
| Failed persistence cannot be followed by another append behind uncertain bytes | Poisoned journal writer until close/recovery | `JournalTest.failedPersistencePoisonsTheWriterUntilItIsClosedAndRecovered` |
| Fatal state stops already queued protocol work | Execution-time state check, not only admission check | `NodeExecutorTest` protocol-failure and disk-failure schedules |
| Shutdown does not release storage beneath a writer, including after transport teardown fails | Close admission, stop protocol execution, finish disk work, release lock last; retain lock on timeout | `NodeExecutorTest.shutdownFinishesAdmittedDiskWriteAndAbandonsQueuedProtocolWork`; both `NodeMainLifecycleTest` schedules |
| A probe never changes durable or timer state | Pre-vote requests are answered without adopting their term, recording a vote or resetting the election deadline | `RaftHardeningTest.aPreVoteIsGrantedWithoutAdoptingTheTermVotingOrRestartingTheTimer`, `aPreVoteWithAnAbsurdTermCannotMoveAnyNode` |
| An unreachable member cannot raise the cluster's term, and a stranded leader stops leading | Probe before campaign; check-quorum window | `HardenedSimulationTest` hardened scenarios with their classic controls |
| A deposed leader does not campaign again at once | Fresh election deadline on leaving leadership | `RaftHardeningTest.aDeposedLeaderWaitsOutAFreshElectionTimeoutBeforeCampaigning` |
| Leadership transfer never lets a lagging member take over, and writes resume if it fails | Hand-off only offered at `match == last`; one-second abandonment | `RaftHardeningTest` transfer tests; `HardenedSimulationTest` lagging and unreachable targets |
| Request and unauthenticated connection bounds survive concurrency | Atomic client permits; TLS authentication deadline | `TransportLifecycleIT` concurrent admission/close and 64 stalled sockets |

For a stable log, `snapshot.index <= applied <= commit <= snapshot.index + log.size`; retained entries are contiguous and nondecreasing in term. During installation, the prospective snapshot can be ahead of active `applied`, but its data and dependent success remain unavailable until persistence completes. The simulator checks application monotonicity, election safety, committed-prefix agreement and equal application results after events.

The extended simulator uses 1,000 deterministic seeds with 180 fault actions each. Mutations use distinct bounded client identities so the workload continues exercising state changes instead of degenerating into repeated identity conflicts. After healing, each seed must commit a fresh command and converge its value on all members; merely observing a leader is insufficient.

The independent [history checker](../raft-verification/src/main/java/io/github/cheshmakzanoon/portfolio/majority/HistoryChecker.java) models register/CAS operations using recorded invocation/response intervals. A timed-out mutation may be omitted or may take effect after its timeout; completed operations retain their real-time precedence. Search is bounded, histories over 62 calls are inconclusive, and exhausted search returns `INCONCLUSIVE`, never a pass. Named absent-versus-literal-`null` and stale-read counterexamples preserve distinctions in its memo state.

No finite simulator, process test or bounded history search proves universal Raft correctness. There is no lease-read, dynamic-membership or Byzantine guarantee. Pre-vote, check-quorum and leadership transfer ([architecture](ARCHITECTURE.md#liveness-hardening-pre-vote-check-quorum-and-leadership-transfer)) change when a member campaigns, never whether a vote is granted, so the table above holds in both option sets; the simulator runs both. Liveness additionally depends on a timely communicating majority and available storage capacity.
