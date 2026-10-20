# Review findings

The review boundary covers bytecode transformation, resource-only frame resolution, bounded JFR normalization/aggregation, timing import, shutdown behavior and offline report safety. Results apply to the recorded source revision; functional success does not establish acceptable observer overhead.

## Resolved defects

| Finding | Correction and regression |
| --- | --- |
| Shutdown abandoned accepted timing entries without reporting the lost tail | Bounded drain, explicit discarded/incomplete/health counters and packaged-JVM conservation tests |
| Absolute ceilings appeared to be actual configured limits | Separate absolute and active limits; a two-slot/100-ID report round trip |
| A missing histogram summary could make a bounded raw prefix appear complete | Reconcile producer, reservation, observed and discarded totals; missing observations remain incomplete |
| Truncated or duplicate status fields could hide coverage loss | Versioned complete status, required fields and duplicate rejection |
| Concurrent recoverable faults lost counter increments | Atomic rare-path accounting and concurrent injected-fault regression |
| A producer could publish after shutdown finalized output | Bounded producer quiescence and sealed admission; a real `System.exit` child preserves application result 42 while reporting an unfinished exit |
| Method selection overflow was omitted from reports | Bounded reason parsing and a real two-method/one-ID JSON/HTML test |
| Distinct imported IDs could overwrite the same displayed method identity | Reject duplicate/empty registry identities before histogram aggregation; preserve distinct valid methods and their totals |
| Existing status/registry/summary artifacts could be combined with new timing data | Reject nonempty or symbolic-link output directories before installing transformations; independent JVMs verify host progress and unchanged old artifacts |
| TSV row bounds were checked only after allocating the entire line | Enforce character limits during reading, including unterminated input; retain CR/LF/CRLF compatibility |

The first seven findings have source-bound historical verification in [the v3 certificate](../evidence/repair-verification-v3.json). Current development checks and their precise scope are recorded in [STATUS.md](STATUS.md). Historical mutation survivors remain visible; no blanket equivalence claim is made.

## Performance remains unresolved

The completed first overhead study failed multiple median limits. Historical cause detection and a 1,202.669-second boundedness run passed under the subsequent declared configuration, while preserving event loss and incomplete windows. Neither establishes overhead acceptance for that configuration. The complete 84-JVM remeasurement remains required; see [performance protocol](PERFORMANCE.md).
