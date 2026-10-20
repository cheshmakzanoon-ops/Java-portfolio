# Performance and relevance evidence

Measurements apply to a specific artifact, corpus, configuration, and runtime boundary. Correctness tests on newer source do not refresh a historical benchmark.

## Reference workload

The documented target is 100,000 generated UTF-8 source documents across three tenants, 30 scheduled searches per second, p95 below 200 ms after warm-up, and at most 2 GiB configured heap. The fixed seed is 7007; the published corpus contains 22,266,651 source bytes with tenant counts 33,334 / 33,333 / 33,333.

The harness warms 150 application requests and runs three fixed-rate measurements. Latency begins at scheduled arrival, including executor queuing. Eight bounded request workers record errors, queue peak, process CPU, GC, and raw latency samples. The measured boundary includes shared application search, generation leases, authorization, facets, explanations, and the bounded warm response cache. It excludes HTTP transport.

Use `projects/07-indexforge/scripts/benchmark.sh` only on an otherwise quiet reference host. Smaller `INDEXFORGE_DOCUMENTS` or `INDEXFORGE_SECONDS` values are exploratory runs and do not satisfy the reference target. Record CPU/cgroup limits, OS/JDK, heap, artifact digest, corpus bytes, concurrency, and colocated processes alongside results.

## Historical frozen baseline

The corrected build-24 baseline reference used the copied CLI artifact with SHA-256 `6c2ae0c414013a55e8d83bcfd9e65707ccfb984fa684054bf83910f08c4d564d`, `BASELINE/CODE_HIGH`, and a 2 GiB heap. Baseline bypasses source-aware feature weights and uses content BM25.

| Run | Requests | Offered rate | Errors | p95 |
| --- | ---: | ---: | ---: | ---: |
| 1 | 900 | 30/s | 0 | 4.727653 ms |
| 2 | 900 | 30/s | 0 | 6.977346 ms |
| 3 | 900 | 30/s | 0 | 2.607306 ms |

Raw evidence is retained under `evidence/performance/reference-build24-attempt1/`. The initial preparation and explicit build-23-to-24 index reuse are recorded in adjacent evidence files. These results establish the frozen baseline's warm-cache application behavior on that machine; they do not certify the later source parser, learned candidates, or HTTP end-to-end latency.

## Relevance outcome

The published corpus has 80 reviewed queries with a 40/20/20 development/validation/held-out split. The declared targets are held-out nDCG@10 of at least 0.75 and identifier MRR@10 of at least 0.90. Existing final measurements were:

| Configuration | nDCG@10 | Identifier MRR@10 | Decision |
| --- | ---: | ---: | --- |
| Original baseline | 0.6012721858 | 0.8833333333 | Retained |
| First selected candidate | 0.6267961004 | 0.8000000000 | Rejected by guard |
| Later fixed candidate C1 | 0.6594266978 | 0.8000000000 | Rejected by guard |

No configuration met both targets. The selected candidate's greater nDCG did not justify its lower identifier MRR. Frozen labels, failed fits, ablations, seals, and repeated-test exposure remain recorded under `evidence/relevance/` and `fixtures/`. These are authored-corpus results, not a universal search-quality claim. Further tuning requires development/validation evidence and a separately declared evaluation protocol.
