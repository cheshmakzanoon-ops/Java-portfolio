# Correctness and ownership review

The review covers all 32 original production classes across the seven implementation modules, plus four extracted ownership/validation components. [Architecture](ARCHITECTURE.md#source-boundaries) links every source; [invariants](INVARIANTS.md) ties the contracts to executable counterexamples.

## Runtime and instrumentation

| Trigger and defect | Correction and regression |
| --- | --- |
| Live collection stops while an embedding producer continues offering events; the unsealed drain can remain active, and a join timeout previously returned as success | EventProcessor owns sealed admission, a bounded drain and explicit termination failure. Latch/clock-controlled schedules cover accepted tails, a blocked observer, exact deadline boundaries, late offers and cleanup retry |
| An observer closes its own controller, or races an external closer | Terminal state is published when the consumer finishes; no lifecycle lock is held across join. A real controller callback closes, returns, and permits an externally joined snapshot |
| Dump fails after stop; cleanup was skipped | Stop-and-dump preserves the original failure while always attempting close. Existing-destination fixtures preserve prior bytes and verify sealed admission/profile availability |
| Normalizing a path masks a symlink/../ escape outside the directory budget | Dumps validate the raw direct parent against the approved root; roots reject symlink ancestry. Escape and valid ./root spelling have named regressions |
| A depth-limited walk silently ignores a large unvisited subtree | Both output scans reject a directory at the unvisited depth boundary; a sparse 300-MiB depth-nine fixture proves the bound |
| Startup enables publication before all cleanup owners are established, then hook/transformer setup fails | TimingSession owns staged installation and rollback. An activation barrier prevents a failing consumer from being followed by re-enablement; failure fixtures verify primary/suppressed errors and terminated owned consumers |
| The same agent is declared twice, replacing the global sink or disabling a healthy first observer | Premain claims one startup attempt before touching configuration/probe state. Packaged JVMs test repeated same/different output directories and preserve all 10,000 first-owner exits |
| Hierarchy resources are unbounded or claim a different class identity | Bound resource/target bytes and validate names before frame resolution. Oversized, unavailable, cyclic and wrong-identity fixtures skip safely; 48 supported cases retain output/exception identity under JVM verification |

## Analysis, artifacts and tooling

| Trigger and defect | Correction and regression |
| --- | --- |
| A trie fills midway through a novel stack and stops counting a known method later in that sample | Separate method accounting from trie insertion; a retained hotspot keeps its exact inclusive/self count under exhausted prefixes |
| Missing sample stacks or clipped duration distributions appear in complete retained windows | Coverage remains incomplete with distinct counters; sample, monitor, park and selected-timing clipping cases are checked |
| Finite paired inputs overflow/underflow during division and silently produce nonfinite statistics | Compute ratios in log space and reject nonfinite output; preserve independent numeric oracles and extreme finite-value fixtures |
| A caller mutates a published profile or map order scrambles a timeline | Freeze sorted maps and flame children; snapshots preserve chronological order and remain unchanged after later aggregation |
| Untrusted report JSON supplies inconsistent denominators, negative totals, duplicated fields, deep structures or excessive cardinality | ReportValidator and bounded JSON parsing enforce coordinates/counts/quantiles/bounds before use; semantic corruption fixtures test the actual JSON entry point |
| Offline output implies selected timings share the JFR window, or a settings-only manifest proves producer platform identity | Explain separate collection intervals and analyzer-only platform metadata; compare only matching declared settings |
| Parent interruption/timeout leaves an experiment JVM or workload thread running | ChildJvm confirms bounded termination and preserves primary/suppressed failures; workload/stress finally blocks stop their owned threads. Real JVM timeout/interruption and deterministic failed-confirmation fixtures exercise cleanup |
| A misspelled smoke flag can launch the complete overhead protocol | Reject unknown options before creating output or starting any JVM |
| Configuration/settings size checks happen before an unbounded read, or duplicate/empty settings look authoritative | Bounded UTF-8 reads, duplicate-key detection and explicit nonempty declarations; malformed/oversized fixtures reject |
| An evidence helper accepts stale reports or counts timeout detection as a kill | Delegate to fresh repository gates, retain all non-killed outcomes, require explicit new output paths and preserve historical certificates |
| Wide tables/deep stack trees overflow narrow screens | Separate scroll regions, visible guidance, keyboard navigation and native details; actual desktop/mobile Chromium checks cover standard/differential reports without remote requests |

Earlier fixes remain active: bounded timing-prefix output, producer/reservation/summary reconciliation, complete status fields, concurrent fault counters, unique imported identities, incremental TSV limits, nonempty-output refusal and explicit selection-overflow evidence. Their original source-bound results remain historical.

## Certification and limits

Current commands, source identities, test counts, per-module strict mutation scores and browser receipts belong in [STATUS](STATUS.md). Mutation scope includes 19 critical production files across six modules and retains 70% gates. Survivors, uncovered branches and timeouts remain visible and penalized; no universal equivalence claim is made.

The first full overhead study failed its acceptance limits. Historical cause detection and 1,202.669-second stress evidence do not establish overhead acceptance for this revision. The full 84-JVM remeasurement and long stress/cause studies were not rerun during this review. Clean-checkout/hosted/cross-platform release gates remain separate obligations.
