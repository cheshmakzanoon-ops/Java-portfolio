# TenantShield execution status

Current-instance correctness/security, asserted demo/reset and the full quiet reference workload have passed. **Independent measurement review, clean-checkout reproduction and repository-wide/remote-CI release gates remain open.** No complete repository release or production guarantee follows.

Executable source is `working-tree-sha256:832dfc3ccd0bf9399528721f8a31710c4b0197129b0b183aadcd5a1afeb92c11`. Java/POM/scripts/fixtures remain frozen; this handoff changes analysis evidence/docs only. No A05 build, service or container is running. Coordinator owns all Git/shared writes.

| Acceptance | Current-instance result | Evidence |
| --- | --- | --- |
| P05-A1 | PASS within tested scope | Original bounded language and 2,500 generated reference comparisons; core 343/348 lines and 267/291 branches clears 85%/75% gates. |
| P05-A2 | PASS within tested scope | Real OIDC verification; signed identity/audience/time negatives; server-side membership binding and raw body limits. |
| P05-A3 | PASS within tested scope | Deterministic RBAC/ABAC/UNKNOWN/deny behavior; expected-prior immutable publication and atomic monotonic rollback. |
| P05-A4 | PASS within tested scope | Older snapshot boundary, 30 new post-revocation denials, 160 concurrent version-specific readers, 100 alternating pooled reads and cache/failure tests. |
| P05-A5 | Full execution passed; measurement review pending | Two 21-assertion protected demos with reset preservation, full seed, three 100k authenticated HTTP runs and both three-fork JMH methods. |

## Correctness and reviewed source lineage

Full integration verification passed 18 unit plus nine real PostgreSQL/Keycloak methods at 02:02:23 UTC under actual JUnit 6.1.3, with zero failures/errors/skips, on source `4bd4397…`. The exact reports are in `correctness.json` and `correctness-methods.json`; earlier 6.0.3 evidence remains separate. A04 closed its scoped source review and prior audience/subject/rollback findings. No production Java changed during subsequent image/cache/metadata maintenance.

Selected mutation analysis killed 58/58 original top-level PolicyEngine mutants, with no uncovered mutations or runner errors. Parser/adapters were not selected. The historical source/framework and first failed 66% result remain preserved; no hard branches were excluded to raise the score.

The real PostgreSQL concurrency regression exposed SQLSTATE 40001 when an old-snapshot audit FK lookup followed a tenant epoch updated under FOR UPDATE. The same minimal schedule committed under FOR NO KEY UPDATE. That repair retained writer serialization, RR isolation, mandatory audit and unchanged concurrent assertions; all nine integration methods then passed twice. CASE_STUDY and raw logs retain the failure and proof.

## Lifecycle and runtime preparation

Direct seed counts verified 50 load tenants, 10,000 immutable versions, 1,000,000 role rows, 990,000 edges, 50,000 resources and 50 active pointers. Both 21-assertion demo runs passed. Reset removed only Harbor/Orchard and preserved all workload rows. The protected dependency probe passed 403 → 503 → 403. See `preparation.json`, `demo.json` and `protected-outage.json`.

Compose/Testcontainers pin the exact tested PostgreSQL 18.3 and Keycloak 26.8.1 digests. A successful unchanged-source verify binds all three executable JAR hashes. Extraction verifies each packaged runtime file and rejects stale, modified, extra or symlink entries; eight actual probes passed. No-Maven mode prevents rebuilding during the reference window.

Quiet attempt 5 failed before JMH measurement because extraction omitted archive-root BenchmarkList/CompilerHints. Its 58.434-second failed reservation, logs and manifests remain in `reference-attempt-5-failed.json` and `.work/A05/failed-reference-5/`. The repair passed 18 unit methods at 03:46:35 UTC, real discovery of both methods and eight integrity probes. No benchmark assertion was weakened.

## Completed full quiet reference

Reservation 6 held all three build locks, found no unrelated active JVMs/application containers at entry/completion and exited zero after 814.080 seconds, from 03:53:56.096602 to 04:07:30.175597 UTC. Both JMH methods completed three forks with three warmup and three measurement iterations each. Each fresh HTTP client process performed 1,000 excluded warmup requests, then 100,000 measured requests with 16 clients and pool size eight. Every run had 20,000 denials, zero errors, 700,150 application prepared statements, 50 cached policies and maximum client in-flight 16. No repetition was discarded.

Throughput declined 593.275 → 504.958 → 367.263 requests/s: sample CV 23.32%, first-to-third decline 38.10%. The continuing backend and growing audit history limit independence. Stable capacity and the slowdown cause are not established. Per-run p99 values were 133.285 / 121.010 / 176.966 ms. Separate JMH and server JFR observations retain their different boundaries.

The packet under `evidence/reference-20261018/` contains the small hash/stat envelope, CSV, JMH summary and artifact receipt. Original result JSON remains one directory above. All six recordings and original logs/results are archived under `.work/A05/reference-20261018-quiet6/`, with 22 size/SHA256 identities. Owner analysis recomputed HTTP counts/rates/statistics and JMH histogram means. Existing FlightSummary JSON was retained without a fresh JFR CLI recount. Product source/artifact hashes are unchanged.

## Remaining work and pause

- Independent review of final metrics, raw hashes, source/artifact lineage and performance claims; no unreceived approval is claimed.
- Coordinator clean-checkout, global/hosted-CI and Git publication validation, followed by the final release decision.
- All owned paths write-paused after handoff. Preserve the seeded volume and every failed/successful artifact; no experiments are scheduled.

Pre-analysis chronological docs remain under `.work/A05/reference-20261018-quiet6/docs-before-analysis/`. Failures remain evidence rather than being overwritten with success.

A04 final measured-artifact review is CLOSED with no blocking finding. All22 raw-file hashes, actual three JARs, full HTTP outcomes, variability and JMH summaries match. See REVIEW.md for scope and pre-closure packet identity. Coordinator clean-checkout/global/CI release checks remain separate.
