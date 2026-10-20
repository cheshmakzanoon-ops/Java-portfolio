# Release criteria

These criteria define the project contracts and repository release gate. Historical evidence in the [acceptance matrix](acceptance-matrix.md) applies to its recorded source revision. A changed implementation requires fresh checks; a previous pass is not inherited automatically.

A release requires all mandatory criteria, current test results, verified demos, and explicit limitations. The release audit reads the identifiers below and fails on missing or incomplete acceptance rows.


## ClearLedger

- **P01-A1:** Domain/property and real-PostgreSQL tests demonstrate all stated posting and reversal invariants.
- **P01-A2:** Concurrent duplicates, tenant isolation, period closing, and crash/retry scenarios pass with preserved receipts.
- **P01-A3:** Reconciliation fixtures distinguish matched, unmatched, duplicate, invalid, and ambiguous rows without fabricated matches.
- **P01-A4:** Shadow replay and restore audit reproduce the source-derived balances.
- **P01-A5:** A clean checkout can run the scripted demo and workload; evidence includes actual commands, commit SHA, seeds, raw results, and limitations.

## DurableFlow

- **P02-A1:** Replay and state-model tests produce identical logical decisions across supported histories and restarts.
- **P02-A2:** Duplicate starts/signals, expired leases, and stale completions cannot create duplicate logical tasks or corrupt state.
- **P02-A3:** External-effect tests verify provider logs and expose uncertainty without unsupported exactly-once claims.
- **P02-A4:** Timer, parallel, cancellation, compensation, and definition-version race cases have executable evidence.
- **P02-A5:** The bounded workload and demo run from a clean checkout with raw measurements, failure timelines, and an operator runbook.

## QuorumKV

- **P03-A1:** Named Raft safety regressions and the seeded simulator pass without ignored events or disabled invariants.
- **P03-A2:** WAL, term/vote, truncation, snapshot-publication, and corruption tests cover explicit crash boundaries.
- **P03-A3:** Partitioned reads, unknown mutation outcomes, deduplication after restart, and compare-and-set histories pass the bounded checker.
- **P03-A4:** The real three-process fault demonstration preserves acknowledged data under the documented storage assumptions.
- **P03-A5:** Benchmarks are reproducible and honestly labelled; the README distinguishes tested evidence from protocol arguments, assumptions, and unsupported features.

## PulseStream

- **P04-A1:** Both original window algorithms agree with the independent oracle across generated boundary cases.
- **P04-A2:** Idle/resume and restart never move watermark backward or mutate a finalized window.
- **P04-A3:** Every prescribed crash leaves SQL results and checkpoints consistent; obsolete owner writes fail.
- **P04-A4:** Dedup scope, quotas, outbox retries and retention-gap behavior are tested and documented.
- **P04-A5:** Bounded workload report, demo transcript and recovery digests are reproducible.

## TenantShield

- **P05-A1:** Original parser/compiler/evaluator agrees with the reference interpreter and enforces resource bounds.
- **P05-A2:** Real OIDC verification and tenant binding defeat the specified adversarial requests.
- **P05-A3:** Roles, unknown attributes, explicit denies and policy publication have deterministic tested semantics.
- **P05-A4:** Post-revocation snapshots deny correctly; versioned cache and pooled connections never cross tenants.
- **P05-A5:** Protected-resource demo, concurrent workload and redacted audit evidence are reproducible.

## MatchCore

- **P06-A1:** Original indexed price-time book and independent reference model agree for generated histories.
- **P06-A2:** Priority changes, FOK, self-trade prevention and fixed-point risk preserve stated invariants.
- **P06-A3:** No pre-force result escapes; retry/recovery semantics hold at every crash boundary.
- **P06-A4:** Corrupt journals fail visibly, snapshots fall back safely, reads expose consistent sequences and admission remains bounded.
- **P06-A5:** Exact demo totals, replay digest and separately labeled core/durable workload evidence are reproducible.

## IndexForge

- **P07-A1:** Repeated ingestion of 10,000 identical records produces one logical copy per key; manifest replacement removes exactly the specified deleted records.
- **P07-A2:** At least 100 seeded kill/failure schedules across build, commit, pointer publication, and retirement yield either the previous valid generation or the complete new one; no mixed-generation response.
- **P07-A3:** Cross-tenant/denied-repository tests cover results, counts, facets, suggestions, explanations, cursors, and warmed caches, with zero unauthorized content disclosures.
- **P07-A4:** Held-out nDCG@10 is at least 0.75 and identifier MRR@10 at least 0.90 on the published corpus; report baseline, candidate, ablations, and per-query failures.
- **P07-A5:** On the documented reference machine, 100,000 documents and 30 search requests/second sustain p95 below 200 ms after warm-up, using at most 2 GiB configured heap; publish errors, GC, and CPU alongside latency.
- **P07-A6:** Invalid UTF-8, oversized files, archive expansion limits, path escape, symlink escape, disk-full, cancellation, and corrupt staging metadata fail explicitly while the previous generation remains searchable.

