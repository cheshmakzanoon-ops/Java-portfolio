# Engineering review — 2026-10-20

This review follows the [earlier boundary-hardening pass](execution/quality-review-20261020.json). It examines all ten implementations at their transaction, concurrency, recovery, resource-ownership, and analysis boundaries. Changes include smaller components with explicit responsibilities, compatible persistence changes, adversarial regressions, and operating procedures.

The [execution record](execution/engineering-review-20261020.json) identifies the baseline, project evidence, test policy, and remaining limits. Individual verification packets retain source identities, executed commands, and unsuccessful attempts. Historical measurements retain the source they measured.

## Implementation changes

| Project | Failure or ambiguity addressed | Resulting design |
| --- | --- | --- |
| [ClearLedger](../projects/01-clearledger/docs/REVIEW.md) | Retrying after an acknowledged commit can duplicate an operation; reconciliation can reuse consumed postings. | Phase-aware serializable transactions distinguish commit from cleanup, immutable CSV preparation precedes retries, posting validation precedes receipt lookup, and matching excludes consumed entries. |
| [DurableFlow](../projects/02-durableflow/docs/REVIEW.md) | A poisoned workflow can stop unrelated scheduling; stale failure handling can overwrite newer state. | Replay, SQL, transactions, and scheduler batches have separate responsibilities. Deferrals compare observed versions, terminal storage failures propagate, and recoverable claims use bounded retries. External-effect uncertainty remains explicit. |
| [QuorumKV](../projects/03-quorumkv/docs/REVIEW.md) | Delayed snapshot chunks can prevent progress; invalid WAL replacement or premature owner release can compromise recovery. | Snapshot correlation remains stable until acknowledgment, assembly is bounded and idempotent, persisted protocol invariants are checked, and shutdown retains journal ownership until the writer stops. |
| [PulseStream](../projects/04-pulsestream/docs/REVIEW.md) | A dequeue-to-in-flight gap understates backlog; premature shutdown can release an owner while work still runs. | Admission tracks outstanding work atomically. Engine and poller termination precede publisher and owner release; SQL transactions preserve acknowledged outcomes, and snapshots validate ownership and bounds. |
| [TenantShield](../projects/05-tenantshield/docs/REVIEW.md) | Mutable authorization inputs, invalid grants, or evicted live quota windows can change a decision's meaning. | Verified immutable plans and grants are constructed once, membership precedes callbacks, typed client responses are bounded, and quota windows retain ownership through admitted requests. RLS checks cover all protected tables and administrative role assumptions. |
| [MatchCore](../projects/06-matchcore/docs/REVIEW.md) | Damaged journals must stop admission; caller cancellation must not control shared work; checkpoints must agree with history. | Journal I/O failure is terminal, futures are isolated per caller, callbacks run outside admission locks, and checkpoint ancestry includes independently reconstructed balances and positions. Durability tests observe real channel-force calls and independently encoded frames. |
| [IndexForge](../projects/07-indexforge/docs/REVIEW.md) | Reader ownership can race publication; archives can allocate before limits; cached signed cursors can expire before cached content. | Generation admission closes before reader teardown, archive structure is bounded before expansion, and cached cursor content is signed afresh for each response. Compiler bindings, ranking, ingestion, and request identity are separate components. |
| [ThreadLens](../projects/08-threadlens/docs/REVIEW.md) | Callback closure can deadlock, duplicate agents can disable an existing session, and partial report inputs can bypass quotas. | Event processing, instrumentation sessions, child JVM ownership, and report validation have explicit lifecycles. Closure is coordinated, duplicate installation preserves the first session, and quotas cover complete directory traversal. |
| [ByteGuard](../projects/09-byteguard/docs/REVIEW.md) | Native calls, mutable returns, malformed frames, ambiguous identities, and corrupt archives can hide uncertainty or lose a flow. | Invocation effects, typed frames, control-flow validation, archive preflight, unfinished-method reporting, and owned report output enforce separate contracts. Analysis never executes the target program. |
| [RouteWeaver](../projects/10-routeweaver/docs/REVIEW.md) | Durable job authority can drift from candidate input; publication and asynchronous responses can show conflicting state. | Separate repositories preserve locked job authority and fencing. The dispatch page places feasibility beside publication, filters map and timeline by vehicle, and rejects stale responses. |

The [architecture guide](architecture.md) compares decision and acknowledgment boundaries. Project architecture, invariants, security, and operations documents explain lock ordering, failure outcomes, compatibility, and recovery beside the implementation.

