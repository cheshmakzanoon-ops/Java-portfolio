# Late Arrival reference study: quiet12

On 2026-10-18, the runner completed original/current three-fork engine JMH and three one-million-event durable rounds from 07:08:40 to 08:09:12 UTC. All six workload assertions passed in every round; the runner and scoped teardown exited zero. This is measured execution evidence, not final release approval or stable capacity.

- [Envelope](envelope.json): source, runner, readiness and baseline identities; terminal outcomes; rates; variability; measured boundaries.
- [Analysis](analysis.json): three-round metrics, raw JMH samples, and fork-mean statistics.
- [Raw index](raw-index.json): 25 verified files totaling 70,859,616 bytes, with original locators, hashes, sizes, and bounded GC-text summaries. Large artifacts remain outside Git under the indexed `.work/A04/runtime` paths; copied JSON inputs retain their original content.

The arithmetic mean rate is 876.493 events/s with sample CV 17.739%; total accepted events divided by total drain time gives 859.785 events/s. The fresh-engine mean throughput ratio is 6.646 with high between-fork variability. It does not establish a corresponding durable-service benefit. See [performance](../../docs/PERFORMANCE.md) for per-round results and exclusions.

Each durable run retained its workload JFR. The original JMH profiler reused a pathname, so numeric samples retain all three forks but only one recording per variant remains. The later [semantic addendum](../reference-20261018-quiet12-jfr-addendum/README.md) inspected all five retained recordings. A separate [capture-only check](../capture-20261018-quiet14/README.md) subsequently retained every fork recording under a different profiling boundary. Neither changes the original measured rows. The failed quiet10 attempt contributes no results here.

## Assertion scope

The [scope clarification](scope-clarifications.json) preserves original raw labels while defining what they actually verify. Recovery compares a **durable semantic projection**: watermark/duplicates, offsets/maxima, dedup entries, panes, nonfinal results, pending windows, and admitted keys. It excludes rebased activity/idle fields, run identity/limits, late counters, quarantine, outbox, and finalized results. It is not full serialized-snapshot equality.

The SQL oracle independently checks row count, window keys/end, aggregates, and finality, requiring `revision >= 1`. It does not derive exact revision numbers or sequences. These qualifications narrow the explanatory labels without altering raw measurements or source identities.
