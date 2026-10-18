# Completed quiet12 PulseStream study

The coordinator completed the entire original/current3fork JMH plus three prescribed1m-event durable rounds in one quiet reservation,07:08:40–08:09:12UTC on2026-10-18. All six workload assertions passed in every round; runner exit0 and scoped teardown succeeded. This records measured execution, not final release approval or stable capacity.

- [Envelope](envelope.json): source/runner/receipt/baseline identity, terminal outcome, rates, sample variability and explicit boundaries.
- [Analysis](analysis.json): exact three-round metrics and three-fork raw JMH samples/fork-mean statistics.
- [Raw index](raw-index.json):25verified original files/70,859,616bytes, exact local locators/SHA256/sizes plus bounded GC-text summaries. Large GC/JFR/console artifacts remain under the indexed ignored `.work/A04/runtime` paths; they are not embedded in Git. Copied small input files retain their original JSON content.

The arithmetic mean rate is876.493events/s with sample CV17.739%; aggregate3m/summed drain is859.785events/s. Observed current/original fresh-engine mean ratio6.646 is a microbenchmark comparison with high between-fork CV. It does not establish durable causal benefit. Each durable run retains a full workload JFR. JMH numeric data retain all3forks, but the profiler reused a pathname and only one JFR remains per variant. Semantic JFR inspection awaits root's Java grant; no complete per-fork recording coverage is claimed. Quiet10 remains a failed earlier attempt and contributes no rows here.

See [PERFORMANCE](../../docs/PERFORMANCE.md) for per-round tables and measured exclusions. Postprocessing performed only light JSON/SHA/text work after measurements while another functional/stress window ran; no Java/JFR parser/Maven/Docker/new workload was launched.

## Qualified assertion scope

[Scope clarification](scope-clarifications.json) distinguishes the raw output labels from the actual checks. The original strings “full recovered state digest” and “all published window fields” remain unchanged in copied JSON. Recovery compares a **durable semantic-projection digest**: watermark/duplicates, source offsets/maxima, dedup entries, panes, nonfinal results, pending windows and admitted keys. It excludes rebased activity/idle fields, run/limits, late counters, quarantine, outbox and finalized results; it is not byte-for-byte full snapshot equality. The SQL oracle independently checks key/end/count/sum/minimum/maximum/finality and row count, and requires **revision >=1**. It does not independently derive exact revision numbers or their sequence; revisions are not included in its result digest. These qualifications change the explanatory scope, not the passed raw measurements or source identity.
