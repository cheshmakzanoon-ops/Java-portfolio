# Quiet Gatehouse reference — 2026-10-18

The completed quiet6 protocol ran both three-fork JMH methods and three full100000-request authenticated HTTP client processes. This is distinct from the preserved quiet5 failure.

- `measurement-envelope.json`: actual reservation/environment, exact results/summary variation, three-artifact receipt, six profile identities, raw-file hashes and limits.
- `system-runs.csv`: all three unrounded run measurements, server CPULoad/GC/sample-allocation observations with separate boundaries.
- `jmh-summary.json`: full-fork sample-time scores, percentiles, reported errors and real allocation-profiler values; original unmodified JMH histogram JSON is `../jmh.json`.
- `verified-artifacts.json`: exact locally verified executable JAR hashes; not remote signed attestation.

Original result JSON remains one directory above. Immutable local raw copies, including all six JFR recordings and complete logs/results, are retained under `.work/A05/reference-20261018-quiet6/` and indexed by SHA256/size in the envelope. Failed attempt5 remains `.work/A05/failed-reference-5/`.

The same server/database persisted across fresh client processes. Throughput declined each run, and no stable capacity or causal explanation is established. Read PERFORMANCE.md before comparing these figures. Independent measurement review and clean/global/remote release gates remain open.
