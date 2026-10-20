# PulseStream runtime recertification: 18 October 2026

This packet records application correctness and artifact preparation before the quiet10 performance attempt. It is not a full performance result.

- [Summary](summary.json): 23 distinct methods (15 ordinary/property and 8 real integration), 131 strict mutation kills of 133 outcomes, preparation/discovery, and 14 Python controls.
- [Input drift](input-drift.json): two wrapper lines supplying the immutable Ryuk image are the sole aggregate fingerprint difference; application source was unchanged.
- [Cold payloads](cold-payloads.json) and [prepared payloads](prepared-payloads.json): 44 main classes, compared resources, and 6 JARs match the preserved artifacts byte for byte.
- [Prepared inputs](prepared-inputs.json): 129 runtime/classpath/direct-class hashes and launcher receipt, bound to source `2f7d0008…`.
- [Checkpoint](checkpoint.json): process/container observations and runner/readiness/baseline identities.

Cold integration completed at 06:47:55 UTC, mutation at 06:50:10 UTC, and preparation at 06:52:03 UTC. Test-report checks used fresh positive cutoffs. Repeated engine and preparation methods overlap the 23-method suite; they are not additional unique tests. JMH `-l` verified discovery without running a measurement iteration.

Original receipts, runtimes, targets, reports, and logs remain under `.work/A04/recert-20261018T064318Z/`. Historical failures and pre-optimization classes remain separate. Quiet10 failed at 06:56:23 UTC after its engine phases, on a permission-denied process-namespace read during dependency startup. Teardown succeeded; no full-million-event durable phase ran. Those outputs cannot be combined with a later study.

The process-supervision repair is recorded separately: [22 controls](harness-controls.json), [actual container identity/teardown checks](harness-functional.json), [unchanged runtime hashes](harness-validation.json), and the [original failed attempt](quiet10-failure.json). Three real ownership observations and scoped shutdown passed without fallback. No product-source change or full workload ran during that repair. The subsequent [quiet12 study](../reference-20261018-quiet12/README.md) repeated all required phases.