## ThreadLens

- **P08-A1:** Replaying one recording twice produces identical normalized aggregates; synthetic event fixtures prove inclusive/self counts, allocation weights, and window boundaries.
- **P08-A2:** The known CPU hotspot appears in the top three observed sampled methods; allocation and contention regressions identify their planted causes in at least six of seven controlled trials.
- **P08-A3:** At least 40 transformation cases preserve outputs and exception identity, including recursion, wide primitive returns, nested handlers, and synchronized methods; transformed fixtures pass JVM verification.
- **P08-A4:** Queue saturation, unwritable output, malformed config, failed transformation, unavailable events, and consumer exceptions leave the host workload functional while producing bounded failure/drop evidence.
- **P08-A5:** Reference-workload median throughput regression is at most 5% for the declared JFR-only configuration and 10% with the documented method allowlist; p99 regression is at most 10% and 15% respectively. Publish all trials.
- **P08-A6:** A 20-minute cardinality/slow-consumer stress run respects configured observer limits; report windows affected by drops as incomplete and test that fixture secrets never appear in normalized exports.

## ByteGuard

- **P09-A1:** All supported bytecode-shape fixtures produce valid CFG/frame states; property tests verify lattice laws, monotone transfers, and schedule-independent fixed points.
- **P09-A2:** Detect all 60 labeled positive cases and classify at least 57 of 60 negatives correctly; publish every result and the per-rule confusion matrix. These are corpus results, not field accuracy claims.
- **P09-A3:** Recursion terminates; dispatch, provenance, and global-budget limits behave distinctly; every budget-exhausted scan exits `3` with explicit unfinished coverage.
- **P09-A4:** A target class initializer containing a sentinel side effect never runs; archive bombs, corrupt entries, duplicate classes, and traversal paths cause bounded failure without external file writes.
- **P09-A5:** SARIF passes the pinned schema; witness locations resolve to fixture bytecode/source; unchanged findings keep fingerprints when unrelated methods are added.
- **P09-A6:** On the documented reference machine, a fixed 10,000-class workload finishes within 120 seconds using at most 2 GiB configured heap, or terminates explicitly as partial at its declared budget. Completion requires a successful full run on the supported benchmark corpus.

## RouteWeaver

- **P10-A1:** The independent validator and tiny exact oracle agree on complete bounded fixtures; invalid routes are rejected without trusting solver scores.
- **P10-A2:** Baseline and solver obey the same units, time semantics, and constraints; a candidate never degrades the valid incumbent for the same immutable problem/matrix/freeze/objective version under the defined lexicographic objective.
- **P10-A3:** Durable async execution survives restart, bounds CPU/queue use, records cancellation honestly, and rejects expired-worker/stale-input publication.
- **P10-A4:** The breakdown/urgent-job demo preserves completed work and applicable frozen work, audits any released commitments, respects actual cargo locations, displays unmet demand, and rejects publication of an outdated solution.
- **P10-A5:** The corpus, raw measurements, reference environment, score explanations, tests, and operational runbook are reproducible from a clean checkout.

## Repository

- **R-A01:** completeness: exactly ten catalog entries point to implemented projects; all P01–P10 acceptance items have evidence and no unresolved mandatory placeholder.
- **R-A02:** reproducible builds: a clean checkout builds with the wrapper on the pinned JDK. Every project builds independently. Linux CI passes; run the documented Windows/PowerShell and macOS entry-point checks when those environments are available and state any platform that remains unverified.
- **R-A03:** meaningful validation: required unit/property/integration checks ran and passed; deliberate foundation failure proved the harness can fail; final logs show no blanket test skips.
- **R-A04:** standalone demos: every catalog demo runs, checks its assertions, and terminates with the correct status. At least two different service projects run together without port or data collisions.
- **R-A05:** failure evidence: each project shows its specified failure, security, or adversarial scenario with a verifiable observed result; bounded tests are described with their bounds.
- **R-A06:** measurement integrity: every published performance claim links to raw results, workload, environment, and actual source identity. Unmet goals remain visibly unmet.
- **R-A07:** maintenance: pinned dependencies/actions/images, verified migration/recovery instructions, appropriate SBOM/notices, no real secrets or generated binary bloat in Git.
- **R-A08:** architecture: no cross-project production imports, no accidental dependency cycles, pure algorithm/domain modules where specified, and review boundaries respected.
- **R-A09:** presentation: root README and all project case studies tell a truthful technical story, links resolve, diagrams match code, and interview walkthroughs identify real implementations.
- **R-A10:** final handoff: no unintended uncommitted changes, final local/remote SHA and CI state reported, known limitations explicit, and resumption instructions present for any concrete blocked gate.

Performance claims require a declared workload, source identity, environment, raw results, and the specified repetitions. Preserve failed runs and unmet objectives. Ordinary correctness, integration, mutation, performance, vulnerability scanning, and hosted CI are separate outcomes.
