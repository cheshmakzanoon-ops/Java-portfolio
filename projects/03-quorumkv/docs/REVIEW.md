# Independent engineering review

A02 and the coordinator reviewed protocol, persistence barriers, snapshot transfer, client uncertainty and the independent history checker. A02 supplied a scoped final closure against the tested source and later re-reviewed the standalone process helper. [Current verification](../evidence/verification-current.json) passed 41 fresh methods on JUnit 6.1.3. Full workload measurements and clean-checkout release approval remain pending.

| Finding | Correction and executable evidence |
| --- | --- |
| Persistence acknowledgment released only one deferred event. | Drain FIFO until empty or another persistence barrier. A named schedule checks several reads and a tick without an unrelated second acknowledgment. |
| Partitioned correlated reads could permanently consume capacity. | Expire them with `UNAVAILABLE`; named partition/healing schedules verify progress. |
| Replacing an in-flight snapshot retained stale per-peer offsets. | Reset transfer state on compaction and reject stale responses. Two large snapshots and follower crash/restart cases check restart at zero and already-installed acknowledgment. |
| Timeout followed by pre-admission overload erased a client's unresolved mutation identity. | Retain identity until a definitive result. A timeout/overload/retry regression checks payload conflict and stable sequence. |
| History-checker memo keys conflated absent values and literal `null` bytes. | Use typed memo state; a concurrent PUT/DELETE/GET counterexample distinguishes them. |
| Long classpaths were truncated in process metadata, so a controller refused its own nodes. | Verify exact arguments, with a bounded Linux `/proc/PID/cmdline` fallback. Long-classpath and unrelated-PID refusal regressions pass. |
| Detached stopped nodes could remain zombies under container init; `onExit` timed out and cleanup abandoned other members. | Detect Linux Z/X states with a bounded read, poll within a deadline, and attempt every member even after one error. A separate up JVM exits before independent stop/restart; actual repeated catalog lifecycle checks also pass. |

The deterministic simulator additionally found the seed-27 apply-before-disk-ack and seed-417 rejected-vote election-deadline defects. Both have minimized named regressions. These tests and bounded schedules are evidence, not exhaustive protocol proofs.

A02's final process-helper review recorded production helper SHA-256 `eb03a0a355e12504aaec44342cfdf9147023d9838d4bf37f8742657eabb5d9c2` and process-test SHA-256 `f728d4bf5eaa6b9f9a42a8fdbc14d8f6700a5d8ca71c3e1f914c388c9b5beff5`. No new confirmed serious finding arose in that scope.

## Mutation investigation

All selected original Raft and Registers classes remain in scope with the original 70% threshold and mutators. The report has 262 killed, 80 survived, four timed out and eight uncovered out of 354: 74.01% strict kills. [The complete non-killed list](../evidence/mutation-survivors.json) preserves locations and transformations.

Survivors expose remaining test distinctions around election/heartbeat timing, boundary comparisons, redundant sends/cleanup, snapshot-transfer edges and exact retained-capacity accounting. Some mutations may be behaviorally equivalent under valid-state invariants; no blanket equivalence claim is made. Uncovered cases include small identity/leader getters and defensive deferred/install paths. The four timeouts affect loop/progress behavior and are penalized rather than counted as kills. No memory or runner errors are accepted as successful kills.

The final review still needs full measured resource behavior, workload output, operations artifacts and clean-checkout/remote checks. A numeric mutation threshold or a scoped source acknowledgment does not close those obligations.
