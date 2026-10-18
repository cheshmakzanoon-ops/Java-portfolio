# Executable invariants

| Invariant | Enforcement | Evidence |
|---|---|---|
| At most one leader per term | Persistent single vote; candidate log freshness; correlated vote replies | `Simulator.invariants`, recorded seeds, direct vote regression |
| Committed prefixes agree | Append log matching; never truncate committed entries; current-term majority commit | Per-event simulator entry comparison; heartbeat/divergence regressions |
| Application is monotonic and never beyond commit | Apply only after commit persistence acknowledgment; replay only persisted commit | Per-event simulator checks; seed27 named crash regression |
| Equal committed commands produce equal results | Deterministic ordered state machine and replicated sessions | Simulator result comparison; jqwik commute/retry property |
| Append acknowledgments imply stable entries | Persist effect gates successful replies | Direct persistence barrier regression; WAL crash tests |
| Old-term replication alone does not advance commit | Explicit current-term predicate | `oldTermEntriesCannotCommitWithoutCurrentTermMarker` |
| Empty heartbeats cannot remove suffixes | Truncation only at conflicting supplied entry | Named heartbeat regression |
| Delayed rejection cannot regress established progress | Request lookup and monotonic match; next bounded by match+1 | Named delayed-rejection regression |
| Isolated former leader cannot read locally | Marker gate + fresh majority + safe applied index | Partition simulation and actual process demo |
| Pending reads cannot permanently exhaust capacity | Per-read deadline cleanup | Saturate/partition/heal regression |
| Identity retries cannot mutate twice | Persistent seq/digest/result, snapshot sessions | Register tests/property and restart demo |
| Complete corrupt records never silently disappear | CRC32C on entire record; final complete record treated as corruption | Interior/final corruption tests |
| A torn unacknowledged tail is distinguishable | Only incomplete header/body tail truncates; complete CRC failure aborts | Tail truncation regression |

No finite test schedule proves universal safety. The checker separately evaluates invocation/response intervals against an independent register/CAS model, considers timed-out mutations potentially applied, and returns INCONCLUSIVE when its search budget is exhausted. INCONCLUSIVE is never counted as a correctness pass.

The extended old-term counterexample repairs a40-entry prior-term suffix, acknowledges exactly the first32 old-term entries on a second member (a genuine majority prefix), asserts commit remains0, then acknowledges current-term marker41 and asserts commit41. This separately exercises the current-term rule rather than relying only on malformed/stale-response rejection.
