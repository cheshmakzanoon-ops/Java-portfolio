# Job-readiness engineering pass — 21 October 2026

This continuation starts from `cd3d25972d81dcfbd174d92fc6b64a9dd88acd75` and commits verified milestones directly to `main`. It closes concrete implementation, authorization, configuration, and build defects, and gives a hiring reviewer reproducible behavior to inspect. The [execution receipt](execution/job-readiness-20261021.json) records the final source identity, commands, verification scopes, and remaining limits. It is separate from the repository's mandatory release certificate.

## Changes a reviewer can follow

| Area | Problem corrected | Resulting behavior and evidence |
| --- | --- | --- |
| DurableFlow inspection | A viewer's GET could schedule durable work and wait on writer row locks. | Read-only repeatable-read inspection returns committed state with values reconstructed from the same history version. A bounded history-page API retains an explicit snapshot boundary, checks tenant ownership on every page, and rejects incomplete or noncontiguous persisted history. [Inspection evidence](../projects/02-durableflow/evidence/inspection-hardening-20261021/validation.json) and [current status](../projects/02-durableflow/docs/STATUS.md). |
| DurableFlow transaction tests | A scheduler test treated a pre-commit callback as an acknowledged database outcome. | A controlled barrier proves that inspection sees the prior committed state while the timer transaction is held, then requires committed completion and exactly one timer event. The initial failure remains recorded with its source and log identity. |
| IndexForge ingestion | Cancellation could report completion and free capacity while a worker still owned work; shutdown could race admission. | Task ownership lasts until actual retirement, queued cancellation frees its queue entry, terminal results are retained within bounds, and durable publication takes precedence over a late cancellation request. [Lifecycle evidence](../projects/07-indexforge/evidence/ingestion-lifecycle-20261021.json). |
| IndexForge authorization | A request admitted for tenant A could retry using tenant B after subject reassignment. | Search and suggestions retain the admitted tenant while refreshing current server-side grants. Fresh and cached results recheck authorization; real signed HTTP races return redacted 403 responses. [Tenant-binding evidence](../projects/07-indexforge/evidence/tenant-binding-20261021/validation.json). |
| Browser credential boundaries | Resource-server configuration could exempt a cookie-bearing mutation from CSRF merely because it also supplied a bearer token. | The final assembled filter applies the intended credential matcher. Parsed and raw cookie headers require CSRF protection, while supported stateless bearer requests remain usable. Direct 403 responses avoid error redispatch ambiguity. [Cross-service evidence](execution/csrf-enforcement-20261021.json). |
| ThreadLens diagnostics | Comparison output did not fully represent allocation, contention, parking, timing distributions, and missing coverage. | HTML and optional JSON compare retained samples and selected timing evidence, preserve absent quantiles, rank changes, and expose recording/window/loss limitations. Output aliases are rejected and each rendered file is published through owned replacement. [Diagnostics evidence](../projects/08-threadlens/evidence/job-readiness-diagnostics-20261021.json) and [final source revalidation](../projects/08-threadlens/evidence/job-readiness-final-20261021.json). |
| ByteGuard configuration | Ambiguous JSON, coerced scalar values, impossible method contracts, or malformed baselines could obscure operator intent. | Exact rule fields, types, descriptors, and bounded JSON are checked before target parsing. SARIF baselines require explicit tool/model/rule/fingerprint identity. A real packaged test proves compatibility with the analyzer's own long-identity report. [Configuration evidence](../projects/09-byteguard/evidence/configuration-hardening-20261021/validation.json). |
| Build and evidence tools | Broken image-parser syntax stopped verification; report readers did not consistently bound or reject hostile XML; activation ignored a configured JDK directory. | The image inventory runs and fails closed on unresolved image expressions. Report reads reject special files, links, entity declarations, encoding bypasses, and excess actual bytes. Bootstrap and activation share the tools-directory override. The documented portfolio launcher has its executable Git mode restored. |
| MatchCore walkthrough | Formatting violations stopped the ordinary build before the hiring demo could run. | The formatting gate and all 48 ordinary tests pass. The actual cancellation/recovery demo checks exact executions, lots, balances, and final orders. |

## Executed local verification

These are the latest scoped reactor runs from this continuation, using the checksum-pinned Temurin **25.0.4.1+1** runtime and Maven **3.10.0** wrapper. They are not an unexecuted clean-checkout or hosted-CI claim. Property trials and simulated actions are not counted as extra test methods.

| Project | Ordinary methods | Integration methods run in this pass |
| --- | ---: | ---: |
| ClearLedger | 52 | Not rerun |
| DurableFlow | 123 | 33 |
| QuorumKV | 69 | Not rerun |
| PulseStream | 37 | Not rerun |
| TenantShield | 49 | Not rerun |
| MatchCore | 48 | Not rerun |
| IndexForge | 248 | 11 |
| ThreadLens | 208 | 7 |
| ByteGuard | 143 | 3 |
| RouteWeaver | 53 | Not rerun |
| **Total executed** | **1,030** | **54** |