## Verification policy

The isolated clean clone passed **839 ordinary Java test cases** using an initially empty Maven cache. Project-specific database, broker, TLS, HTTP, and packaged-process runs passed another **114 integration cases**. All reported cases completed without failures, errors, or skips. Property-generated examples and simulation actions are not counted as additional test cases.

| Project | Ordinary cases | Integration cases | Strict mutation kills / viable mutants |
| --- | ---: | ---: | ---: |
| ClearLedger | 45 | 22 | 137 / 141 |
| DurableFlow | 96 | 22 | 250 / 254 |
| QuorumKV | 69 | 7 | 325 / 421 |
| PulseStream | 37 | 11 | 209 / 219 |
| TenantShield | 41 | 15 | 271 / 276 |
| MatchCore | 43 | 2 | 458 / 514 |
| IndexForge | 194 | 4 | 1,901 / 2,450 |
| ThreadLens | 157 | 7 | 593 / 680 |
| ByteGuard | 106 | 2 | 557 / 757 |
| RouteWeaver | 51 | 22 | 82 / 82 |

Mutation scopes differ by project; the table does not imply whole-codebase mutation coverage. Every selected module passed its own declared threshold. The packets preserve separate source-bound campaigns where unchanged modules were reused, as well as earlier failed attempts. The formerly heap-exhausting IndexForge scoring-loop mutation is killed in its complete replacement query campaign.

The shared tools passed 48 Python cases. Another 64 launcher and cache cases passed and are included in project CI jobs; DurableFlow's 19 subprocess cases run through its ordinary Maven suite. Source and compiled architecture checks found no violations. After the cold build, one additional ByteGuard identity assertion and CI launcher wiring were verified separately; production code and Maven inputs remained unchanged. The [execution record](execution/engineering-review-20261020.json) identifies both tested revisions and report hashes.

Ordinary and integration checks require fresh, nonempty reports for every selected module. Test-case totals must agree with suite totals; failures, errors, and skipped tests fail the check. Database and broker integration uses actual pinned containers; process tests exercise actual JVMs and sockets.

Mutation results use **killed mutants only** in the numerator. Surviving, uncovered, and timed-out viable mutants remain in the denominator; runner, memory, and unknown states fail. Every selected module must meet its declared threshold, with a minimum of 70%. Project packets identify targeted classes and non-killed results. Mutation coverage does not establish correctness outside those scopes.

Shared checks exercise the verification tools themselves, dependency inventories, documentation links, pinned images, and source and compiled architecture boundaries. The compiled check inspects every origin namespace, including fully qualified dependencies that an import-only check would miss. Executed counts and source receipts are recorded in the [verification packet](execution/engineering-review-20261020.json).

## Interfaces and operation

RouteWeaver's PostgreSQL-backed dispatch workflow was exercised in Chromium at desktop and 390px widths, including strict CSP, keyboard controls, partial plans, delayed responses, publication, and forced-restart recovery. ThreadLens's generated normal and differential reports were exercised at both widths through a temporary loopback server, including navigation, literal hostile identifiers, table scrolling, and privacy checks. Direct file navigation was blocked by managed browser policy; that failed attempt is retained separately.

Lifecycle scripts prepare artifacts before acquiring services, bind reusable artifacts to their inputs, preserve preexisting containers on failed startup, and stop only processes for which they hold identity evidence. Project packets state the precise identity mechanism and platform limits. Fresh PostgreSQL clusters validate the selected Alpine baseline; they do not establish compatibility for transplanting existing volumes between libc implementations.

## Dependencies and remaining limits

Dependency changes patch observed vulnerable Jackson, Tomcat, and LZ4 versions. The [security observations](dependencies/security.json) distinguish application libraries from container contents and preserve earlier findings. The scanner regenerates the aggregate SBOM, checks unchanged POM inputs and complete package coverage, and rejects HIGH or CRITICAL findings without suppressions.

The selected PostgreSQL image still has findings in its upstream `gosu` executable; Kafka still has findings in bundled libraries and an OS package. These remain release failures. Application dependency overrides do not replace broker-owned libraries. The [technology baseline](technology-baseline.md) records versions, digests, and scope.

Historical relevance, observer-overhead, and performance failures remain failures. This pass establishes no new throughput, latency, capacity, or overhead result. Hosted CI, hosted CodeQL, OWASP feed access, and native Windows/macOS execution have separate outcomes. The [release contract](release.md) retains all 63 acceptance identifiers; passing correctness checks does not override outstanding release criteria.
