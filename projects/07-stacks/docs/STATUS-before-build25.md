# Historical validation before build 25

This summary describes older artifacts. It is retained for provenance and does not certify current source.

| Boundary | Recorded result |
| --- | --- |
| Corrected build 24 | 65 test methods; 98 of 98 selected mutations killed. |
| First final relevance study | Baseline nDCG@10 0.6012721858 and identifier MRR@10 0.8833333333; selected candidate 0.6267961004 and 0.8000000000. Quality target failed; baseline retained. |
| Frozen baseline performance | 100,000 documents, three 900-request runs at 30 offered requests/s, zero errors; p95 4.727653 / 6.977346 / 2.607306 ms. Application boundary excludes HTTP. |

The original source/artifact bindings, complete outcomes, and unsuccessful experiments remain under `evidence/certification-build24.json`, `evidence/relevance/`, and `evidence/performance/`. See [current status](STATUS.md) for later verification and [performance](PERFORMANCE.md) for the workload and measurement limits.
