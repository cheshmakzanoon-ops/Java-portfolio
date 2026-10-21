# Observation and lifecycle invariants

The guarantees below apply to supported Java 25 HotSpot/Linux behavior and the explicit storage/JVM assumptions in [operations](OPERATIONS.md). They describe observed coverage, not complete application execution or acceptable overhead.

| Invariant | Enforcement and regression |
| --- | --- |
| Each JVM has one startup observer owner | First-attempt claim in Premain; duplicate-agent packaged JVMs preserve the first sink for identical and different directories |
| Probe publication starts after installation owns every resource | TimingSession installation order and activation lock; hook/transformer failure fixtures verify rollback and disabled publication |
| Application arguments/return objects are never retained | Primitive TimingBuffer fields and TimingProbe signature; privacy fixtures inspect normalized exports |
| A producer never waits for consumer progress | Bounded reservation attempts in TimingBuffer/EventQueue; saturation and retained-payload tests |
| Sealed admission cannot be reopened by late publication | Atomic reservation seal and terminal processor state; accepted-tail and shutdown-race schedules |
| Accepted timings remain accounted or visibly unknown/lost | Quiescence, sealed reservations, bounded drain and reconciliation; real System.exit fixture and timing importer conservation tests |
| No mutable consumer statistics are read after a timed-out join | TimingSession omits unavailable producer counts and marks status incomplete; incomplete/missing-status import checks |
| JFR snapshots require a terminated analysis owner | EventProcessor terminal barrier; stalled observer rejects snapshots until successful cleanup |
| Callback-triggered close can finish and be externally joined | No lock across join; real controller self-close regression |
| Periodic disk checks depend on time, not event count | Injected monotonic clock; 1,000-event fixed-clock cadence regression |
| Dump destinations stay directly inside the validated root | Raw-parent checks and root identity validation; symlink/../escape and direct ./root fixtures |
| A scan never treats an unvisited subtree as zero usage | Entry/depth limits reject hidden depth-nine content |
| Consumer and recoverable linkage failures retain host progress | Bounded failure counters/incomplete windows; observer/probe fault fixtures and packaged startup JVMs |
| Method totals survive trie exhaustion | Separate method/trie passes; a retained hotspot remains counted below an unretained prefix |
| Inclusive samples count recursion once per method; self counts the leaf | Independent stack oracle and 48 supported transformation cases |
| Windows are half-open and deterministic under replay | Boundary fixtures and exact repeated recording import |
| Lost/unknown data cannot establish zero activity | Capability flags, missing-stack/loss/overflow/clipping counters and incomplete windows; missing proportions stay null |
| Snapshot maps/children cannot change after publication | Immutable sorted Profile/Flame snapshots and mutation/chronology regression |
| Paired statistics cannot silently become NaN/infinity | Log-domain ratios and finite result validation; independent numeric oracles and extreme finite-input fixtures |
| Report denominators and bounds are checked before use | ReportValidator rejects forged coordinates/counts, duplicate JSON fields, invalid histograms, oversized/deep/cardinality violations |
| Dynamic report text cannot become executable markup | Escaping and restrictive CSP; hostile identifiers survive as literal text in structural and Chromium tests |
| Interrupted experiments cannot leave owned workers/JVMs behind | ChildJvm termination/confirmation, workload finally blocks and stress cleanup; actual child timeout/interruption and worker lifecycle tests |
| Evidence describes the selected fresh run | Repository test/mutation gates and evidence collector require the run start timestamp; existing certificates are never replaced implicitly |

The fixed ceilings are 65,536 live event slots / 64 MiB conservative payload, 10,000 method IDs, stack nodes and allocation subjects, 128 frames, 256-character identifiers and 900 windows. Timing histograms retain 128 selected-method subjects; monitor and park histograms retain 64 each. Bounds are independent, and overflow counters identify which surface lost coverage.

The ring/queue consumer is single-owner. Events become visible only after publication sequence advancement; reclaim happens before an embedding callback can throw. A reserved slot whose producer has not published can prevent later reservations from draining. Shutdown deadlines expose this as an incomplete tail rather than pretending the slot was consumed.

Fatal VM/resource failures, invalid agent installation, dynamic attachment, unsupported loaders/modules and arbitrary native/JVM corruption are outside the recoverable host guarantee. JFR decoder/native allocations and filesystem behavior are not fully controlled by aggregate bounds. [STATUS](STATUS.md) separates current correctness checks from historical stress/cause results and the failed overhead study.