The final runs have zero failures, errors, or skips. Freshness and completeness readers reject missing selected-module reports and skipped tests. DurableFlow uses an explicitly selected, disposable loopback PostgreSQL **18.6** instance with isolated test schemas; its exact pinned Testcontainers default is retained. This is real PostgreSQL integration, but it does not certify container startup or Docker compatibility in this environment. IndexForge runs actual loopback HTTP/OIDC/JWKS, Lucene, `/dev/full`, and packaged CLI checks. ThreadLens and ByteGuard launch actual child JVMs.

The shared Python suite passes **114 tests**. Source and compiled architecture scans pass across all ten projects. Documentation links and all four immutable image inventory entries pass. These inventory checks do not claim that the images are vulnerability-free.

ThreadLens's final combined integration/mutation reactor completed at **20:39:17 UTC** after the activation-script correction. Its original receipt is preserved, and the supplemental receipt binds the new run to all 93 source/build inputs. Interrupted or environment-blocked attempts are recorded as unsuccessful attempts, not folded into the passing result.

### Change volume

Relative to the continuation's starting commit, the implementation, regression tests and shared tools contain **6,861 added lines and 451 removed lines**, a net increase of **6,410 lines**. Documentation, evidence packets and two POM additions are excluded from these totals. Production source accounts for 1,586 additions and 263 removals; project tests account for 4,998 additions and 68 removals; shared scripts and their tests account for 277 additions and 120 removals. The changes address concrete failure cases rather than using document size as the implementation target.

### Mutation evidence

| Scope rerun in this continuation | Killed / generated | Interpretation |
| --- | ---: | --- |
| DurableFlow model, engine, persistence | 259 / 264 | Per-module results 41/41, 158/162, and 60/61; five survivors retained. This inspection-scope campaign precedes the later CSRF adapter and integration-test-only follow-up. |
| ThreadLens selected components | 718 / 811 | Six module gates pass; 74 survivors, 15 uncovered, and 4 timeouts remain counted. |
| ByteGuard selected core components | 527 / 727 | 106 survivors and 94 uncovered mutants remain counted. |
| ByteGuard identity/publication components | 30 / 30 | Existing target scope and thresholds retained. |

Mutation targets are explicit subsets, not whole-project coverage claims. ByteGuard's new configuration classes have direct boundary and process regressions; they are not represented as newly mutation-tested classes. The detailed packets retain module thresholds, outcomes, and source identities.

### Reproduce the hiring walkthrough

With JDK 25 available, run from the repository root:

```bash
./scripts/portfolio.sh verify matchcore
./scripts/portfolio.sh demo matchcore
```

The observed demo returned `PASS executions=4 lots=8 aliceCash=19200 aliceLots=8 openOrders=0 results=12`, including restart after the durable cancellation boundary. It is a synthetic deterministic accounting/recovery demonstration, not a throughput measurement.

For the managed bootstrap, retain the same `PORTFOLIO_TOOLS_DIR` when running `scripts/bootstrap-cloud.sh` and sourcing `scripts/cloud-env.sh`. The default remains `/workspace/.tools`; a custom provisioned location no longer requires resetting `JAVA_HOME` afterward. See [development](development.md) for complete setup and build limits.

## Remaining acceptance work

The [63-criterion release contract](release.md) and [historical acceptance register](acceptance-matrix.md) retain their existing thresholds and failures. The strict release audit still fails: 20 historical rows are incomplete and a complete current release receipt does not exist. No failed gate was suppressed or relabeled by this pass.

- **IndexForge relevance:** the existing exposed held-out corpus missed the 0.75 nDCG / 0.90 identifier-MRR targets. Correctness and tenant-isolation tests do not establish ranking quality. A valid independent corpus/protocol and frozen model are still required.
- **ThreadLens overhead:** the historical full 84-JVM study failed six of eight comparisons. The new diagnostics do not certify observer overhead. The full prescribed study still needs a quiet host and at least 126 minutes of observation.
- **Security release evidence:** upstream PostgreSQL/Kafka image findings and a current OWASP NVD-backed scan remain unresolved. An administrator-provided `NVD_API_KEY` is still required by the existing hosted scan policy. This pass adds no scanner suppression or credential.
- **Hosted and deployment checks:** pushed commits trigger CI, integration, and security workflows, but queued or pending runs do not establish success. Full current-source hosted release evidence, all container-backed integrations, simultaneous service demos, and native platform checks remain separate work.
- **Browser verification:** ThreadLens's report structure and escaping are tested, but its new comparison view was not independently exercised in an interactive browser during this pass. ClearLedger and TenantShield's new CSRF regressions use their assembled filter chains with mock servlet requests; RouteWeaver also exercises its real HTTP/container boundary.

The implementation and evidence are suitable for a focused engineering interview walkthrough. Claims about production deployment, security certification, field accuracy, full platform support, or benchmark acceptance must remain limited to their separately recorded results.
