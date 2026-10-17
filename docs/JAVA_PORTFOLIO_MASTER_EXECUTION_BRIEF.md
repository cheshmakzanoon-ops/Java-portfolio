# Java Portfolio Master Execution Brief

**Ten advanced projects with ten parallel coding agents**

**Target repository:** [cheshmakzanoon-ops/Java-portfolio](https://github.com/cheshmakzanoon-ops/Java-portfolio.git)  
**Target branch:** `main`  
**Brief date:** 17 October 2026  
**Audience:** one coordinating engineering agent and ten project implementation agents  
**Deliverable:** a cohesive Java monorepo containing ten independently runnable, thoroughly tested projects and verifiable engineering case studies.

This is an implementation contract. When the repository owner gives you this file and asks you to execute it, build the software, exercise the difficult behavior, document the evidence, and complete the release gates. A list of ideas, generated scaffolds, diagrams without working systems, or ten basic CRUD applications does not satisfy this brief.

The intended standard is the depth of judgment expected from a strong senior Java engineer: explicit guarantees, deliberate architecture, correct concurrency, understandable domain models, operational recovery, measured performance, and clear explanations. The portfolio must demonstrate those skills through inspectable work. It must never represent portfolio development as ten years of employment or claim deployments, customers, uptime, throughput, or security certification that have not occurred.

## Navigation

- [1. Repository baseline and preflight](#1-repository-baseline-and-preflight)
- [2. The ten project assignments](#2-the-ten-project-assignments)
- [3. Parallel agent operating model](#3-parallel-agent-operating-model)
- [4. Java technology baseline](#4-java-technology-baseline)
- [5. Repository structure and build contracts](#5-repository-structure-and-build-contracts)
- [6. Common quality and evidence requirements](#6-common-quality-and-evidence-requirements)
- [7. Project implementation contracts](#7-project-implementation-contracts)
- [8. Execution milestones and integration gates](#8-execution-milestones-and-integration-gates)
- [9. Final portfolio content and handoff](#9-final-portfolio-content-and-handoff)
- [10. Source and version maintenance](#10-source-and-version-maintenance)

| Project contract | Direct link |
| --- | --- |
| A01 — ClearLedger | [Open contract](#project-01--clearledger-an-auditable-accounting-and-reconciliation-engine) |
| A02 — DurableFlow | [Open contract](#project-02--durableflow-a-durable-workflow-execution-engine) |
| A03 — QuorumKV | [Open contract](#project-03--quorumkv-a-replicated-key-value-store-with-a-from-scratch-raft-core) |
| A04 — PulseStream | [Open contract](#project-04--pulsestream-event-time-analytics-with-recoverable-state) |
| A05 — TenantShield | [Open contract](#project-05--tenantshield-explainable-multi-tenant-authorization) |
| A06 — MatchCore | [Open contract](#project-06--matchcore-deterministic-order-matching-and-replay-laboratory) |
| A07 — IndexForge | [Open contract](#project-07--indexforge-transactional-source-and-artifact-search) |
| A08 — ThreadLens | [Open contract](#project-08--threadlens-evidence-driven-jvm-diagnostics) |
| A09 — ByteGuard | [Open contract](#project-09--byteguard-bounded-java-bytecode-analysis-and-taint-tracing) |
| A10 — RouteWeaver | [Open contract](#project-10--routeweaver-constrained-dispatch-and-replanning) |

## How to use this file

Place the complete file at `docs/JAVA_PORTFOLIO_MASTER_EXECUTION_BRIEF.md`, then give the lead coding agent this instruction:

> Execute `docs/JAVA_PORTFOLIO_MASTER_EXECUTION_BRIEF.md` in `cheshmakzanoon-ops/Java-portfolio`. Inspect the current repository first. Establish the common build and ownership contracts, then launch ten project coding agents concurrently if the runtime supports ten workers plus a coordinator. Assign each worker its numbered project and keep it implementing through the defined release gates. You own integration, shared files, reviews, validation, and serialized Git operations on `main`. Implement all mandatory requirements, resolve defects, produce runnable demos and reproducible evidence, and commit and push the completed changes when repository authorization and branch rules allow. If the runtime provides fewer worker slots, use the available maximum, preserve all ten assignments, and report actual concurrency honestly. Follow the file through final acceptance; do not stop at planning or scaffolding.

The command block and file paths in this brief describe interfaces to implement. They are not a claim that those tools or projects already exist.

## 1. Repository baseline and preflight

At inspection, the public repository had default branch `main`, an initial commit `8284f1572045523d933502a24e5634b1c895f30b`, and one file: `README.md`, containing `# Java-portfolio`. This brief is therefore written for a new repository. The future coding agent must inspect again before editing because the remote may have changed.

Evidence: [inspected initial commit](https://github.com/cheshmakzanoon-ops/Java-portfolio/commit/8284f1572045523d933502a24e5634b1c895f30b) and [README at that commit](https://github.com/cheshmakzanoon-ops/Java-portfolio/blob/8284f1572045523d933502a24e5634b1c895f30b/README.md).

Run the following preflight, adapting the clone step if a checkout already exists:

1. Resolve the correct repository and authenticated account. Read applicable `AGENTS.md`, existing build files, and repository guidance. Record the remote URL, current branch, HEAD, remote `main`, and working tree status. Preserve existing work.
2. Use `rg --files` for the inventory. Read the actual source before changing a populated project. Do not reset, delete, or overwrite an existing implementation to match a preconceived template.
3. Inspect the available JDK, Maven wrapper, Docker/Compose, CPU count, RAM, disk space, network permissions, and actual agent concurrency limit. Put facts in `docs/execution/environment.md` without credentials or personal machine identifiers.
4. Recheck the technology documentation in Section 4. Pin compatible released versions and verify dependency resolution before distributing ten incompatible starter projects.
5. Convert this brief into `docs/acceptance-matrix.md`. Every mandatory acceptance item must have an owner, implementation location, test or demo command, evidence location, and status. Start with `NOT_STARTED`; use `IN_PROGRESS`, `IMPLEMENTED_UNVERIFIED`, `PASS`, `FAIL`, or `BLOCKED` as work progresses.
6. Do not ask the owner to choose routine filenames, frameworks already selected here, folder structure, or ordinary implementation details. Make the specified choices and record meaningful deviations in an architecture decision record. If an external capability is unavailable, finish independent work and report the precise remaining dependency.

## 2. The ten project assignments

All ten projects are mandatory. Each project is a standalone product with its own domain, executable entry point, demo, tests, and engineering case study. The common repository supplies build standards and presentation; it does not turn the projects into ten interdependent microservices.

| Agent | Project and directory | Product | Main engineering evidence |
| --- | --- | --- | --- |
| A01 | ClearLedger — `projects/01-clearledger/` | Double-entry ledger and statement reconciliation | Transactional invariants, SQL concurrency, idempotency, auditability |
| A02 | DurableFlow — `projects/02-durableflow/` | Durable workflow execution and compensation | State machines, leased work, fencing, retries, ambiguous outcomes |
| A03 | QuorumKV — `projects/03-quorumkv/` | Replicated key-value store with an original Raft core | Consensus, persistence, linearizability, crash recovery |
| A04 | PulseStream — `projects/04-pulsestream/` | Event-time analytics with durable recovery | Watermarks, late events, deduplication, atomic checkpoints |
| A05 | TenantShield — `projects/05-tenantshield/` | Multi-tenant authorization and policy decisions | Tenant isolation, RBAC/ABAC, policy versions, revocation semantics |
| A06 | MatchCore — `projects/06-matchcore/` | Deterministic exchange matching simulator | Price-time priority, single-writer concurrency, journaling, replay |
| A07 | IndexForge — `projects/07-indexforge/` | Source and artifact search platform | Index lifecycle, ranking evaluation, incremental ingestion, query safety |
| A08 | ThreadLens — `projects/08-threadlens/` | JVM diagnostics and profiling workbench | JFR, instrumentation, class loading, overhead measurement |
| A09 | ByteGuard — `projects/09-byteguard/` | Java bytecode analysis and CI findings | Control flow, abstract interpretation, taint summaries, SARIF |
| A10 | RouteWeaver — `projects/10-routeweaver/` | Dispatch planning and constrained fleet optimization | Optimization modeling, independent validation, asynchronous replanning |

The projects deliberately cover different technical problems. Do not replace the consensus core with an existing database, the workflow core with a hosted orchestrator, the analyzer with a command that runs another analyzer, or the routing application with a pasted quickstart. Reuse mature libraries for supporting mechanisms and implement the central intellectual contribution described in each contract.

## 3. Parallel agent operating model

### 3.1 Roles and concurrency

The coordinator owns repository integration and shared engineering standards. A01–A10 are all coding agents, each responsible for a complete project. Do not consume one of the ten project assignments with a documentation-only or management-only role. The coordinator can perform review and implementation work while workers progress.

Launch A01–A10 concurrently after the foundation checkpoint when the execution environment permits ten active workers plus the coordinator. A prompt cannot override a host concurrency limit. If capacity is smaller, schedule the maximum supported workers in waves, retain the same ten ownership assignments, and publish actual active-worker counts. Do not claim ten agents are running when they are not. Do not create nested workers to evade platform limits.

Workers start with their own vertical slice and continue into reliability and evidence. A worker that encounters a shared dependency submits a precise request to the coordinator and proceeds with independent code. Do not block all ten projects waiting for a shared dashboard, identity provider, distributed database, or a different project.

### 3.2 Exclusive write ownership

| Paths | Writer | Rule |
| --- | --- | --- |
| Root `pom.xml`, `.mvn/`, `mvnw`, `mvnw.cmd` | Coordinator | One owner for dependency and plugin versions |
| Root `README.md`, `AGENTS.md`, `LICENSE`, `CONTRIBUTING.md`, `SECURITY.md` | Coordinator | Root documentation summarizes verified state |
| `.github/`, `build-support/`, `scripts/`, `catalog/`, root `docs/` | Coordinator | Workers submit requests; they do not edit these paths |
| `projects/NN-name/**` | Assigned A01–A10 | Includes project source, POMs, docs, local Compose, fixtures, and evidence |
| `integration/showcase/**` | Coordinator | Optional integration only after standalone acceptance |
| `.work/A01/` through `.work/A10/` | Corresponding worker | Ignored temporary scratch; no committed deliverables here |

No worker may run a repository-wide formatter, rename shared packages, change root dependency versions, stage files, commit, pull, rebase, reset, switch branches, or push. Workers may inspect Git status and diffs. They hand tested changes to the coordinator. A reviewer reports defects to the owning worker; the reviewer does not edit that worker's project concurrently.

Use a shared checkout on `main` with disjoint paths and one Git writer by default. Do not create ten long-lived branches. If the coding host requires isolated worktrees, use temporary local isolation only with an explicit recorded reason and serialize integration back into `main`; preserve the repository owner's existing branch policy. Never weaken protection or force-push to manufacture a green result.

### 3.3 Foundation checkpoint before fan-out

The coordinator must complete these steps before concurrent implementation:

1. Establish the Maven wrapper, root parent/aggregator, the ten project aggregator POMs, format rules, test profiles, ignore rules, and the project catalog schema.
2. Reserve all project package prefixes, artifact IDs, demo command names, HTTP port ranges, and status report paths.
3. Create one minimal Java test in one temporary foundation validation module or first real module, verify the JDK/compiler/test/plugin combination, and remove temporary validation scaffolding after it has served its purpose.
4. Commit a coherent foundation checkpoint if repository writes are authorized. Send the actual foundation commit to workers. The checkpoint is not project completion.
5. Give every worker the common rules, its complete project contract, its exclusive path, and the handoff format. Instruct it to start writing working code immediately after a short design note.

No portfolio-wide Java runtime library is required for version 1. Keep project-specific domain types, errors, security decisions, persistence, clocks, and protocols inside their projects. Share build configuration and document shapes. Extract a runtime library only after a real duplicate contract has been proven and the coordinator has reviewed the resulting coupling.

### 3.4 Build and resource coordination

Concurrent coding does not require ten heavyweight integration stacks running simultaneously. Give workers their own project `target/` directories and test datasets. Unit tests can run in parallel within CPU and memory limits. Limit concurrent Docker integration suites to the measured host capacity; begin with two slots. Run performance measurements alone on the reference host.

Workers invoke the repository wrapper against their own project aggregator, for example:

```bash
./mvnw -f projects/01-clearledger/pom.xml -Dmaven.repo.local=.work/A01/m2 -B -ntp verify
./mvnw -f projects/01-clearledger/pom.xml -Dmaven.repo.local=.work/A01/m2 -B -ntp -Pintegration verify
```

Each project aggregator inherits the root parent using `../../pom.xml`; its child modules inherit the project parent using `../pom.xml`. Project modules have no dependencies on another project's artifacts. This allows a project build without first installing the entire monorepo. Use unique artifact IDs such as `clearledger-domain`, not ten identical `core` artifact IDs. When a contract lists a generic module directory such as `service/` or `verification/`, prefix its artifact ID with the project slug. A table labeled module/path can name a package or owned support directory; only create another Maven artifact when it has a meaningful dependency, executable, or verification boundary.

Avoid concurrent `mvn install` into a shared local repository for mutable monorepo artifacts. By default give each worker a separate Maven local repository under its assigned scratch, resolving that path to an absolute path in launcher scripts. Run the examples above from the repository root. If sharing a dependency cache to reduce downloads, the coordinator must configure and verify cross-process Resolver locking first; ordinary in-process locks are insufficient. See [Maven Resolver named locks](https://maven.apache.org/resolver/maven-resolver-named-locks/index.html). No worker edits another worker's build outputs.

The coordinator owns full-reactor builds. Do not run a root build while workers are changing files that build is supposed to certify; establish a checkpoint or frozen review window. Use a coordinator-specific local repository for release verification.

Before changing shared build policy or dependency pins, pause affected builds, publish the revised foundation contract, and resume only after workers have received it. Do not silently change the classpath underneath a benchmark or release verification.

Set reasonable JVM heap limits and test fork counts in the execution environment. Do not pass `-T 1C` to every worker and multiply the machine's entire CPU allocation ten times. A performance regression measured while nine other agents are compiling is not publishable evidence.

### 3.5 Handoff and review protocol

At each milestone, the worker updates its own `docs/STATUS.md` with:

```text
Project and milestone:
Owned paths changed:
Implemented acceptance IDs:
Commands executed, environment, and exit status:
Evidence paths and tested source revision or tree fingerprint:
Correctness guarantees demonstrated:
Known limitations or remaining failures:
Shared change request, if any:
Next implementation step:
```

The coordinator tracks shared requests centrally. Use explicit statuses; do not estimate progress from lines of code or number of files. On interruption, leave a resumable checkpoint with commands, defects, and pending acceptance items. Resume from that evidence instead of generating another scaffold.

After a project is reviewable, use this review rotation: A01 reviews A02, A02 reviews A03, continuing through A09 reviewing A10, and A10 reviews A01. The review focuses on guarantees, fault tests, missing domain behavior, and demo reproducibility. The owner implements fixes. The coordinator closes defects only after evidence supports closure.

### 3.6 Serialized commits and remote verification

Before a project handoff, its owner acknowledges a write pause covering that project's paths. While those paths are frozen, the coordinator reviews the intended path-scoped diff, runs relevant gates, stages only those reviewed paths, checks the staged diff, and creates an accurate commit. The coordinator then releases the owner to resume. Never use `git add .` to scoop up other agents' unfinished work. Use messages such as `feat(clearledger): enforce balanced posting and request deduplication` or `test(quorumkv): verify recovery after interrupted snapshot install`.

Fetch and inspect remote changes before a push. Fetching may occur while workers code, but any checkout-changing reconciliation, merge, or rebase requires a pause acknowledged by all filesystem writers and preservation of their outstanding changes first. If `main` has moved, reconcile with the new work, preserve both sides, and rerun affected checks. Do not rewrite remote history, disable protections, or repeatedly retry an unchanged failing push. Diagnose permission, network, branch-rule, large-file, or history conflicts from actual errors. If branch rules require a pull request, follow those rules and report the boundary accurately.

At final handoff, report the final local commit, remote `main` commit, relevant CI state, and working tree state separately. A local passing build, a successful push, and a passing remote workflow are three different observations. If pushes are not authorized or access is blocked, finish the reviewable local work and identify the exact unresolved publication step.

## 4. Java technology baseline

### 4.1 Verified starting choices

Use Java 25 LTS for application and library source, with a verified OpenJDK distribution such as Eclipse Temurin and compiler release `25`. The Oracle roadmap lists Java 25 as an LTS release. Spring Boot's inspected requirements page lists stable 4.1.1 and compatibility including Java 25. Maven's inspected download page lists 3.10.0 as the current stable release. These are research snapshots, not instructions to resolve floating versions on every build.

| Concern | Selection | Reason and implementation rule |
| --- | --- | --- |
| Java | JDK 25, exact distribution/build recorded | Modern LTS baseline; no preview or incubator features in required code |
| Build | Maven Wrapper, Maven 3.10.0 baseline | One predictable reactor and portable `mvnw`/`mvnw.cmd` entry points |
| Service framework | Spring Boot 4.1.1 baseline for service adapters | Keep domain and engine modules independent of Spring |
| SQL | PostgreSQL, JDBC or jOOQ, Flyway | Explicit transactions and migrations; use the real engine for persistence tests |
| Messaging | Apache Kafka only where the contract needs it | Bound the delivery guarantee and retention assumptions |
| HTTP contracts | Versioned OpenAPI, JSON, [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem responses | Generate or validate clients/specs from a single authoritative contract |
| Tests | JUnit Jupiter, AssertJ, Testcontainers, jqwik where compatible | Example, integration, and property tests prove different claims |
| Architecture | ArchUnit rules with targeted boundaries | Enforce dependency direction and prevent cross-project imports |
| Performance | JMH for microbenchmarks; Java load drivers and JFR for systems | Separate component timing from end-to-end latency |
| Observability | SLF4J, Micrometer, OpenTelemetry where appropriate | Bounded cardinality, request correlation, meaningful domain measurements |
| Diagnostics/analysis | JFR, Java Instrumentation, ASM | Use supported JDK APIs and bytecode libraries; implement project-specific analysis |
| Search | Apache Lucene | Build ingestion, ranking, lifecycle, and evaluation around a mature index |
| Optimization | Timefold Solver library plus independent Java validator/baseline | Use solver machinery while owning the real constraints and lifecycle |
| UI | Server-rendered HTML/CSS and minimal JavaScript; CLI where natural | Clear recruiter demos with Java remaining the substantive codebase |
| Local runtime | Docker Compose per project, without paid accounts | Standalone demos and repeatable data setup |

At execution, recheck published releases and compatibility, then pin full versions in root dependency/plugin management and container image references. Prefer a currently patched compatible release if the snapshot version has been superseded. Record the chosen versions, checksums or image digests, compatibility evidence, and reason for a deviation in `docs/technology-baseline.md`. Do not invent a future version or silently mix Boot 3 examples with Boot 4 dependencies. Use the BOM-compatible Jupiter generation, not a blanket assumption that all current Spring builds use JUnit 5.

Root plugin management must pin compiler, Surefire, Failsafe, JaCoCo, Enforcer, formatter, source/Javadoc, and relevant packaging plugins. Activate required checks through `build/plugins` and appropriate lifecycle executions; entries in `pluginManagement` alone do not activate them. Bind Failsafe to both `integration-test` and `verify` in the integration profile. Demonstrate controlled failures of test, format, and coverage gates in the foundation rather than assuming configuration is effective. See the [Maven POM reference](https://maven.apache.org/pom.html). Pin dependencies absent from the chosen BOM. Review dependency convergence; resolve an actual conflict instead of hiding every warning with exclusions. Keep root build policy small and legible.

### 4.2 Java-specific engineering expectations

Use records for immutable data carriers, sealed types where they make state alternatives explicit, exhaustive pattern matching where useful, and `java.time` for time. Use conventional classes where lifecycle, identity, or ORM mechanics require them. Do not use language features merely to populate a checklist.

Use virtual threads for suitable blocking I/O workloads; they do not make CPU computation faster. CPU-bound solvers, indexers, matching loops, and analyzers need bounded execution policies. Bound concurrent downstream requests with explicit permits and queue capacity even when virtual threads are cheap. Handle interruption, timeouts, cancellation, shutdown, and resource ownership explicitly. Close I/O resources with try-with-resources. Do not use the common fork-join pool as an accidental global scheduler.

Define ownership for every mutable state structure. Keep thread confinement, locks, atomics, and immutable snapshots understandable. A concurrency-focused project must explain memory visibility and happens-before relationships and test the observable behavior. Do not label ordinary collections lock-free. Do not add `Unsafe`, native code, off-heap allocation, reactive APIs, or custom class loaders without a measured or contractual reason.

For currency and order quantities, choose exact scaled representations, checked arithmetic, and documented bounds. For SQL, define isolation, lock order, transaction retry scope, query plans, indexes, migration compatibility, and pool limits. A Java annotation is not a substitute for explaining the transaction boundary.

Prefer small explicit domain/application/adapters modules over generic base services and repositories. Do not implement a homegrown dependency injection framework. Avoid catch-all `utils`, reflection-heavy magic, unnecessary Lombok, empty interfaces, or event sourcing applied to every CRUD entity. Use event history where the project's semantics require it.

Primary references: [Oracle Java support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html), [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Maven releases](https://maven.apache.org/download.cgi), [Maven reactor](https://maven.apache.org/guides/mini/guide-multiple-modules.html), [Maven Wrapper](https://maven.apache.org/tools/wrapper/), and [JDK 25 virtual threads](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html).

## 5. Repository structure and build contracts

### 5.1 Root structure

Create these paths with useful content. Do not add empty folders merely to resemble an enterprise repository.

| Path | Required contents |
| --- | --- |
| `README.md` | Concise portfolio entry, three recommended deep dives, ten-project comparison table, quickstart, evidence links |
| `AGENTS.md` | Ownership matrix, commands, Git writer policy, tested guarantees, resume instructions |
| `pom.xml`, `.mvn/`, `mvnw`, `mvnw.cmd` | Root parent/aggregator, pinned wrapper, common build/test profiles |
| `.editorconfig`, `.gitattributes`, `.gitignore` | Formatting, text normalization, generated-output and local-data rules |
| `LICENSE`, `NOTICE`, `THIRD_PARTY.md` | Consistent licensing; choose Apache-2.0 for original code unless the owner already selected another license; preserve dependency/data notices |
| `CONTRIBUTING.md`, `SECURITY.md` | Local development, useful checks, and a real supported security contact/report route |
| `catalog/projects.yaml` | Exactly ten entries, unique slug/path/ports, launch/verify/demo commands, maturity and evidence paths |
| `docs/architecture.md` | Monorepo boundaries and build/deployment relationships |
| `docs/technology-baseline.md` | Verified toolchain/dependencies/images and compatibility decisions |
| `docs/acceptance-matrix.md` | Project and repository requirements with evidence-backed status |
| `docs/execution/` | Environment, coordinator state, requests, integration defects, resumable checkpoints |
| `docs/portfolio-guide.md` | Recruiter navigation, project comparisons, technical interview routes |
| `docs/decisions/` | Root ADRs on consequential choices; no quota of cosmetic ADRs |
| `build-support/` | Formatter/static analysis configuration and catalog schema |
| `scripts/` | Portable project launcher, verification, demo, evidence, and cleanup entry points |
| `.github/workflows/` | Common CI, integration, security, and manual benchmark jobs |
| `.github/ISSUE_TEMPLATE/`, `.github/PULL_REQUEST_TEMPLATE.md` | Actionable defect and change templates |
| `projects/01-clearledger/` through `projects/10-routeweaver/` | The complete project implementations specified below |

Do not publish fake GitHub usernames for worker agents in `CODEOWNERS`. If a CODEOWNERS file is useful, name only actual maintainers with repository access; the agent ownership map belongs in `AGENTS.md`.

### 5.2 Per-project structure

Each project has a packaging-`pom` aggregator and the meaningful Java modules in its own contract. Within a module use Maven's standard `src/main/java`, `src/main/resources`, `src/test/java`, and `src/test/resources` paths. Use package prefix `io.github.cheshmakzanoon.portfolio.<slug>`; replace `<slug>` with the plain project name, for example `clearledger`.

Each project must also contain:

| Path relative to project | Requirement |
| --- | --- |
| `README.md` | What it does, guarantees, quickstart, exact demo, architecture link, engineering decisions, measured results, limits |
| `docs/ARCHITECTURE.md` | Module diagram, principal data flow, domain/state model, failure boundaries |
| `docs/DECISIONS/` | Decisions that expose a real tradeoff, including rejected alternatives |
| `docs/INVARIANTS.md` | Precise invariants and the test/implementation that enforces each |
| `docs/OPERATIONS.md` | Start/stop, health, failure symptoms, recovery, backup/restore where meaningful |
| `docs/SECURITY.md` | Actual trust boundaries, data handling, access rules, known limitations |
| `docs/PERFORMANCE.md` | Workload, environment, raw result links, bottlenecks, before/after comparisons |
| `docs/CASE_STUDY.md` | Problem, design decision, difficult defect, evidence, practical limitations |
| `docs/STATUS.md` | Worker milestone handoff and unclosed items |
| `docs/REVIEW.md` | Independent review findings and verified resolutions |
| `contracts/` | API/protocol schemas or CLI/output contracts; a CLI project need not invent a web API |
| `demo/` | Scripted scenarios, deterministic fixture generators, expected observations |
| `infra/compose.yaml` | Only the project's required services, volumes, and health checks |
| `evidence/` | Small committed machine-readable test/demo/benchmark summaries tied to source and environment |

Use `target/`, `.work/`, or ignored runtime directories for large/generated outputs. Commit small stable fixtures and generators; store build artifacts, recordings, and large benchmark logs as CI artifacts or approved releases. Do not fill Git with Maven caches, database directories, container layers, `.jfr` recordings, huge JARs, or generated gigabyte datasets.

### 5.3 Build profiles and commands

Implement these common semantics across all projects:

| Command/profile | Behavior |
| --- | --- |
| `./mvnw -B -ntp verify` | Compile, unit/property tests, architecture rules, format/static checks; no mandatory Docker |
| `./mvnw -B -ntp -Pintegration verify` | Add real integration tests through Failsafe; a required missing container engine is a failure with a clear message |
| `./mvnw -B -ntp -Pmutation verify` | Targeted mutation checks of critical domain/algorithm modules |
| `./mvnw -B -ntp -Pbenchmarks package` | Build benchmark artifacts; do not silently run long benchmarks during every build |
| `./mvnw -f projects/NN-name/pom.xml ...` | Perform the same operation for that project's complete module graph |

Use project-specific profiles when a test needs different infrastructure, but document them in the catalog. Every project reactor must execute meaningful tests; packaging-only and generated-contract modules need not contain artificial tests. Validate required suite discovery at the project level so a zero-test implementation cannot pass. The coordinator verifies plugin configuration by observing discovery and execution, including controlled known failures while building the foundation. Do not use `skipTests`, `maven.test.failure.ignore`, blanket exclusions, or disabled containers to make final acceptance green.

Provide `scripts/portfolio.sh` and `scripts/portfolio.ps1` with equivalent behavior:

```bash
./scripts/portfolio.sh doctor
./scripts/portfolio.sh list
./scripts/portfolio.sh verify clearledger
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger
./scripts/portfolio.sh verify all
```

```powershell
.\scripts\portfolio.ps1 doctor
.\scripts\portfolio.ps1 up clearledger
.\scripts\portfolio.ps1 demo clearledger
```

Scripts resolve their own location, check prerequisites, propagate failures, enforce timeouts, and print actionable diagnostics. `demo` waits for health, uses deterministic seed data, checks outputs, and exits nonzero if an assertion fails. `down` stops only the chosen project's resources and preserves data by default. Provide a separately named, explicit `reset-demo` action that is limited to that project's seeded data; never use global Docker prune.

For CLI-first projects, `up` may prepare local directories or a controlled demonstration target rather than start an unnecessary server. `list` must state this. Do not install a full browser toolchain to print a CLI report.

### 5.4 Local isolation and port reservations

Reserve host port range `18100–18109` for A01, `18200–18209` for A02, continuing through `19000–19009` for A10. The first port in a range is the default main HTTP UI/API if one exists. QuorumKV allocates client and peer ports within its range. ThreadLens and ByteGuard may use no service port. Every port is overridable for tests.

Compose project names use `java-portfolio-<slug>`. Databases, Kafka topics, credentials, and volumes are isolated per project; credentials in committed demo files must be explicit disposable local fixtures. Bind published demo ports to loopback by default. Testcontainers uses dynamic port mapping. Do not publish database and broker ports unless the demo needs host access. Resource names must not collide when two different projects run together.

## 6. Common quality and evidence requirements

### 6.1 Functionality, contracts, and security

Every advertised endpoint, command, screen, and feature must exercise real implemented behavior. Seeded examples are allowed; hardcoded success responses, fake charts, prerecorded failure recovery presented as live, and mocked persistence in the shipped demo are not.

Service adapters validate input and limits, use consistent error codes, bound pagination and request sizes, and avoid leaking stack traces or identifiers across tenants. Apply parameterized SQL, explicit timeout budgets, safe serialization, and output escaping. Administrative and destructive operations need the project's actual authorization rule. Generate demo tokens/keys by the documented local setup; keep real secrets out of code, logs, fixtures, and screenshots.

Use a real local OIDC issuer when proving token validation. Ordinary demos may use a named, explicitly trusted disposable development profile bound to loopback; reject non-loopback binding for that profile. A protected profile and its security acceptance remain mandatory for service projects with authenticated operations. Those tests exercise signature, issuer, audience, expiry, and scope/tenant handling. Projects remain independently runnable: TenantShield is an optional integration for other projects, not their bootstrap dependency.

For the security tools, inspect only local fixtures and explicitly selected inputs; do not crawl or attack external systems. For financial projects, use synthetic accounts and execution simulations. No paid cloud account, commercial data feed, real fund movement, or production credential is required for completion.

### 6.2 Meaningful tests

Each project needs tests that could detect a plausible serious defect. Test the contract with a reference model, independent invariant checker, or observable system outcome where possible. Include malformed input, duplicate requests, concurrent calls, process restart, bounded resources, and the project-specific difficult cases. Avoid a coverage suite that just calls getters or repeats implementation branches.

Use the following baseline and refine it transparently where a project has a meaningful exception:

- Core domain/algorithm modules: at least 85% line and 75% branch coverage on handwritten code; adapter coverage is risk-based. Generated code can be excluded by exact source path with an explanation.
- Critical invariant tests must all pass regardless of aggregate coverage. For selected small critical modules, aim for at least 70% non-equivalent mutation kill rate; investigate survivors and document equivalent or invalid mutants. Do not hide broad classes to meet a percentage.
- Database semantics run against PostgreSQL through Testcontainers. H2 is not the correctness oracle for PostgreSQL locks, constraints, isolation, or migrations.
- Property tests use reproducible seeds and preserve a minimized counterexample when a defect appears. Time-based algorithms use injectable clocks or explicit event time; concurrency integration tests may use real time with bounded deadlines.
- Distributed histories and fault scenarios report the cases and schedules explored. A bounded checker returning `UNKNOWN` is not a proof. A suite finding no violation is not a proof of universal correctness.

Testcontainers, ArchUnit, and JMH are separate tools with distinct purposes; use them where they support an actual requirement. Primary references: [Testcontainers](https://java.testcontainers.org/), [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html), and [JMH](https://github.com/openjdk/jmh).

### 6.3 Performance methodology

Every project publishes a reproducible workload and at least one meaningful measured baseline. Numeric targets in this brief are proposed acceptance workloads or optimization goals, never already achieved results. Report when a target has not been met; improve the bottleneck or document an agreed, evidence-backed scope adjustment. Do not quietly change the dataset to improve a graph.

Record CPU model, available cores, memory limit, OS/architecture, JDK vendor/full build, JVM flags, dependency versions, data seed, data size, warmup, measurement duration, forks/runs, client concurrency or offered load, timestamp, source revision, and whether containers share the host. State the measured boundary: in-memory matching, journal commit, HTTP round trip, or full dependency path.

Use JMH forks/warmup and consume results for CPU microbenchmarks. Use a separate system load driver for network/persistence measurements. Publish p50/p95/p99 where sample counts support them, throughput, error/rejection rate, queue growth, CPU, allocation, and GC observations. Use open-loop load or explicitly explain coordinated omission and closed-loop limits. Avoid claiming nanosecond end-to-end service latency from an inner-loop benchmark.

Compare changes on the same reference environment across at least three independent runs; include variability. CI can run correctness and small performance smoke checks, while noisy shared runners should not enforce aggressive microsecond thresholds. Absolute targets must name a reference machine; correctness gates do not depend on a fast laptop.

Commit a small result envelope shaped like:

```json
{
  "schemaVersion": 1,
  "project": "matchcore",
  "sourceRevision": "<actual tested commit or explicit working-tree fingerprint>",
  "workload": "<versioned workload name>",
  "seed": 42,
  "environment": "<relative environment manifest path>",
  "command": "<exact executed command>",
  "exitCode": 0,
  "assertions": {"passed": 0, "failed": 0},
  "measurements": {},
  "rawArtifact": "<actual artifact path or URL>",
  "limitations": []
}
```

Replace all illustrative values with observed values. Evidence may refer to the code commit immediately before the evidence-only commit; do not create a circular requirement that a file contain the SHA of the commit that first creates itself. Record the relationship explicitly.

### 6.4 CI and repository automation

Implement root-controlled GitHub Actions workflows with these responsibilities:

| Workflow | Required behavior |
| --- | --- |
| `ci.yml` | Format, compile, unit/property/architecture checks; matrix of all ten projects on `main`; affected projects on routine changes |
| `integration.yml` | Container-backed integration and failure tests; bounded parallelism and timeouts; reports uploaded on failure |
| `security.yml` | Dependency/container/source checks supported by the account; SBOM and clear handling of justified suppressions |
| `benchmark.yml` | Manual or explicitly configured benchmark execution; reference environment and raw artifacts; no invented badges |
| `release-check.yml` | Catalog/docs/link validation, complete acceptance summary, packaging and clean-clone demos |

Root build or dependency changes trigger all ten projects. A project-only change can use a path-filtered matrix, but a required aggregate job must fail if a mandatory selected check failed or never ran. Use `fail-fast: false` for independent project matrix jobs so a failure leaves useful results from the others.

Use read-only default workflow permissions, verify action provenance, pin action dependencies with the currently supported GitHub lockfile mechanism or verified full commit SHAs, and keep write permissions limited to the job that needs them. Do not execute untrusted pull-request code in a privileged `pull_request_target` context. See [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use).

Keep performance gates separate from ordinary unit gates and resource-heavy failure tests separate from lightweight checks. No required gate may be made green by `continue-on-error`, silent container skips, disabled tests, fake metrics, or excluding the difficult implementation from coverage. Explain tool/network outages in the status; an unavailable scan is not a passed scan.

### 6.5 Presentation and interview evidence

Use a professional root README with a short purpose, real CI status, Java/build requirements, the ten-project table, and links to three recommended deep dives: ClearLedger for backend correctness, DurableFlow for reliability, and ThreadLens for JVM depth. This recommendation controls navigation only; it does not lower completion requirements for the other seven.

Every project needs a 3–5 minute demonstration and a 10–15 minute technical walkthrough. Show the hard behavior, not only a homepage. Include one useful diagram, one real output or screenshot, the critical invariant, one failure/recovery example, measured results, and a decision the author can defend. A CLI recording or terminal output is sufficient for a CLI product; a fashionable dashboard is not a requirement.

The case study must explain an actual defect found during development, the rejected design, the chosen fix, and the regression evidence. If no consequential defect has yet been discovered, state that honestly and use a documented adversarial test that motivated a design decision. Do not invent an incident history.

Include `docs/INTERVIEW.md` per project with questions grounded in its implementation: walk through a request, state the invariant, explain the worst failure, point to the test, read a profile/query plan, and discuss the next scaling bottleneck. The owner should be able to run the demo, explain the code, and make a small live change. Record third-party code/data attribution and assisted-development provenance accurately.

## 7. Project implementation contracts

Each numbered contract below supplements the common requirements. All sections marked required, every listed milestone, and all acceptance IDs belong to release 1 unless explicitly labeled optional. A bounded scope is the finished product's specification, not permission to replace its difficult behavior with stubs.

### Project 01 — ClearLedger: an auditable accounting and reconciliation engine

**Assigned owner:** A01. **Exclusive directory:** `projects/01-clearledger/`. **Hiring evidence:** precise domain modelling, database concurrency, business invariants, multi-tenant authorization, migration discipline, and recoverable asynchronous projections. Build a coherent accounting engine for a fictional software marketplace. Every amount and statement is synthetic. This project moves no money and connects to no payment network.

#### Product and modules

Create child Maven modules `ledger-domain`, `ledger-persistence`, `ledger-api`, and `ledger-verification`, plus owned `docs/`, `fixtures/`, `scripts/`, and `infra/` directories. Use the coordinator's pinned Java baseline, Spring Boot, Spring Security, JDBC, PostgreSQL, Flyway, JUnit Jupiter, jqwik, and Testcontainers. Keep the domain free of Spring and persistence annotations; use explicit SQL for posting and reconciliation. Do not add Kafka simply to relay one local projection. A PostgreSQL outbox and independently restartable projector are sufficient.

Implement account creation, atomic multi-line journal posting, full journal reversal, account statements, period closing, exact reconciliation against synthetic bank-statement CSVs, and replayable reporting. Support CAD, USD, and JPY with a versioned currency-scale fixture. Every journal uses one currency; an account's currency never changes after its first posting. There is no foreign-exchange calculation, card processing, lending, tax engine, or claim of regulatory compliance.

#### Domain and storage contract

Represent money with a currency and `BigInteger` minor units; accept decimal strings only through strict scale validation. Never use binary floating-point money. Bound input amounts, batch size, and stored balances explicitly; an overflow rejects the entire transaction. `DEBIT` and `CREDIT` are accounting sides, not synonyms for an account gaining or losing value. Store normal account side and document the balance presentation formula.

Minimum schema: `tenant`, `account`, `accounting_period`, `journal`, `posting`, `idempotency_receipt`, `account_balance`, `outbox_event`, `projection_checkpoint`, `statement_import`, `statement_row`, `reconciliation_match`, and `reconciliation_decision`. Use composite tenant-aware foreign keys. Enforce positive posting amounts, at least two postings per journal, account/currency compatibility, and equality of total debits and credits. Give every journal an effective business date and database recording time; define period boundaries using dates rather than server-local timezone conversions. A reversal is a new journal referencing the original and swapping its sides. Preserve the original journal and prevent a second full reversal.

The database posting operation must atomically validate and insert the journal and all postings, update authoritative balance totals, record the idempotency receipt, and create the outbox event. PostgreSQL does not support a cross-row accounting sum as an ordinary `CHECK` constraint; use a restricted posting function plus appropriate constraints/triggers and permissions. The runtime role cannot directly mutate posted journals or postings. Harden a privileged function's schema resolution and grants; test using the actual runtime database role. Protect tenant identity using the verified application principal, not a request-body tenant field. A database administrator remains outside the integrity threat model. See [PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html).

#### Concurrency and failure design

Run posting and period-close decisions in transactions with a documented serializable strategy. Acquire participating account locks in a stable order, and coordinate journal posting with period closure through the corresponding period record. Retry the entire transaction on serialization failure within a bounded retry budget; keep network side effects outside that retry. PostgreSQL explicitly requires complete transaction retry, including decisions that selected the statements. See [serialization failure handling](https://www.postgresql.org/docs/current/mvcc-serialization-failure-handling.html).

Scope each idempotency key by tenant and operation. Store a canonical payload digest and stable response in the same commit as the journal. An identical retry returns the original journal identifier; the same key with different intent returns `409`. Preserve posting receipts for this project's retained journal lifetime. An HTTP timeout does not imply rollback: clients retry with the original key or query request status. Reject terminal validation errors distinctly from temporary retry exhaustion.

The reporting projector processes events at least once and commits each applied event and checkpoint together. Redelivery changes nothing. Use durable per-event processing state; a sequence-generated event ID is not a safe commit-order watermark because an earlier ID can commit later. Test that interleaving explicitly. Expose projection freshness; never silently present a lagging reporting balance as the authoritative current balance. Replay into separate projection tables, compare against the immutable source, and promote only after verification. No destructive live replay command is permitted. Book-closing and reconciliation calculations use consistent database snapshots.

#### API and reconciliation behavior

Publish OpenAPI contracts for `POST /v1/accounts`, `POST /v1/journals`, `POST /v1/journals/{id}/reversals`, `GET /v1/accounts/{id}/statement`, `GET /v1/requests/{key}`, `POST /v1/periods/{id}/close`, `POST /v1/statements`, and reconciliation review endpoints. Use keyset statement pagination with a stable ordering token, explicit monetary strings, and structured error codes. A statement response identifies its consistency boundary and applied filters.

Import bounded CSV files with content hashes and source-row identities. Model matches as explicit links with uniqueness constraints preventing a statement row or candidate posting being consumed twice. Automatch only an unambiguous one-to-one reference/currency/amount match. Multiple candidates enter an ambiguity queue; amount-only coincidence never authorizes an automatic match. Manual resolution records actor, reason, source evidence, and time. Malformed rows receive visible rejection reasons. Exported CSV must neutralize spreadsheet formula injection. Importing a file twice preserves the same reconciliation results.

#### Build sequence and proof

1. Write the accounting vocabulary, signed-balance examples, invariants, schema diagram, and two ADRs covering the transactional boundary and reporting consistency. Implement a pure in-memory reference model and property generators first.
2. Implement schema migrations and the atomic database posting operation. Exercise actual runtime-role permissions, closed-period rejection, currency mismatch, and replayed keys before exposing HTTP routes.
3. Add API authorization and current statements, then reversals and period closing. Test multiple API instances writing to the same PostgreSQL database.
4. Add durable outbox reporting, shadow replay, and reconciliation. Finish the failure demonstration, performance workload, operational runbook, and walkthrough.

Use generated valid and invalid journals to compare the SQL engine with the independent reference model. Race 64 same-key submissions, concurrent reversals, and a close-period operation against writers. Inject process termination before commit and after commit but before HTTP response. Test tenant-crossing identifiers, SQL injection attempts, oversized imports, duplicate source rows, and projection redelivery. Restore a database backup into a clean instance and rerun the accounting invariant audit. Unit mocks cannot substitute for these database tests.

#### Workload, demonstration, and acceptance

Provide a seeded workload of 100,000 journals across 20 tenants and 10,000 accounts, including multi-line journals, CAD/USD/JPY, deliberate duplicate requests, and known reversals. Supply a fixture-derived expected balance file generated by the independent reference model. Run 1, 8, and 32 concurrent writers with bounded pools. Record machine details, database settings, throughput, p50/p95/p99 latency, retries, lock waits, projection lag, and peak memory; these are measurements to collect, not promised benchmark results.

The five-minute demo posts a balanced journal, rejects an unbalanced one, repeats a request, kills a writer after commit, recovers its receipt, reverses the journal, imports a statement twice, displays an ambiguous match, and rebuilds reporting. Expected results are exactly one original posting per idempotent request, zero debit/credit imbalance, matching replayed balances, and visible unresolved ambiguity.

- **P01-A1:** Domain/property and real-PostgreSQL tests demonstrate all stated posting and reversal invariants.
- **P01-A2:** Concurrent duplicates, tenant isolation, period closing, and crash/retry scenarios pass with preserved receipts.
- **P01-A3:** Reconciliation fixtures distinguish matched, unmatched, duplicate, invalid, and ambiguous rows without fabricated matches.
- **P01-A4:** Shadow replay and restore audit reproduce the source-derived balances.
- **P01-A5:** A clean checkout can run the scripted demo and workload; evidence includes actual commands, commit SHA, seeds, raw results, and limitations.

### Project 02 — DurableFlow: a durable workflow execution engine

**Assigned owner:** A02. **Exclusive directory:** `projects/02-durableflow/`. **Hiring evidence:** persistent state machines, asynchronous orchestration, worker coordination, crash recovery, compatibility, and honest handling of uncertain external effects. Implement the scheduler and execution semantics yourself. Temporal, Camunda, and other workflow engines cannot supply the core runtime.

#### Product and modules

Build `workflow-model`, `workflow-engine`, `workflow-persistence`, `workflow-worker-sdk`, `workflow-api`, and `workflow-verification`. Keep the deterministic interpreter independent of Spring. Use Java, Spring Boot, PostgreSQL, JDBC, Flyway, Jackson with restrictive configuration, JUnit Jupiter, jqwik, Testcontainers, and a controllable fake HTTP provider. Project-owned scripts start an API, two schedulers, several trusted workers, PostgreSQL, and the fake provider. Core workers execute only activities compiled into this repository.

Accept immutable, versioned JSON workflow definitions with a small typed DSL: activity, durable timer, signal wait, pure branch, and bounded parallel-all. Add explicit compensation references to suitable activities. Support one finite, bounded graph per execution; validate reachability, references, type compatibility, maximum nodes, parallel fan-out, and acyclicity before activation. Expressions use a small parsed whitelist; never JavaScript, SpEL, reflection, shell commands, uploaded JARs, or arbitrary class names. Demonstrate fictional order fulfilment and media-processing workflows with synthetic data.

#### Durable state and execution contract

Create `workflow_definition`, `workflow_instance`, `workflow_history`, `activity_task`, `activity_attempt`, `timer`, `signal_inbox`, `compensation_obligation`, and `operator_decision`. Every instance pins the definition content hash and engine-semantic version. Events have unique `(instance_id, sequence)` ordering. Append history and update the current state/materialized scheduling records in one transaction guarded by instance version. A transaction either records the decision and its tasks or records neither.

Make the interpreter a pure function of a validated definition, recorded history, and a typed new event. Time, generated identifiers, external results, and branch-relevant values must be recorded inputs. Deterministic decision identifiers prevent replay from creating a second logical task. Replaying history reconstructs decisions without invoking activities. Keep definition versions and compatible worker implementations available while executions reference them. An incompatible definition change creates a new version; it does not mutate running instances.

Define legal state transitions for workflows and tasks, including `RUNNING`, `WAITING`, `COMPLETED`, `FAILED`, `CANCEL_REQUESTED`, `COMPENSATING`, `COMPENSATION_FAILED`, and `MANUAL_REVIEW`. Specify signal-versus-timeout races: the first valid transition serialized by the instance transaction wins; retain the losing event as rejected or late evidence. Cancellation is cooperative and cannot undo effects already performed.

#### Worker leasing and uncertain outcomes

Claim ready work in short PostgreSQL transactions using bounded batches and `FOR UPDATE SKIP LOCKED`; PostgreSQL documents its queue-consumer use and its inconsistent-view limitation, so never use it to infer that all work is finished. See [PostgreSQL SELECT](https://www.postgresql.org/docs/current/sql-select.html). Claims increment a fencing generation and record worker identity and lease deadline. Completion must match instance, logical task, attempt, and current generation. A delayed worker cannot overwrite the result of a successor. Use database time for durable deadlines and injected time in interpreter tests; document clock-change effects on scheduling latency.

Use bounded worker concurrency, queue limits, heartbeat intervals, retry caps, deadlines, exponential backoff, and seeded jitter. Pure and provider-idempotent work may be reassigned after lease expiry, but expiry does not prove the previous worker stopped. Before a non-idempotent external call, the worker durably records dispatch intent in a transaction that verifies its current fencing generation; it performs no external I/O if that transaction fails. If dispatch intent exists without a definitive result when the lease expires, transition to `UNKNOWN_OUTCOME` instead of dispatching another attempt. Work without dispatch intent may be reassigned; stale workers cannot acquire dispatch authorization. Fencing protects engine state. Preventing duplicate external effects additionally requires a cooperating provider or an activity that is intrinsically safe to repeat.

Each activity declares `PURE`, `IDEMPOTENT_WITH_KEY`, or `NON_IDEMPOTENT`. An idempotent provider receives a stable key derived from instance and logical activity, retained across attempts. Its result lookup and retention contract must be explicit. An uncertain non-idempotent call becomes `UNKNOWN_OUTCOME` and pauses automatic progression for reconciliation or operator resolution. Never translate a timeout into “the effect did not happen,” and never blindly rerun such an activity. A retry with changed parameters conflicts. These design choices follow the failure cases discussed in [AWS's idempotent API guidance](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/).

Record compensation obligations when eligible forward steps complete. Define reverse recorded-completion order for compensating parallel work, and explain its domain assumptions. Compensation is another durable, retryable activity with its own idempotency key and possible uncertain outcome. It is a business action, not an atomic rollback. A failed compensation remains visible with its evidence and authorized next action.

#### API, security, and implementation sequence

Provide `POST /v1/definitions`, `POST /v1/executions`, `GET /v1/executions/{id}`, `GET /v1/executions/{id}/history`, signal submission, cancellation, and operator-resolution APIs. Start and signal requests require idempotency keys. Worker APIs claim tasks, heartbeat, complete, and report failure using generation-bearing credentials. Enforce tenant scope and separate deployer, operator, viewer, and worker permissions. Restrict provider destinations to configured endpoints; caller-supplied URLs must not enable SSRF. Cap payloads, history size, and queued work per tenant; redact secrets from history and traces.

1. Implement the DSL validator, transition table, pure interpreter, reference fixtures, and replay tests. Produce an ADR distinguishing recorded state consistency from external-effect guarantees.
2. Add schema migrations, versioned history, atomic scheduling, durable timers, and idempotent start/signal delivery. Build restart recovery before distributing workers.
3. Add leases and generations, then the fake provider's deliberate failure modes: reject before effect, apply then disconnect, delay past lease, and return duplicate response.
4. Add bounded parallel execution, cancellation, compensation, version compatibility, operator resolution, observability, and the scripted evidence bundle.

#### Correctness and observable proof

Generate histories and crash schedules; compare execution decisions to a separately specified transition model. Kill schedulers between history append and transaction commit, and kill workers between external effect and result acknowledgement. Duplicate and reorder completions, heartbeat late, expire a lease, restart all components, and race signals with timers. A stale generation must produce a clear rejection with no state change. Test thousands of dormant timers with no sleeping thread per timer. Confirm every replay yields the same logical task identifiers and terminal state.

Do not “prove exactly once” by counting only successful HTTP responses. Inspect the fake provider's independent effect log. For its idempotent mode, repeated dispatch may occur but one logical effect must exist. For its non-idempotent uncertain mode, require visible `UNKNOWN_OUTCOME` and no automatic second invocation. Show a permitted operator resolution as a new audited decision, never an edit to history.

#### Workload, demonstration, and acceptance

Provide 10,000 seeded executions with eight activities each, 20% transient first-attempt failures, duplicate signals, two scheduler processes, eight worker processes, and 5,000 timer waits. Keep a smaller CI profile. Record schedule-to-start delay, completion latency, recovery delay, lease expirations, queue depth, attempts per logical task, memory, and database growth. Timer lateness and throughput are measured outcomes; establish a reference-machine regression budget after the first honest baseline.

The demo restarts the scheduler during a wait, kills a worker after the fake provider applies an effect, rejects its stale completion, safely recovers an idempotent result, and pauses a non-idempotent unknown outcome. Then show compensation failing visibly and a definition upgrade leaving an old running execution pinned.

- **P02-A1:** Replay and state-model tests produce identical logical decisions across supported histories and restarts.
- **P02-A2:** Duplicate starts/signals, expired leases, and stale completions cannot create duplicate logical tasks or corrupt state.
- **P02-A3:** External-effect tests verify provider logs and expose uncertainty without unsupported exactly-once claims.
- **P02-A4:** Timer, parallel, cancellation, compensation, and definition-version race cases have executable evidence.
- **P02-A5:** The bounded workload and demo run from a clean checkout with raw measurements, failure timelines, and an operator runbook.

### Project 03 — QuorumKV: a replicated key-value store with a from-scratch Raft core

**Assigned owner:** A03. **Exclusive directory:** `projects/03-quorumkv/`. **Hiring evidence:** protocol design, persistence ordering, deterministic simulation, correctness arguments, concurrency control, and JVM systems programming. Build a rigorous educational implementation with observable limits. Do not present it as a production database or use a Raft library, etcd, ZooKeeper, or PostgreSQL as its consensus/storage engine.

#### System and module boundaries

Create `raft-core`, `raft-storage`, `raft-transport`, `kv-server`, `kv-client`, and `raft-verification`. Use plain Java for the core, Java NIO/FileChannel for persistence, Netty with a bounded versioned binary codec for networking, JUnit Jupiter and jqwik for tests, and an independent history-checking implementation in verification. Spring is unnecessary in the protocol. Use three fixed, authenticated members with explicit node IDs and cluster ID. Each member has its own process and durable directory in the local demonstration; host and physical-disk failures remain shared risks.

Support byte-key `PUT`, `GET`, `DELETE`, and compare-and-set over an ordered in-memory map backed by a replicated log and snapshots. Cap keys, values, requests, total retained data, clients, and replication queues. Publish limits and return explicit resource exhaustion. No transactions spanning keys, TTLs, dynamic membership, Byzantine tolerance, cross-region availability, or unlimited-scale claims. Safety assumes non-Byzantine nodes and stable storage that honors the documented flush/rename contract. Liveness additionally needs a communicating majority and sufficiently timely scheduling.

#### Protocol and concurrency design

Implement `RequestVote`, `AppendEntries`, and chunked `InstallSnapshot`, including terms, log coordinates, request correlation, and cluster identity. Use one state-machine owner per node. Network/disk adapters submit typed events; they do not mutate protocol fields. The core emits effects such as persist, send, schedule, and apply. Persistence acknowledgements are explicit events, so simulation can kill a node before a write becomes durable. Bound pending work and cancel obsolete term-specific effects safely.

Use the [Raft author paper](https://raft.github.io/raft.pdf), particularly Figure 2 and Sections 5 and 8, as the protocol reference. Implement vote restrictions, log matching, higher-term step-down, election timing, conflict repair, and current-term majority commitment. Never acknowledge a persistent-state change before the required storage flush, and never apply beyond the committed index. A new leader commits its current-term marker before serving reads. For every linearizable read, confirm leadership with a fresh correlated majority exchange and wait until the state machine has applied the captured safe index. An isolated former leader cannot answer from its local map. These conditions matter even when no client write is in progress.

Ignore stale RPC responses using term, peer, request identity, and progress checks. A delayed rejection cannot undo later replication progress. Empty heartbeats must not truncate a follower's log. Keep timeouts for progress separate from the safety argument. Do not introduce clock-based read leases as an optimization. Document why ordinary leadership flags and recent heartbeats are insufficient evidence for a read.

#### WAL, snapshots, and crash recovery

Define and document a versioned WAL with record length, type, index/term where relevant, payload length, and checksum. Persist term/vote updates before dependent replies, and entries before successful append acknowledgements. Make log replacement/truncation crash-safe; implement truncation markers or a durable segment-generation manifest rather than unsafe in-place overwrite. One process holds the directory lock. An incompatible format fails startup with an actionable message.

Take snapshots only from a stable applied state. Include last-included index/term, cluster configuration, state-machine contents, and deduplication state. Write a temporary snapshot, verify it, flush it, atomically publish its manifest under the documented filesystem contract, then retire covered log segments. Do not delete the old recovery path before the new one is durable. Retain valid fallback artifacts until publication finishes. Reject incomplete or mismatched snapshot transfers; permit interrupted transfers to restart without activating partial state.

Recovery validates checksums, indexes, manifest relationships, and contiguous retained log ranges. A torn final record can be discarded only under an explicitly tested incomplete-write tail-recovery rule. Interior corruption and a complete final record with an invalid checksum both cause fail-closed startup with evidence, never silent skipping. Tail recovery must not discard a possibly acknowledged complete record. Reconstruct snapshot state and replay entries only when their commit status is safely established. A restarted node cannot infer commit merely because entries exist locally. State the supported filesystem and disk-cache assumptions in the runbook. The [etcd Raft integration documentation](https://github.com/etcd-io/raft) is a primary implementation reference for reviewing persistence/message ordering; do not import its implementation.

#### Client semantics and threat model

Each mutating request carries `clientId`, monotonic sequence, and payload digest. The supplied client allows one outstanding mutation per identity and preserves its retry identity. Replicate the latest accepted sequence, digest, and result with the key-value state, and include them in snapshots. Repeating that sequence returns the recorded result; changing its payload conflicts; older sequences return a stale-request error without mutation. Reject gaps. Do not expire sessions silently: cap demo client identities and document the absence of session garbage collection.

A failed compare-and-set returns its committed observed value and performs no mutation. A timeout is an unknown outcome; the client retries the same mutation identity rather than inventing another. Followers return an explicit leader hint or unavailability. Separate administrative observation endpoints from linearizable key reads. Authenticate member identities, constrain all frame lengths before allocation, reject unknown cluster IDs, keep control endpoints local by default, and never deserialize arbitrary Java objects. Cryptographic transport is supplied by standard TLS libraries, not custom cryptography.

#### Build sequence and verification

1. Implement pure state transitions and a seeded virtual-time simulator with reorder, duplication, loss, partition, pause, crash, restart, and independent volatile/durable storage models.
2. Add election, replication, and commit tests, including divergent logs and an old-term majority-replication counterexample. Assert election safety, committed-prefix agreement, monotonic application, and consistent application results after every simulated event.
3. Implement WAL and snapshots, injecting crashes at each persistence boundary. Add real-process crash recovery separately from the virtual storage model.
4. Add client deduplication, quorum reads, compare-and-set, bounded transport, real three-process operation, observability, and measured workloads. Keep this sequence; a working HTTP demo is not evidence of consensus safety.

Run at least 1,000 recorded simulation seeds in the extended profile and retain minimized traces for every discovered failure. Also maintain named regression scenarios so fixed bugs never depend on rediscovering a random seed. Check short concurrent per-key histories against an independent sequential register/CAS model using invocation and response intervals. Treat timed-out operations as potentially completed; never erase them from histories. Bound search and report `INCONCLUSIVE` when exhausted. An inconclusive result cannot satisfy a passing correctness gate, and large histories cannot be called formally verified by extrapolation.

#### Workload, demonstration, and acceptance

Benchmark 100,000 1-KiB operations at 1, 8, and 32 clients with separate read-heavy and mixed profiles; serialize each client's mutation sequence. Record hardware, fsync policy, payload sizes, throughput, latency percentiles, GC, replication lag, snapshot duration, and time to regain progress after a leader failure. Supply exact commands and raw output. These are workload definitions, not invented performance guarantees.

The demo partitions the leader, verifies its reads/writes cannot succeed without a majority, progresses on the remaining two nodes, heals the network, compares state digests, snapshots, terminates all processes, restarts, and retrieves acknowledged values. Include a small checked history and a separately reported throughput run. Three processes on one laptop do not demonstrate survival of a whole-machine failure.

- **P03-A1:** Named Raft safety regressions and the seeded simulator pass without ignored events or disabled invariants.
- **P03-A2:** WAL, term/vote, truncation, snapshot-publication, and corruption tests cover explicit crash boundaries.
- **P03-A3:** Partitioned reads, unknown mutation outcomes, deduplication after restart, and compare-and-set histories pass the bounded checker.
- **P03-A4:** The real three-process fault demonstration preserves acknowledged data under the documented storage assumptions.
- **P03-A5:** Benchmarks are reproducible and honestly labelled; the README distinguishes tested evidence from protocol arguments, assumptions, and unsupported features.

### Project 04 — PulseStream: event-time analytics with recoverable state

**Assigned agent:** A04. **Package root:** `io.github.cheshmakzanoon.portfolio.pulsestream`.

**Exclusive ownership:** `projects/04-pulsestream/**`. Build a telemetry analytics service whose original Java engine implements incremental windows, watermarks, late corrections, bounded state, and crash recovery. This is evidence of reasoning about time and partial failure: a hiring reviewer must be able to crash the service between two persistence steps and explain why the visible totals remain correct. Kafka is the transport and PostgreSQL the durable sink; neither supplies your window algorithm. Do not replace the engine with Kafka Streams, Flink, or a framework aggregation operator.

#### Modules, stack, and interfaces

Use Java 25, the repository Maven parent, Kafka's Java consumer/producer clients, PostgreSQL JDBC with Flyway, and Spring Boot for administration/query APIs only. Use repository-approved stable dependency pins, Testcontainers for Kafka/PostgreSQL, jqwik for generated histories, and JMH for isolated engine measurements.

| Owned Maven module | Responsibility |
| --- | --- |
| `engine/` | Pure event/window types, pane aggregation, watermark state machine, deterministic transition functions; no Spring or database dependency. |
| `kafka-adapter/` | Versioned event codec, partition validation, one consumer poll loop, pause/resume, bounded delivery to the engine. |
| `postgres-store/` | Schema migrations, owner fencing, atomic state/result/checkpoint transactions, outbox. |
| `service/` | Lifecycle, HTTP queries, operational metrics, health, configuration. |
| `verification/` | Seeded producer, independent batch oracle, process crash controller, replay comparison and workload runner. |

Place specifications in `contracts/`, dataset manifests in `fixtures/`, and scripts in `scripts/`. Prefix package names using the repository convention plus `.pulsestream`.

An input record contains `schemaVersion`, `runId`, `eventId`, `tenantId`, `sensorId`, `eventTimeEpochMillis`, and integral `valueMilliUnits`. The Kafka key is exactly `tenantId + ":" + sensorId`. Fix the topic partition count for a run; validate routing instead of silently accepting a key on another partition. Permit an explicit, validated source-progress record, distinct from a measurement. No floating-point sums.

Expose `GET /v1/runs/{run}/windows?tenant=...&sensor=...&type=...`, returning start/end timestamps, count, sum, minimum, maximum, revision and finality; `GET /v1/runs/{run}/status`, returning watermark, partition activity and committed checkpoint; and a paginated diagnostic endpoint for quarantined records. Operational controls bind to the local development profile. All result queries require the project authentication/tenant convention if exposed beyond localhost.

#### Implementation phases and invariants

1. **Specify time, then implement the pure engine.** Implement one-minute tumbling windows and five-minute sliding windows stepping every minute. Both use half-open intervals `[start,end)`. Build reusable one-minute panes with count/sum/min/max and merge panes for sliding results. Compare against a deliberately simple batch oracle before adding infrastructure. Detect arithmetic overflow; invalid measurements enter quarantine without modifying aggregates.
2. **Implement progress and late corrections.** For each non-idle partition, track its largest valid source timestamp and subtract five seconds of allowed disorder. Job watermark is the minimum of active partition candidates, clamped so it never decreases. An unseen partition blocks initial progress until observed or explicitly classified idle. Mark a partition idle only after thirty seconds of demonstrated source silence while consumption is eligible to make progress; exclude it from the minimum. A deliberately paused partition, locally queued records, or a known unconsumed broker backlog is not a silent source. Define how lag/source-progress observations establish idleness, and test pause/backlog separately from genuine silence. If every partition is idle, hold the watermark. Processing time does not become event time. Reject implausible future timestamps under an explicit configurable clock-skew limit.
3. **Implement window lifecycle.** Publish a provisional result once watermark reaches window end. Accept a late contribution to that window only while `watermark < end + 30 seconds`; increment its revision and update the provisional result. Finalize at or beyond that threshold and never reopen it. Retain a shared pane until every dependent sliding window has finalized. A resumed idle partition cannot lower the watermark. Route contributions to already-finalized windows into a durable late-record table. Decide lateness separately for every assigned sliding window: one event can be too late for one window and valid for another.
4. **Add durable batches and restart.** Persist engine state, partition progress, watermark and outputs together as described below. Restore committed state before seeking Kafka. Restart conservatively treats partitions as active until fresh activity or a full idle timeout establishes idleness; retain the committed watermark. Demonstrate recovery before performance work.
5. **Add bounded operation and evidence.** Enforce a 50,000-key admission quota, a configurable open-pane cap, bounded fetched batches and a bounded engine queue. Reject/quarantine new over-quota keys with explicit reasons; never evict unfinished state silently. Publish source lag, checkpoint age, watermark lag, queue utilization, active/idle counts, duplicate count and per-window late contributions.

#### Atomicity, ownership, and failure behavior

Support **one active processor per run**, with a fixed set of manually assigned Kafka partitions. One engine thread owns mutable window state; one separate poll thread owns all consumer methods. The poll thread pauses inputs before the bounded queue fills, while continuing required polling; database failure stops progress without dropping queued records. No elastic distributed execution claim is allowed.

Maintain `run_owner(run_id, epoch, lease_until)`, `checkpoint(run_id, topic_id, partition_id, next_offset)`, `pane_state`, `window_result`, `seen_event`, `quarantine`, and `outbox`. Include run/tenant identifiers in relevant keys. Acquire ownership and increment its epoch while locking the owner row; use database time for leases. Every commit locks that row, verifies the current epoch, and updates progress. An obsolete process must fail its write after takeover.

For each deterministic batch, one PostgreSQL transaction commits event-ID decisions, state deltas, result revisions, late/quarantine rows, all affected next offsets, and outbox rows. Publish new in-memory committed state only after database success, or restore it on rollback. A Kafka offset mirror may advance afterward; it is never the recovery authority. Store **the next position**, allowing gaps in Kafka offsets; do not confuse the last consumed offset with the restart position. Topic identity protects against delete/recreate ambiguity. An expired broker retention range fails startup visibly, rather than jumping forward.

Deduplication is scoped to a finite run: `(run_id,tenant_id,event_id)` retains its payload hash until that run is explicitly archived. Identical retries contribute zero; conflicting payloads under one ID are quarantined. Set a configurable durable run-event quota and document its disk growth. Do not silently introduce an expiring dedup cache. Outbox publication is at least once; use `(run_id,window_id,revision)` as its idempotency identity. Claim atomic PostgreSQL-visible results/checkpoints within this boundary, never global exactly-once delivery.

#### Verification, workload, and reviewer demo

Property histories cover reordered timestamps, offset gaps, retries, malformed records, exact interval edges, sliding-window overlap, partition silence/resumption, all-idle behavior and overflow. Kill the real service before SQL commit, after SQL commit/before Kafka offset mirroring, and during outbox delivery. Test fenced former owners, unavailable PostgreSQL, exhausted quotas and unavailable retained source positions. Compare recovered committed aggregates with the oracle using recorded arrival/control schedules and barrier-controlled fixtures; arbitrary new cross-partition interleavings can legitimately change late classification. Test externally observable output, not implementation-private counters alone.

The workload uses a fixed seed, eight partitions, 10,000 keys and one million measurements, with a separately specified duplicate/reordering schedule. Report actual accepted events/second, p50/p95/p99 checkpoint latency, maximum queue depth, heap, database size and recovery time, with machine/JDK configuration. Compare engine-only JMH results with end-to-end durable processing; never label one as the other.

Provide a **three-to-five-minute demo**, using an injected clock and recorded source order. For sensor `a`, send values `10`, `20` into the first minute; advance watermark to second 60; then send value `5` timestamped at second 50. Crash after its database commit before Kafka mirroring and restart. Make its partition idle while another advances watermark to second 100; resume with an event timestamped at second 40, then repeat the earlier `20` event ID. Expected evidence: first-window `count=3`, `sum=35`, `final=true`; one logical duplicate; one rejected late tumbling-window contribution; recovery digest equals the oracle. List overlapping sliding-window outcomes separately. These are required future outputs, not pre-existing measurements.

| Acceptance ID | Required proof |
| --- | --- |
| `P04-A1` | Both original window algorithms agree with the independent oracle across generated boundary cases. |
| `P04-A2` | Idle/resume and restart never move watermark backward or mutate a finalized window. |
| `P04-A3` | Every prescribed crash leaves SQL results and checkpoints consistent; obsolete owner writes fail. |
| `P04-A4` | Dedup scope, quotas, outbox retries and retention-gap behavior are tested and documented. |
| `P04-A5` | Bounded workload report, demo transcript and recovery digests are reproducible. |

**Excluded:** arbitrary SQL, stream joins, session windows, distributed rescaling, ML anomaly detection and a graphical dashboard until these gates pass. Consult [KafkaConsumer ownership, pause/resume and external offsets](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html) and [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html) for integration semantics; the window rules above are this project's explicit design.

### Project 05 — TenantShield: explainable multi-tenant authorization

**Assigned agent:** A05. **Package root:** `io.github.cheshmakzanoon.portfolio.tenantshield`.

**Exclusive ownership:** `projects/05-tenantshield/**`. Build an authorization decision service for a fictional document-management SaaS. The original contribution is a typed policy compiler/evaluator, tenant-safe data access, role inheritance, deterministic conflict resolution, consistent publication, and evidence of revocation behavior. A reviewer should see why a request is allowed, which version authorized it, and how another tenant is prevented from influencing that decision.

Authentication belongs to a mature external OIDC issuer. Use a reproducible local Keycloak realm fixture, Spring Security OAuth2 Resource Server and its JWT verification. Do not write an identity provider, password database, JWT signature implementation, login protocol, or a homemade cryptographic primitive.

#### Modules, stack, and interfaces

Use Java 25, the Maven parent, the repository Spring Boot baseline, Spring Security, PostgreSQL JDBC/Flyway, Caffeine for immutable compiled policies, and the repository metrics/testing tools. Verify and pin compatible versions during execution.

| Owned Maven module | Responsibility |
| --- | --- |
| `policy-core/` | Typed AST, restricted parser, type checker, role closure, indexed evaluator and explanation types; no Spring. |
| `policy-store/` | Tenant-scoped persistence, immutable policy revisions, transactional publication, audit records. |
| `decision-service/` | Resource-server security, request binding, consistent decision snapshots, quotas and APIs. |
| `demo-resource-server/` | A small protected document API that obtains a decision for each protected operation. |
| `verification/` | Adversarial JWT/tenant tests, generated policy cases, reference evaluator, concurrent publication tests and load driver. |

Keep OpenAPI/JSON Schemas and the grammar under `contracts/`; Keycloak fixture under `infra/`; policy examples under `fixtures/`; walkthrough under `docs/`. Use the repository package convention plus `.tenantshield`.

Minimum interfaces are `POST /v1/tenants/{tenantId}/decisions`, `POST .../policy-versions`, `POST .../policy-versions/{version}/publish`, `PUT .../memberships/{subjectId}`, and `POST .../simulate`. The simulation endpoint is privileged and side-effect free; it does not grant permissions. Publication requires an expected prior version/ETag; conflicts return `409` or `412` according to the documented contract.

A normal decision request is `{ "action":"document:read", "resource":{"type":"document","id":"doc-7"}, "context":{"purpose":"support"} }`. Obtain subject identity from the validated token, never a free-form `subjectId` in this body. Resolve resource attributes by tenant-scoped ID on the server. Return `{ "decision":"ALLOW", "reason":"MATCHED_ALLOW", "tenantEpoch":42, "policyVersion":7, "resourceVersion":3, "matchedRuleIds":["read-department"], "decisionId":"..." }`. An unavailable dependency returns an explicit error and never an allow; the protected API must refuse the operation.

#### Implement the policy language personally

1. **Define typed inputs and parsing.** Implement a tokenizer, recursive-descent parser, typed AST and bounded evaluator. The grammar supports parentheses, `and`, `or`, `not`, `==`, `!=`, ordered comparisons, `in`, `exists(field)`, and the single role predicate `hasRole("name")`. Values are booleans, bounded strings, signed integers, enums and bounded literal sets. Allow only named `subject.*`, `resource.*` and approved `context.*` fields. No reflection, Java/SpEL/Groovy execution, regexes, loops, dynamic imports, HTTP calls or arbitrary functions. Bound input bytes, AST nodes, nesting, literal sizes and evaluation work. Reject invalid types during publication.
2. **Implement roles and attributes.** Use a tenant-local role DAG; compute transitive closure and reject cycles. A user receives the permissions of assigned roles and their ancestors under an explicitly illustrated inheritance direction. Build an action/resource-type candidate index, then evaluate ABAC constraints. Example permit condition: `hasRole("reader") and resource.department == subject.department and subject.clearance >= resource.classification`. Subject attributes and resource classifications are server-owned; caller-supplied context cannot overwrite them.
3. **Specify conflict handling.** Require an effective RBAC grant and a matching ABAC allow. Any applicable explicit deny wins. Comparisons with missing values return `UNKNOWN`; `not UNKNOWN` remains `UNKNOWN`, while `exists` reports presence. An applicable rule with an unknown/error result denies conservatively with `INDETERMINATE`, even if another rule permits. Otherwise permit only when the grant and allow conditions hold; default deny. Define applicability using action/resource type, before condition evaluation. Use stable rule IDs and deterministic ordering in explanations. Do not make authorization depend on hash-map iteration order.
4. **Add versioned publication and governance.** Compile and validate an immutable draft, execute its regression examples, and publish it by changing one tenant pointer inside a transaction. Memberships, active policy, security-relevant attributes and role changes increment a monotonic tenant authorization epoch. Rollback copies selected older policy contents into a new immutable policy version and publishes it at a higher tenant epoch; both published policy version and epoch remain monotonic.
5. **Integrate enforcement and prove isolation.** Connect the resource API, then exercise real token verification, cross-tenant requests, revocation and concurrent traffic before tuning caches.

#### Tenant binding, consistency, and concurrency

Configure a trusted issuer allowlist, expected audience, accepted signature algorithms and required token times. Unverified token claims must not initiate discovery against arbitrary URLs. Identify a principal by `(issuer,subject)` and bind the route tenant through its current server-side membership; a header or token role string alone does not establish tenant access. Administrative actions require a separate, explicit current administrative permission.

Use composite tenant keys and foreign keys for `membership`, `role`, `role_edge`, `grant`, `resource`, `policy_version`, `active_policy`, and `audit`. Add PostgreSQL row-level security with a runtime role that is neither the table owner nor allowed to bypass RLS. Set transaction-local tenant context; test alternating tenants through reused pooled connections. Reserve schema/migration privileges for a separate role. A global debug endpoint must not bypass these boundaries.

Default to **fresh decisions**: begin one repeatable-read database transaction and read the tenant epoch, membership, active version and resource attributes from its consistent snapshot. Cache compiled ASTs by `(tenantId,immutablePolicyVersion,contentDigest)`, with bounded size. Do not cache allow decisions across requests in the baseline. A policy-cache hit cannot skip the database epoch/membership read. Policy publication does not mutate an AST being evaluated by another thread.

Revocation semantics are explicit: a decision whose snapshot begins after a successful revocation commit must observe the new epoch and permissions. A request already evaluating an older snapshot can complete under that snapshot. Return the observed epoch/version; never promise instantaneous cancellation of in-flight actions. The sample enforcement point performs a fresh decision for each operation. Document that a remote authorization response alone cannot make a later resource write atomic with policy changes; applications requiring that stronger contract must coordinate version checks with their resource transaction.

Enforce bounded concurrent requests, body sizes, policy sizes and per-tenant rate limits. Isolate cache keys and quotas by tenant. Store a redacted audit record containing decision ID, principal reference, action, resource reference, epoch, result and reason; never log bearer tokens or complete sensitive attribute sets. Audit publication/membership mutations atomically with their state changes. If decision-audit persistence is required by the configured profile, its failure also prevents an allow response.

#### Verification, workload, and reviewer demo

Test forged signatures, wrong audience/issuer, expired/not-yet-valid tokens, arbitrary issuer discovery attempts, unregistered subjects, mixed tenant IDs, cross-tenant resource IDs and pooled-connection leakage. Generate policy expressions and compare the indexed evaluator to a simple AST interpreter; cover unknown values, negation, deny precedence, role cycles, parser limits and deterministic traces. Concurrently publish/revoke while evaluating; assert the documented snapshot boundary and immutable-cache isolation. Include database outage and cache-eviction behavior.

The seeded workload uses fifty tenants, 200 policies per tenant, 100 roles, 1,000 resources and 100,000 decision requests. Separate cold compilation, warm AST evaluation and authenticated database-backed HTTP measurements. Record actual throughput, p50/p95/p99 latency, query counts, cache size, denials, error counts and maximum in-flight requests; disclose hardware and connection-pool settings.

The **three-to-five-minute demo** creates tenants `harbor` and `orchard`, obtains real development tokens, and publishes a department/clearance policy. Show `harbor/alice -> harbor/doc-7 = ALLOW`; the same principal against `orchard/doc-7 = DENY`; mismatched department `DENY`; missing classification `DENY/INDETERMINATE`; and an explicit suspension rule overriding an otherwise valid allow. Remove Alice's membership, wait for that mutation's successful response, then start a new decision: `DENY`, even with a warmed AST cache; its authorized audit view shows the higher tenant epoch. Finish by requesting through the protected resource API and showing its denied HTTP response. Assertion scripts must check all outcomes automatically.

| Acceptance ID | Required proof |
| --- | --- |
| `P05-A1` | Original parser/compiler/evaluator agrees with the reference interpreter and enforces resource bounds. |
| `P05-A2` | Real OIDC verification and tenant binding defeat the specified adversarial requests. |
| `P05-A3` | Roles, unknown attributes, explicit denies and policy publication have deterministic tested semantics. |
| `P05-A4` | Post-revocation snapshots deny correctly; versioned cache and pooled connections never cross tenants. |
| `P05-A5` | Protected-resource demo, concurrent workload and redacted audit evidence are reproducible. |

**Excluded:** a universal policy language, authorization-as-a-service commercial claims, custom cryptography, cross-tenant role inheritance, a new identity provider and generalized relationship-graph traversal. Integration references: [Spring Security JWT resource servers](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [trusted issuer multi-tenancy](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/multitenancy.html), and [PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html). Policy grammar and conflict rules above are project choices, not claims about those libraries.

### Project 06 — MatchCore: deterministic order matching and replay laboratory

**Assigned agent:** A06. **Package root:** `io.github.cheshmakzanoon.portfolio.matchcore`.

**Exclusive ownership:** `projects/06-matchcore/**`. Build a single-instrument simulated exchange demonstrating data structures, deterministic state transitions, numerical correctness, risk reservation, NIO durability and credible performance measurement. A reviewer must inspect an original matching algorithm, replay a journal, and account for every unit of simulated cash and inventory. This project has no real exchange connection, trading account, deposits or real-money execution.

#### Modules, stack, and command contract

Use Java 25, Maven, JDK collections/NIO, an established JSON codec, and a minimal HTTP/CLI adapter. Use Jupiter, jqwik, JMH and JFR as appropriate. Spring Boot may host management APIs but must not enter the matching loop. Use mature bounded queues; a custom lock-free queue is outside the core requirement.

| Owned Maven module | Responsibility |
| --- | --- |
| `matching-core/` | Order book, price-time allocation, risk reservations, deterministic command transitions and immutable result types. |
| `journal/` | Versioned framed command log, checksums, full-write loops, force boundary, snapshots and recovery. |
| `gateway/` | Validated order/cancel/replace interfaces, bounded admission, result lookup and immutable depth snapshots. |
| `simulator/` | Seeded participants, independent reference book, fault controller, ledger reconciler and demo CLI. |
| `benchmarks/` | Isolated matching benchmarks and distinct journal/durable-service workload runners. |

Put event schemas in `contracts/`, fixtures in `fixtures/`, decision records in `docs/DECISIONS/`, and demo/replay scripts in `scripts/`. Use the repository package convention plus `.matchcore`.

Define one instrument `DEMO`, tick size `0.01` quote units, and integral quantity lots of one underlying unit. All internal prices, quantities, balances and reservations use checked integers. The adapter may accept decimal strings, but must convert exactly or reject excess precision. Overflow always rejects a command before changing business state; no binary floating-point money.

Example command: `{ "clientCommandId":"c-17", "accountId":"alice", "type":"NEW", "orderId":"a-1", "side":"BUY", "priceTicks":10200, "quantityLots":6, "timeInForce":"GTC" }`. Expose submission, `GET /commands/{id}`, `GET /book?depth=20`, and paginated executions. Bind API accounts to authenticated identities; cancel, replace and private-result reads require ownership. CLI fixtures can use a documented trusted local mode.

#### Matching and risk phases

1. **Implement and specify the book.** Maintain ordered bid/ask price indexes, FIFO linked orders inside each level, and an order-ID-to-node index. Best price wins, then admission priority. Execute at the resting maker's price. Support GTC, IOC and FOK limit orders plus a post-only GTC flag. IOC cancels any unfilled remainder. FOK preflight must prove the complete quantity is executable under the same risk/self-trade rules before changing anything. Post-only rejects an order that would immediately cross. Exclude unpriced market orders.
2. **Implement cancel/replace precisely.** Cancel removes remaining quantity and releases reservations. A quantity-only reduction preserves priority; any increase or price change loses priority and is processed as a new aggressor. Keep the public order ID but assign a new priority sequence/revision. Define replacement quantity as `newRemainingQtyLots`, not ambiguous total quantity; zero is cancellation. Validate the entire replacement and its risk before removing the original; rejection leaves the original unchanged. A replacement of an already-completed order returns an explicit terminal result.
3. **Implement pre-trade risk and reconciliation.** Use cash-only accounts with no borrowing or short sales. A resting buy reserves remaining quantity times its limit; a resting sell reserves inventory. Execution transfers maker-price cash and lots atomically, releasing price improvement and filled reservations. Check limits with exact arithmetic. Define self-trade prevention as canceling the incoming remainder when it encounters its own resting order; earlier fills can stand. FOK treats that blocker as failure and executes nothing. Property tests reconcile aggregate cash and inventory and ensure balances, available funds and remaining quantities never go negative.
4. **Add sequence ownership and durable replay.** One writer exclusively mutates book, reservations, sequence and dedup state. Network threads enqueue validated envelopes into a bounded queue. Arrival/admission sequence determines order; timestamps are display metadata, never tie-breakers. Seeded simulation randomness, risk/configuration changes and initial balances must be recorded as inputs. Replay never reads wall time, network state or a fresh random generator.
5. **Add snapshots, failure handling and profiling.** First prove full-log recovery. Then add snapshots and inspect allocation/CPU behavior with JFR before optimizing. A clever fast path must retain agreement with the reference model.

#### Acknowledgement, journal, deduplication, and reads

Acquire an exclusive journal file lock; a second process cannot start another writer. Allocate a sequence for each new well-formed admitted command, including business rejections. Append framed commands in sequence, handling partial writes. Each frame contains bounded length, schema version, sequence, payload and checksum; check the header independently so corrupted lengths cannot masquerade as torn tails. For a batch, force the journal to local storage **before** applying the batch to committed live state or publishing any result, trade or depth update. Apply durable commands in order, then acknowledge their outcomes. If the process dies between force and application/response, recovery applies the command and a retry retrieves its original result.

An unacknowledged command may survive and execute after recovery; the client must query/retry its stable ID. Do not promise that a timeout means rejection. The idempotency identity is `(runId,accountId,clientCommandId)` with a canonical payload hash: identical retries return the original outcome without another sequence/trade; a different payload under the same ID returns a conflict. Before allocating a sequence, coalesce IDs already pending in the current fsync batch using a bounded pending-ID map or equivalent mechanism; deduplication cannot wait until outcomes are applied. Retain dedup outcomes for the entire finite run, including terminal orders; cap run size and reject further admission explicitly when necessary. Concurrent duplicate submissions must converge on one result. Namespace orders by `(runId,accountId,orderId)` and prohibit reuse after termination within the run; replacement preserves that order identity while changing its revision/priority as specified.

On force/I/O failure, stop admission and enter recovery-required state rather than continue acknowledging from memory. Recovery rejects sequence gaps and checksum errors in complete records; only an incomplete final frame is eligible for documented tail truncation. Test torn frames at every field boundary. Use snapshot version, last sequence and a verified journal-prefix digest. Publish snapshots through a forced temporary file and atomic rename where supported; retain the complete journal in the baseline, so a lost/bad snapshot falls back to full replay. Do not prune required log history as a demo shortcut.

Publish immutable depth/account snapshots with their last applied sequence; readers must not traverse a mutating book. A client needing read-after-ack consistency supplies a minimum sequence or uses a gateway barrier. Bound pending responses and queues; overload returns an explicit retryable response before admission. Document guarantees as process-crash recovery on retained local storage honoring the configured force operation, with no disk-loss tolerance or replicated availability claim.

#### Meaningful verification and workload

Implement a deliberately slow sorted-list reference matcher, independently structured from the production book. Generate commands covering partial fills, multi-level sweeps, FIFO ties, empty book, post-only rejection, FOK atomicity, self-trades, overflow, duplicate conflicts, concurrent retries and every replace rule. Compare trades, open orders and account balances after every command. Reject any discrepancy before benchmarking.

Crash a separate process before append, during a frame, after force/before apply and after apply/before response. Compare replayed state against the complete durable prefix, not merely the set of responses seen by a client. Distinguish SIGKILL process tests from injected storage failures; neither proves real hardware power-loss behavior. Verify snapshot fallback and journal corruption refusal.

Run one million seeded commands across 64 accounts with a declared 60% new/25% cancel/15% replace mix, separately scheduled duplicate injections, a 100,000-open-order cap and a specified price distribution. Report measured core commands/second, durable commands/second, p50/p95/p99 acknowledgement latency, batch size, force frequency, queue depth, allocation/command, heap and recovery time. Include JMH warm-up/forks and full machine/JDK/storage configuration. A memory-only result may not be advertised as durable exchange throughput; never invent a latency number to make the repository look impressive.

#### Three-to-five-minute deterministic demo

Start Alice with `100000` cash ticks and zero inventory. Start Bob, Carol and Dave with at least ten lots each. Execute the following fixture, asserting outcomes rather than only printing a market animation:

1. Bob offers five lots at `10100`; Carol then offers three at `10100`.
2. Alice buys six with limit `10200`: Bob fills five first, then Carol one, all at `10100`.
3. Reduce Carol's remaining two to one; add Dave's one-lot offer at `10100`; increase Carol's remaining quantity to two, moving her behind Dave.
4. Alice buys two at `10100`: Dave fills one before Carol fills one. Retry that identical buy command; trade count does not change.
5. Submit cancellation of Carol's remaining lot, crash after its force/before response, restart and retry the cancellation ID.

Required final evidence: **four executions, eight lots traded, Alice cash `19200`, Alice inventory `8`, no open orders, one durable result for each command ID, and identical replay/reference state digests**. The cancellation may initially have an unknown client outcome, then resolve as canceled through recovery. Demonstrate it explicitly. These are acceptance expectations for the future implementation.

| Acceptance ID | Required proof |
| --- | --- |
| `P06-A1` | Original indexed price-time book and independent reference model agree for generated histories. |
| `P06-A2` | Priority changes, FOK, self-trade prevention and fixed-point risk preserve stated invariants. |
| `P06-A3` | No pre-force result escapes; retry/recovery semantics hold at every crash boundary. |
| `P06-A4` | Corrupt journals fail visibly, snapshots fall back safely, reads expose consistent sequences and admission remains bounded. |
| `P06-A5` | Exact demo totals, replay digest and separately labeled core/durable workload evidence are reproducible. |

**Excluded:** exchange connectivity, real money, FIX certification, market making claims, multi-instrument atomic orders, derivatives, margin, consensus/replication and nanosecond guarantees. Read the official [Java FileChannel contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html) for force/write behavior. The order rules are deliberately defined for this simulator and must not be described as compliance with a particular real exchange's rulebook.

### Project 07 — IndexForge: transactional source and artifact search

**Assigned agent:** A07. **Exclusive ownership:** `projects/07-indexforge/**`. **Package root:** `io.github.cheshmakzanoon.portfolio.indexforge`. Use the repository Java 25, Maven, Spring Boot, dependency-management, and security conventions; request centrally pinned Lucene modules through the coordinator. Deliver an independently runnable application, Java CLI, evaluation harness, and engineering case study.

#### Product and senior engineering evidence

Build a local-first search service for a software organization’s source snapshots, documentation, and build-artifact metadata. A developer can find a method by identifier, an implementation by a natural-language description, or a versioned artifact by coordinates. Demonstrate index lifecycle management, concurrency, reproducible information-retrieval evaluation, authorization, and operational recovery. The substantial work is the ingestion protocol, generation publication, source-aware query planning, ranking experiments, and failure handling. Lucene supplies the inverted index and retrieval primitives; do not describe its implementation as your own.

Accept explicitly configured local snapshot directories, UTF-8 source/document files, and bounded source archives. Artifact records contain coordinates, version, content digest, and associated documentation. Do not crawl networks, execute builds, load archive classes, or discover arbitrary filesystem roots. Include authored fixtures and any third-party fixture licenses. All content is disposable, non-secret demonstration data.

#### Owned modules and file responsibilities

| Module/path | Responsibility and required implementation seams |
|---|---|
| `ingest/` | `SnapshotManifest`, `SafeSourceReader`, `ContentAddressedStore`, `IngestionJob`, `CheckpointStore`; validate inputs, hash content, normalize relative paths, bound work, and resume interrupted ingestion. |
| `index/` | `DocumentMapper`, `CodeIdentifierAnalyzer`, `GenerationBuilder`, `GenerationPublisher`, `GenerationLease`; map fields and control durable, reference-counted index generations. |
| `query/` | `QueryAst`, `QueryPlanner`, `AuthorizationFilter`, `CandidateRetriever`, `Ranker`, `SnippetBuilder`, `SearchCursor`; parse a bounded grammar and produce authorized, explainable results. |
| `service/` | HTTP endpoints, authenticated request context, quotas, cancellation, job status, health, and metrics; no index internals in controllers. |
| `cli/` | `ingest`, `search`, `reindex`, `verify-generation`, and `evaluate` commands using the same application services. |
| `evaluation/`, `fixtures/`, `docs/` | Deterministic workload generator, committed relevance judgments, metric implementation, failure schedules, architecture decisions, operational runbook, and measured results. |

#### Data, publication, and API contract

Give every source document a stable key `(tenantId, repositoryId, normalizedPath)` and record source revision, SHA-256, language, kind, symbol names, content, and schema version. Use exact fields for identifiers/coordinates and analyzed fields for prose and split identifiers; preserve original tokens and source offsets. Document Unicode, case, camel-case, and underscore behavior.

Each tenant has an independently owned index directory. Ingestion registers an immutable manifest and idempotency key; retrying the same key and manifest returns the same job, while different content under that key returns a conflict. A complete source snapshot explicitly lists deletions; a partial upload cannot silently erase missing files. Restrict jobs to configured resource quotas and bounded worker queues.

Build each replacement generation in a separate directory from a complete immutable manifest. Checkpoints are optimization hints; the manifest and committed index metadata are authoritative. Validate document counts and hashes, commit Lucene with manifest/schema metadata, open and warm a committed reader, then publish a checksummed generation pointer using a same-filesystem atomic move. Sync required metadata where supported. Startup validates the pointer and committed generation, quarantines incomplete staging work, and retains the last valid generation. Document the actual filesystem durability assumptions; failure to support the required publication primitive must be explicit.

A request acquires exactly one generation lease, and all retrieval, facets, snippets, and pagination use that lease. Retire old generations only after leases expire or close. Do not expose an uncommitted writer reader while claiming durable atomic snapshot visibility. Lucene commit and reader semantics are documented in its [IndexWriter API](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/index/IndexWriter.html); reader lifetime management can reuse [SearcherManager](https://lucene.apache.org/core/10_4_0/core/org/apache/lucene/search/SearcherManager.html).

Expose `POST /v1/ingestions`, `GET /v1/ingestions/{id}`, `POST /v1/search`, `POST /v1/suggest`, and `POST /v1/reindexes`. Search requests include bounded text, typed filters, page size at most 100, and an optional signed cursor. Responses carry generation ID, scoring version, authorized total-hit relation, results, explanations, and cursor. Bind cursors to tenant, principal authorization version, query digest, generation, and expiration; return `410` when their generation is retired.

Obtain tenant and repository permissions from authenticated server context. Apply authorization before candidates, aggregations, suggestions, and explanations. Cache keys include tenant, allowed repositories, authorization version, generation, and query. Recheck authorization-version changes before emitting a response, discarding and retrying an obsolete result. Never leak inaccessible document names through errors, suggestions, counts, snippets, or cache hits. Explicitly exclude constant-time confidentiality guarantees for shared hardware from the security claim.

#### Algorithms, milestones, and demonstrations

Implement identifier tokenization, a small query parser with Boolean/phrase/field expressions, deterministic query planning, per-field weighting, stable tie-breaking, bounded reranking, cursor validation, and nDCG@10/MRR@10/Recall@20 yourself. Reuse Lucene’s [BM25 retrieval implementation](https://lucene.apache.org/core/10_4_0/core/org/apache/lucene/search/similarities/BM25Similarity.html), postings, highlighting primitives where suitable, and segment mechanics. Do not bolt on paid embeddings or an external search server.

**M1:** ingest immutable snapshots and retrieve source lines through the CLI. **M2:** complete generation publication, restart recovery, concurrent leases, and deletion behavior. **M3:** deliver authenticated HTTP search, suggestions, bounded query parsing, and ranking explanations. **M4:** freeze evaluation data, run relevance/performance experiments, execute crash schedules, and publish the case study.

Commit 80 meaningful queries with graded judgments and rationales, split 40 development, 20 validation, and 20 held-out before ranking tuning. Have the coordinator review labels independently of ranking output. Record that authored-corpus results describe that corpus. Compare plain content-field BM25 against source-aware ranking and feature ablations. Preserve baseline and unsuccessful results; retain the baseline when a candidate does not improve held-out quality. Never relabel inconvenient queries to improve a score.

`demo` generates a reproducible 20,000-document workload across three tenants, indexes it, runs identifier/prose searches, replaces a snapshot under load, interrupts another build, restarts, and verifies that an inaccessible repository remains invisible. Provide a 2,000-document CI profile and a 100,000-document performance profile with fixed seeds and exact byte counts.

#### Completion gates

| ID | Required measurable evidence |
|---|---|
| `P07-A1` | Repeated ingestion of 10,000 identical records produces one logical copy per key; manifest replacement removes exactly the specified deleted records. |
| `P07-A2` | At least 100 seeded kill/failure schedules across build, commit, pointer publication, and retirement yield either the previous valid generation or the complete new one; no mixed-generation response. |
| `P07-A3` | Cross-tenant/denied-repository tests cover results, counts, facets, suggestions, explanations, cursors, and warmed caches, with zero unauthorized content disclosures. |
| `P07-A4` | Held-out nDCG@10 is at least 0.75 and identifier MRR@10 at least 0.90 on the published corpus; report baseline, candidate, ablations, and per-query failures. |
| `P07-A5` | On the documented reference machine, 100,000 documents and 30 search requests/second sustain p95 below 200 ms after warm-up, using at most 2 GiB configured heap; publish errors, GC, and CPU alongside latency. |
| `P07-A6` | Invalid UTF-8, oversized files, archive expansion limits, path escape, symlink escape, disk-full, cancellation, and corrupt staging metadata fail explicitly while the previous generation remains searchable. |

Performance thresholds are project engineering targets, not universal hardware claims. Include raw measurements and a failed-gate explanation whenever an environment cannot meet them.

### Project 08 — ThreadLens: evidence-driven JVM diagnostics

**Assigned agent:** A08. **Exclusive ownership:** `projects/08-threadlens/**`. **Package root:** `io.github.cheshmakzanoon.portfolio.threadlens`. Build on Java 25 and Maven, using `jdk.jfr`, `java.instrument`, and centrally pinned ASM. Deliver a startup Java agent, offline analysis CLI, standalone HTML report, and a controlled performance experiment.

#### Product and senior engineering evidence

ThreadLens explains why a deliberately instrumented Java workload becomes slower: executing-stack hotspots, allocation pressure, observed lock contention, parking, GC behavior, and timings for a small selected set of application methods. It must demonstrate JVM observability, bytecode transformation, concurrency, statistical restraint, and the ability to make a diagnostic tool safe for its host application.

JFR provides event collection. You implement event normalization, bounded aggregation, comparison, reporting, method-selection policy, and instrumentation. Execution samples are sampled evidence, not exact method call counts or complete CPU-time accounting. Observed contention and allocation events have configuration-dependent coverage. Every graph declares its unit, denominator, recording configuration, missing-event status, and observation window. Do not claim to have built a native CPU sampler.

#### Owned modules and responsibilities

| Module/path | Responsibility and required implementation seams |
|---|---|
| `agent/` | `ThreadLensPremain`, `AgentConfig`, `InstrumentationPolicy`, `TimingTransformer`; package the `Premain-Class` manifest, select classes, transform safely, and isolate initialization failure. |
| `probe/` | Minimal `TimingProbe`, method-ID registry, monotonic duration capture, reentrancy guard, and nonblocking bounded event publication; no web framework dependencies. |
| `jfr/` | `EventCapabilities`, `RecordingController`, `EventNormalizer`, `RecordingImporter`; runtime event discovery, bounded recording configuration, live consumption, and offline import. |
| `analysis/` | `StackAggregator`, `WindowAccumulator`, `ContentionSummary`, `AllocationSummary`, `ProfileComparator`; deterministic aggregation and comparable baseline/candidate statistics. |
| `cli/`, `report/` | Commands `capabilities`, `analyze`, `compare`, and `benchmark`; self-contained HTML with navigable flame stacks, timelines, and accessible tables. |
| `workloads/`, `compatibility/`, `docs/` | CPU/allocation/contention/virtual-thread workloads, JVM matrix tests, injected observer faults, overhead protocol, safety boundaries, and raw benchmark evidence. |

#### Collection and instrumentation contract

Start explicitly with `-javaagent:threadlens-agent.jar=config=threadlens.yaml`; dynamic attachment is outside this project’s required surface. Agent startup and `premain` behavior follow the [instrumentation specification](https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/package-summary.html). A valid installed agent must catch recoverable initialization failures, emit one bounded diagnostic, disable itself, and allow the application to start. Do not promise recovery from an invalid agent JAR, fatal VM/resource failure, or every possible linkage failure.

Discover enabled event schemas at runtime. Support execution samples, selected allocation samples with their available weights, GC events, Java monitor-enter events, and thread-park events when present. Record unsupported events explicitly. Use a custom minimal recording configuration; do not collect method arguments, return values, HTTP content, environment variables, system properties, or exception messages. Application identifiers and stack signatures are sufficient for reports.

Use the [RecordingStream API](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/consumer/RecordingStream.html) for live observation and JFR consumer APIs for files. Configure 15-minute/256-MiB recording retention and document chunk-granularity behavior. Add a 512-MiB observer-directory budget with graceful collection shutdown before exhausting host disk. A callback performs bounded conversion and a nonblocking queue offer; analysis and output run separately. Default queue capacity is 65,536 normalized events. Track queue drops, observed JFR loss indications, unsupported fields, truncated stacks, and incomplete windows. Missing data must never become a zero-activity claim.

Instrumentation requires an explicit application-package allowlist and a method-count ceiling. Initially support ordinary classes in the system classloader’s unnamed module; JFR still observes other supported runtime events. Skip bootstrap/JDK/tool classes, constructors, class initializers, native/abstract methods, unsupported loaders/modules, and oversized methods, with reason counts. Publish this scope honestly.

Implement entry/normal-exit/exceptional-exit timing while preserving return values, thrown exceptions, and original exception-handler behavior. Use monotonic time. Timings are observed wall-clock duration within the injected boundaries; timing a synchronized method does not include monitor acquisition before its bytecode begins. Shade agent dependencies. Resolve any frame/type metadata without initializing target classes; skip transformation when safe resolution is unavailable. The [ClassFileTransformer contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/ClassFileTransformer.html) specifically warrants careful avoidance of transformation-time class circularity.

Probe work must avoid blocking, I/O, unbounded allocation, argument inspection, and retained object references. Bound method IDs to 10,000, stack depth to 128, and string lengths to 256 characters; aggregate overflow into explicit counters. Disable failing probes without changing application results. Treat fatal VM errors as outside the recoverable observer-fault guarantee.

#### Algorithms and implementation milestones

Implement stack-prefix aggregation, inclusive versus self-sample accounting, fixed event-time windows, bounded cardinality handling, weighted allocation aggregation where supported, stable method identity, and comparable profile deltas. Reuse ASM parsing/writing, JFR decoding, and a histogram library. Keep timing distributions separate from sampling proportions; do not infer exact invocation frequencies from a flame graph.

**M1:** analyze a fixture recording and emit schema-validated JSON with explained units. **M2:** deliver bounded live collection and equivalent offline replay results. **M3:** add restricted startup instrumentation and bytecode-preservation tests. **M4:** produce the comparison report, compatibility matrix, observer-failure demonstration, and measured overhead study.

Define `report.schema.json` with recording ID, JDK vendor/version, OS/architecture, event settings, start/end, sample counts, loss counters, supported capabilities, aggregation limits, and profile data. Escape all report text; a hostile method name or recording field cannot inject HTML. Reports remain useful offline and make no external telemetry requests.

Declare Java 25 HotSpot on Linux x86-64 the full validation platform. Run JDK 25 startup, collection, and analysis smoke tests on Windows x86-64 and macOS arm64 where runners exist. Record each platform as tested, failed, or untested; never infer compatibility from compilation. Other JDKs, JVM implementations, native images, and dynamic attachment remain outside verified support.

#### Workload and completion gates

Provide a single demo command that runs baseline and intentionally regressed CPU, allocation, and contention workloads, then opens a differential report showing the relevant observed evidence. Include platform threads and virtual threads, and explicitly show events or relationships the tool cannot attribute.

Measure overhead through at least seven randomized paired baseline/observed runs per workload, each with a fixed 30-second warm-up and 60-second measurement window. Record exact settings, workload rate, JDK flags, hardware, process RSS, GC, throughput, and p50/p95/p99 latency. Report paired effect estimates and uncertainty. Benchmark JFR-only and JFR-plus-selected-method-instrumentation separately; microbenchmarks of probe cost cannot replace application measurements.

| ID | Required measurable evidence |
|---|---|
| `P08-A1` | Replaying one recording twice produces identical normalized aggregates; synthetic event fixtures prove inclusive/self counts, allocation weights, and window boundaries. |
| `P08-A2` | The known CPU hotspot appears in the top three observed sampled methods; allocation and contention regressions identify their planted causes in at least six of seven controlled trials. |
| `P08-A3` | At least 40 transformation cases preserve outputs and exception identity, including recursion, wide primitive returns, nested handlers, and synchronized methods; transformed fixtures pass JVM verification. |
| `P08-A4` | Queue saturation, unwritable output, malformed config, failed transformation, unavailable events, and consumer exceptions leave the host workload functional while producing bounded failure/drop evidence. |
| `P08-A5` | Reference-workload median throughput regression is at most 5% for the declared JFR-only configuration and 10% with the documented method allowlist; p99 regression is at most 10% and 15% respectively. Publish all trials. |
| `P08-A6` | A 20-minute cardinality/slow-consumer stress run respects configured observer limits; report windows affected by drops as incomplete and test that fixture secrets never appear in normalized exports. |

If an overhead gate fails, preserve the result and improve the implementation. Changing event settings requires rerunning both correctness and overhead evidence under the newly declared configuration.

### Project 09 — ByteGuard: bounded Java bytecode analysis and taint tracing

**Assigned agent:** A09. **Exclusive ownership:** `projects/09-byteguard/**`. **Package root:** `io.github.cheshmakzanoon.portfolio.byteguard`. Use Java 25, Maven, centrally pinned ASM, Jackson, and a CLI library. Deliver an analysis engine, versioned rule models, command-line tool, SARIF exporter, and an independently inspectable benchmark corpus.

#### Product and senior engineering evidence

Analyze Java classfiles and JARs without loading or executing the analyzed code. Explain potentially unsafe flows from modeled external-input sources to narrowly specified SQL-text, shell-command-string, and filesystem-access sinks. Show the intermediate representation, control-flow graph, abstract values, interprocedural summaries, and witness path behind every finding.

This is evidence of compiler engineering, graph algorithms, fixed-point analysis, secure parsing, and disciplined uncertainty handling. It is a bounded analyzer under a declared model. Never advertise a complete vulnerability detector, guaranteed security certification, or universal soundness. Absence of findings does not establish absence of vulnerabilities.

#### Owned modules and responsibilities

| Module/path | Responsibility and required implementation seams |
|---|---|
| `input/` | `InputManifest`, `BoundedJarReader`, `MultiReleaseSelector`, `ClasspathCatalog`; safely enumerate and hash explicit inputs, resolve duplicates, and select target-release entries. |
| `bytecode/` | `ClassFactsReader`, `MethodIr`, `InstructionId`, `TypeHierarchy`; use ASM visitors/tree nodes only to extract structural facts. |
| `cfg/` | `ControlFlowBuilder`, `BasicBlock`, `ExceptionEdge`, `StronglyConnectedComponents`; implement graph construction and validation. |
| `dataflow/` | `AbstractValue`, `FrameState`, `TransferFunction`, `WorklistSolver`, `HeapSummary`; implement the finite lattice and method analysis. |
| `interprocedural/` | `CallResolver`, `MethodSummary`, `SummaryWorklist`, `AnalysisBudget`; resolve modeled calls and propagate summaries with explicit bounds. |
| `rules/`, `report/`, `cli/` | Versioned declarative source/sink/library models, finding explanations, deterministic SARIF/JSON, baseline comparison, and scan commands. |
| `corpus/`, `evaluation/`, `docs/` | Trusted source fixtures, expected labels, compilation recipes, confusion matrices, unsupported-feature inventory, threat model, and engine walkthrough. |

#### Safe input and analysis model

Provide `byteguard scan --input fixture.jar --classpath corpus/stubs --target-release 25 --rules rules/core.yaml --format sarif --out report.sarif`. Inputs and classpaths are explicit local paths. No network dependency resolution, target classloader, reflection, deserialization, annotation processing, or execution of target archives is permitted. Compile owned corpus fixtures with `-proc:none`.

Stream archive contents without extracting entries into caller-selected paths. Default limits: 25,000 entries, 200 MiB total expanded bytes, 8 MiB per class, expansion ratio 100, nesting depth zero, and a configurable scan deadline. Enforce actual bytes read, not merely ZIP metadata. Reject traversal names, duplicate logical entries, malformed classfiles, and unsupported class versions with precise diagnostics. Resolve multi-release entries according to the requested target release and manifest semantics in the [JAR specification](https://docs.oracle.com/en/java/javase/25/docs/specs/jar/jar.html). Do not treat every versioned class as a separate application class.

ASM supplies classfile facts and instruction representations; the agent must write its own CFG, operand-stack/local-state transfer functions, worklist, joins, summaries, and provenance. Do not wrap ASM’s built-in analyzer, Soot, WALA, or an existing taint engine and call it the project’s core algorithm. Choose a centrally pinned ASM version with verified Java 25 support; [ASM’s release history](https://asm.ow2.io/versions.html) documents supported classfile versions.

Build normal branch, switch, fallthrough, return, and exceptional edges. Handle category-two locals/stack values, duplication instructions, handler entry stacks, unreachable code, and verifier-compatible merge shapes. Use stable method signatures plus instruction ordinals for identities; attach source lines when debug information exists without inventing missing locations.

Specify a finite taint lattice with bottom, source-label sets, and conservative unknown. Represent locals and operand-stack values explicitly. Use a documented field-sensitive, object-insensitive heap abstraction and conservative array summaries. Joins must be monotone and associative, commutative, and idempotent. Distinguish “unknown” from “untainted.” Record bounded provenance separately from lattice state so witness growth cannot prevent termination.

Implement supplied-classpath hierarchy resolution, method summaries, recursive strongly connected components, and a one-call-site context bound. Default to at most 32 dispatch targets, five levels of traced call provenance, and a fixed global work budget. A provenance depth limit truncates explanation, not the taint fact itself. Analysis-budget exhaustion produces a partial result with the unfinished frontier. Unknown calls use a documented conservative transfer and increment uncertainty diagnostics; reflection, native code, dynamic loading, unresolved libraries, and unsupported bootstrap behavior remain explicit model limitations.

Model common string construction, supported `StringConcatFactory` sites, selected lambda patterns, collection pass-through, and exact source/sink descriptors. Unsupported `invokedynamic` must not silently become a clean value. Sanitizers are narrow, context-specific model entries backed by positive and negative tests. A prepared statement with tainted SQL text still triggers the SQL-text rule; separately bound values do not. Normalizing a path alone is not a traversal sanitizer. Shell-command findings require modeled shell-command-string construction, not merely any string argument to a process API.

#### Output, milestones, and reproducible demonstration

Every finding includes rule version, severity, confidence/model limitations, source and sink descriptors, call path, source locations if available, bytecode locations, and stable fingerprint. Produce schema-valid [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html) with rules, results, code flows, partial fingerprints, and invocation diagnostics. Do not embed absolute workstation paths or entire source files unnecessarily. A resource limit must appear both in machine output and terminal status.

Define exit codes: `0` completed within the declared model with no findings; `1` completed with findings; `2` invalid input/configuration or analysis failure; `3` partial analysis. Distinguish deliberate suppressions, unsupported constructs, and incomplete work. Baselines match stable finding identity and cannot hide newly introduced findings through a broad filename wildcard.

**M1:** safe archive reader, class facts, and CFG diagrams. **M2:** intraprocedural taint, tested lattice operations, and detailed witnesses. **M3:** bounded interprocedural summaries, modeled libraries, recursion, and explicit unknown behavior. **M4:** SARIF, baseline workflow, adversarial inputs, corpus evaluation, and performance measurements. The demo compiles trusted fixtures, scans them without execution, traces one cross-method finding, analyzes the corrected fixture, then demonstrates a partial result under a deliberately small budget.

Commit at least 120 labeled fixtures: 60 positives and 60 closely matched negatives spanning all three rule families. Include loops, switch expressions, overloads, inheritance, recursive calls, exceptions, wide stack values, concatenation, modeled lambdas, safe parameter binding, false sanitizer appearances, missing dependencies, and missing debug tables. Keep additional unsupported cases outside that confusion matrix and report them separately. The coordinator reviews labels before final engine tuning; preserve fixture hashes and label rationales.

#### Completion gates

| ID | Required measurable evidence |
|---|---|
| `P09-A1` | All supported bytecode-shape fixtures produce valid CFG/frame states; property tests verify lattice laws, monotone transfers, and schedule-independent fixed points. |
| `P09-A2` | Detect all 60 labeled positive cases and classify at least 57 of 60 negatives correctly; publish every result and the per-rule confusion matrix. These are corpus results, not field accuracy claims. |
| `P09-A3` | Recursion terminates; dispatch, provenance, and global-budget limits behave distinctly; every budget-exhausted scan exits `3` with explicit unfinished coverage. |
| `P09-A4` | A target class initializer containing a sentinel side effect never runs; archive bombs, corrupt entries, duplicate classes, and traversal paths cause bounded failure without external file writes. |
| `P09-A5` | SARIF passes the pinned schema; witness locations resolve to fixture bytecode/source; unchanged findings keep fingerprints when unrelated methods are added. |
| `P09-A6` | On the documented reference machine, a fixed 10,000-class workload finishes within 120 seconds using at most 2 GiB configured heap, or terminates explicitly as partial at its declared budget. Completion requires a successful full run on the supported benchmark corpus. |

Include a candid case study of one false positive, one unsupported behavior, and one precision/performance tradeoff. Explaining the engine’s boundary is part of the deliverable.

### Project 10 — RouteWeaver: constrained dispatch and replanning

**Assigned coding agent:** A10  
**Exclusive implementation directory:** `projects/10-routeweaver/`  
**Java package:** `io.github.cheshmakzanoon.portfolio.routeweaver`

#### Purpose and engineering contribution

Build a dispatch planning application for a synthetic delivery fleet operating around a fictionalized Atlantic Canadian service area. A dispatcher imports vehicle shifts and delivery jobs, computes a feasible plan, understands why some jobs cannot be assigned, and replans after a vehicle breakdown or urgent job. Include a simple local map-like coordinate view and a schedule view; no commercial mapping API is required. Mark locations and travel times as synthetic fixtures.

The senior-level contribution is the combination of rigorous business constraints, an independent feasibility oracle, accountable optimization, and safe concurrent publication of plans. Implement the domain model, reference validator, baseline heuristic, versioned input/solution lifecycle, stale-result protection, and diagnostic explanations. Use Timefold Solver for the improvement search. Merely changing names in a vehicle-routing quickstart is insufficient.

#### Modules and ownership

| Module | Responsibilities |
| --- | --- |
| `routeweaver-domain` | Immutable problem snapshots, vehicles, jobs, time windows, versioned travel matrix, score/violation types |
| `routeweaver-planning` | Reference validator, nearest-feasible insertion baseline, original move evaluation, Timefold domain/constraints, replanning freeze rules |
| `routeweaver-service` | Async job lifecycle, bounded solver pool, JDBC persistence, optimistic publication, authenticated HTTP/UI |
| `routeweaver-verification` | Independent small-instance oracle, fixture generator, property tests, solver benchmark, demo assertions |

Keep solver annotations and mutable working solution objects inside the planning adapter. Return immutable validated solutions to the service. The reference validator must not invoke the solver's constraint provider or trust its shadow-variable calculations; it independently recomputes feasibility and objective components from the route order and input snapshot.

#### Domain and constraint contract

Represent travel duration in integer seconds, distance in integer meters, weight in grams, service duration in seconds, and capacity with documented nonnegative integer bounds. Use checked arithmetic for aggregate costs. The fixture matrix is asymmetric and versioned, so `travel(a,b)` need not equal `travel(b,a)`. Validate unique IDs, complete required matrix entries, finite nonnegative values, compatible units, and maximum problem size before solving.

Each vehicle has a depot, shift start/end, capacity, and skill set. Each delivery job has a location, service duration, demand, required skills, service-start time window, and priority. For release 1, all jobs are deliveries loaded at the depot. No pickup-and-delivery precedence model is required. A route's initial load is its assigned demand total and decreases as deliveries complete. Vehicles must return to their depot before the shift ends.

For successive stops, compute `arrival = previousDeparture + travelSeconds`; `serviceStart = max(arrival, earliestStart)`; `departure = serviceStart + serviceSeconds`. A job is temporally feasible when its service start is at most its latest permitted start and the resulting route fits the vehicle shift. This convention must be identical in documentation, solver model, independent validator, and tests. Waiting time is permitted. Include return-leg travel in route cost and shift validation.

Hard requirements: no duplicate assignment, every assigned job appears once, vehicle skill coverage, capacity, service-start windows, shift bounds, and preserved completed/committed work during replanning. Assigned and unassigned jobs form an exact disjoint partition of all remaining input jobs; completed jobs belong to immutable history and are excluded from that partition. Unassigned jobs are an explicit outcome. Distinguish `FULLY_FEASIBLE` from `PARTIAL_FEASIBLE` with unassigned demand and from a candidate containing hard violations. Never silently drop a job or describe an infeasible candidate as a dispatchable solution.

Replanning uses an explicit residual-state snapshot: planning instant, each vehicle's observed current stop/location, next available time, onboard job IDs and remaining load, completed-job history, and any frozen future prefix. Replan at observed stop boundaries in release 1; do not invent a vehicle's mid-road position. A moving vehicle retains its onboard jobs and cannot acquire depot cargo remotely. New urgent jobs remain at the depot and can be assigned only to depot/standby vehicles. Cargo transfer and en-route reloading are outside release 1. A failed vehicle's undelivered onboard jobs remain visibly unassigned with `STRANDED_CARGO`; do not teleport them to another vehicle. Release its unstarted frozen commitments only through an audited problem revision, or return an explicit blocked constraint conflict. Completed work is never released or reassigned. Start remaining routes from the observed state, not automatically from the depot.

Compare score levels lexicographically: first hard violations, then weighted unassigned priorities, then operating cost and disruption penalties. Name every component and publish its units and weights. Do not allow a large soft saving to outweigh a hard violation. Explain rejected assignments using validated reasons such as capacity, skills, time windows, or shift duration; a heuristic's failure to find an insertion is not proof that no feasible global solution exists.

Use UTC instants internally with an explicit service-area `ZoneId` for imported/displayed local times. Tests must cover an ambiguous local time, a nonexistent local time, and a cross-midnight shift. Require explicit offset/disambiguation for ambiguous input. Fixed deterministic fixtures can use offset timestamps to avoid accidental dependence on the machine's timezone.

#### Persistence, API, and concurrency

Own PostgreSQL tables such as `problem_snapshot`, `solve_job`, `solution_candidate`, `published_plan`, and `plan_event`, with tenant-aware keys where exposed to multiple organizations. Store `problem_version`, `matrix_version`, a canonical input hash, algorithm/configuration version, seed, stop condition, and source revision in every solve result.

Required HTTP surface:

- `POST /v1/problems` validates and stores an immutable versioned snapshot.
- `POST /v1/problems/{id}/solve` accepts `expectedVersion`, seed, and bounded solve budget; returns `202` with a durable job ID.
- `GET /v1/solve-jobs/{id}` returns queued/running/completed/cancelled/failed state and the best validated candidate.
- `POST /v1/solve-jobs/{id}/cancel` requests cooperative cancellation and returns the actual observed state; do not claim an immediate hard stop.
- `GET /v1/plans/{id}` returns the published input version, routes, unassigned jobs, score breakdown, and validation result.
- `POST /v1/plans/{id}/replan` creates a new immutable problem version with completed stops and the committed near-term route prefix frozen.
- `POST /v1/plans/{id}/publish` performs compare-and-set against the active problem version and current plan revision; a stale solver result returns `409`.

Dispatch solving to a bounded CPU executor with a maximum queue and explicit admission rejection. Do not solve on request threads or assume virtual threads provide more CPU. A database lease/fencing token prevents two service processes from publishing for the same solve job. A result from an expired worker or superseded input is retained as historical evidence, not installed as the active plan. On restart, queued/running jobs must move through a documented recovery path rather than remain permanently running.

Store the last valid incumbent when cancellation or budget expiry occurs. Invalid candidates are diagnostic only and cannot overwrite a validated plan. Multiple candidate updates must publish immutable snapshots; UI reads cannot observe a solver's half-mutated lists.

Define cancellation transitions explicitly: queued jobs can move directly to `CANCELLED`; running jobs first enter `CANCEL_REQUESTED`, then become `CANCELLED` when the worker acknowledges, or retain a terminal completion if completion serialized first. Terminal-state requests are idempotent. Validate fencing generation and legal current state in the same transaction that records an authoritative candidate or terminal transition. A cancelled job's retained validated incumbent may be manually published only after the ordinary current-version checks; cancellation never auto-publishes it.

Compare incumbents only within the same immutable problem snapshot, travel matrix, residual/freeze state, and objective configuration. A breakdown or new urgent job can worsen attainable results. Repair and independently revalidate the previous plan against the new snapshot before it can serve as that snapshot's incumbent; never compare raw scores across problem versions as though they were the same workload.

#### Implementation sequence

1. Implement model validation, time arithmetic, independent feasibility checking, score decomposition, and small exhaustive enumeration before adding the solver.
2. Implement a deterministic nearest-feasible insertion baseline and original relocate/swap/2-opt candidate evaluation with feasibility rechecking. The travel matrix is asymmetric: a 2-opt reversal must account for every reversed internal directed arc or recompute the complete affected route; the symmetric four-edge shortcut is incorrect here. Define tie-breaking by stable IDs. Preserve the best valid incumbent for the same problem/objective version.
3. Add the Timefold adapter with the same constraints, version-pinned API use, and bounded termination. Use the baseline as an incumbent and retain it if the solver result is worse. Small fixtures use fixed seeds and deterministic operation/step budgets when supported; wall-clock runs are not promised to return identical solutions.
4. Add durable async jobs, input versioning, cancellation, idempotent submission, restart recovery, and compare-and-set plan publication.
5. Add dispatcher views: routes and stop sequence, shift timelines, unassigned work, constraint explanations, baseline comparison, and a visible input/configuration version.
6. Implement breakdown and urgent-job replanning, adversarial validation, benchmark corpus, and the case study.

#### Verification, workload, and demonstration

Create an exhaustive oracle for bounded tiny instances, for example up to seven deliveries and two vehicles. Enumerate assignments including unassigned jobs and empty routes, then valid route permutations, under a deterministic search limit. Evaluate each complete candidate independently and return an optimum only after complete enumeration; interrupted oracle runs are inconclusive. Validate the evaluator and enumeration with analytically solved fixtures so calling the same validator twice is not the sole evidence. A heuristic need not reach that optimum, but an independently checked candidate cannot score better than a completed exact oracle. Also test the exact disjoint partition of remaining jobs and an asymmetric route where reversing internal arcs materially changes cost.

Property tests generate feasible plans and mutate one property at a time: duplicate stop, impossible skill, excess load, window violation, missing return leg, and modified frozen prefix. Verify rejection and useful explanations. Compare the optimized candidate's independent score to the baseline and retain the baseline when necessary. Run database races proving stale solve completion, cancellation, and two concurrent publish attempts cannot corrupt the active revision. Verify cross-tenant access denial, malformed matrices, queue saturation, restart, and arithmetic overflow rejection.

Publish a deterministic ten-instance workload corpus with 100, 250, and 500 jobs and 5, 10, and 25 vehicles, including easy, tightly constrained, and deliberately overconstrained cases. Record score/time traces, feasibility, unassigned demand, solver queue latency, throughput of problem validation, memory, and baseline-relative improvement. A proposed goal is at least 10% lower operating cost than the baseline on a preregistered deliberately improvable fixture within a 30-second budget on the stated reference host. This is a tuning goal, not a claim about all datasets. Feasibility and preservation of the valid incumbent are mandatory even when optimization gains are small.

The 3–5 minute demo uses 60 jobs and six vehicles, including one standby vehicle at the depot: load the fixture, display baseline routes, solve, display validated same-version score differences, advance to an observed stop boundary, mark a vehicle broken down, freeze completed stops, insert an urgent job at the depot, and replan. Show stranded cargo and any other unassigned work, and use the standby vehicle only when its constraints permit the urgent assignment. Attempt to publish the earlier solution and visibly receive a stale-version conflict. The demo script asserts no duplicate deliveries, zero hard violations in every published plan, intact completed history, correctly audited released commitments, and recorded input versions.

#### Acceptance and scope boundaries

- **P10-A1:** The independent validator and tiny exact oracle agree on complete bounded fixtures; invalid routes are rejected without trusting solver scores.
- **P10-A2:** Baseline and solver obey the same units, time semantics, and constraints; a candidate never degrades the valid incumbent for the same immutable problem/matrix/freeze/objective version under the defined lexicographic objective.
- **P10-A3:** Durable async execution survives restart, bounds CPU/queue use, records cancellation honestly, and rejects expired-worker/stale-input publication.
- **P10-A4:** The breakdown/urgent-job demo preserves completed work and applicable frozen work, audits any released commitments, respects actual cargo locations, displays unmet demand, and rejects publication of an outdated solution.
- **P10-A5:** The corpus, raw measurements, reference environment, score explanations, tests, and operational runbook are reproducible from a clean checkout.

Release 1 does not require real road routing, live traffic, a dispatch integration, mathematically optimal solutions for large instances, autonomous real-world vehicle control, or a paid Timefold service. Implement a realistic bounded optimization product and state its assumptions precisely.

Primary references: [Timefold constraint and score model](https://docs.timefold.ai/timefold-solver/latest/constraints-and-score/overview), [Timefold Java/Spring integration and solver lifecycle](https://docs.timefold.ai/timefold-solver/latest/quickstart/spring-boot/spring-boot-quickstart), and [JDK 25 `java.time`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/time/package-summary.html). These references support the libraries' behavior; the project model, workload, API, and acceptance criteria above are this brief's design choices.

## 8. Execution milestones and integration gates

The coordinator progresses all ten assignments through the same milestone model. Milestones describe coherent implementation checkpoints; they are not alternative stopping points. Avoid a calendar promise based only on agent count. The effort depends on the executor, implementation quality, review findings, and available validation resources.

| Milestone | Worker output | Coordinator gate |
| --- | --- | --- |
| M0 — foundation | Read contract; validate assignment and known constraints | Common toolchain works, paths/ports reserved, test discovery demonstrated |
| M1 — working vertical slice | Real input through core logic to persisted or computed result; useful tests | The intellectual core exists and a minimal executable scenario runs |
| M2 — complete domain | Required operations, invariants, schemas/protocols, edge cases | No core acceptance item replaced with a placeholder or delegated to a turnkey engine |
| M3 — failure and recovery | Crash/concurrency/adversarial tests, security boundaries, recovery | Important failure guarantees hold in observed tests |
| M4 — measured and explained | Workload results, profiling, operations, demo, case study | Evidence has commands, environment, source identity, and honest limitations |
| M5 — independent review | Peer findings fixed and regressions demonstrated | Every project acceptance ID plus global gates passes or is explicitly blocked |
| M6 — integrated release | Clean-checkout verification, packages, catalog, final README | Remote state and CI verified where publication is authorized |

At each milestone, workers keep writing implementation and tests for the next unresolved gate, except during an acknowledged validation/commit write pause. The coordinator handles foundation work, review, and integration promptly so all workers can progress. Do not spend a full iteration polishing a root landing page while critical engine behavior is absent. An explicitly blocked gate permits an honest partial handoff; it does not satisfy the gate. A completed release requires every mandatory applicable gate to be `PASS`.

### 8.1 Worker launch packet

For each A01–A10, send a task containing all of the following rather than a one-line idea:

```text
You are project implementation agent A<NN> for <Project>.
Repository: https://github.com/cheshmakzanoon-ops/Java-portfolio.git
Exclusive owned directory: projects/<NN>-<slug>/
Read the common sections of docs/JAVA_PORTFOLIO_MASTER_EXECUTION_BRIEF.md
and the entire Project <NN> contract, plus applicable AGENTS.md instructions.
Implement that project completely through M1–M5 and its acceptance IDs.
The coordinator owns root files, dependency versions, integration and all Git writes.
Write only in your project directory and assigned temporary scratch.
Use the pinned Java/Maven baseline and common launch/test conventions.
Deliver real algorithms, durable behavior, meaningful tests, failure demos,
measurements, operational docs, and an honest case study.
Start with the smallest working vertical slice, then complete all requirements.
Send precise shared-change requests; keep progressing on independent work.
Update docs/STATUS.md at each milestone and preserve a resume checkpoint.
Do not claim a gate passed without executed evidence.
```

Populate each packet with the corresponding project name, directory, full detailed contract, and concrete milestone goal from the assignment table. Register all supported workers before awaiting their completion. Awaiting task registration is fine; waiting for A01 to finish before launching A02 defeats parallel implementation. The coordinator reports actual active tasks and the resource queue.

### 8.2 Selected integration showcase

After the standalone projects pass, the coordinator should build one small showcase under `integration/showcase/` if it adds useful evidence: a DurableFlow process requests a simulated ClearLedger posting through its public API; TenantShield protects a chosen operation; ThreadLens captures a controlled diagnostic session. This is an optional presentation layer and never a dependency of the ten standalone acceptance suites.

The integration must carry stable idempotency keys, preserve each project's documented failure semantics, and pass contract tests. Do not force all ten products into one runtime or introduce a common database, mandatory service mesh, or Kafka topic shared by unrelated domains. Ten independently usable projects are the release requirement.

### 8.3 Repository release acceptance

The coordinator must satisfy these additional gates:

- **R-A01 — completeness:** exactly ten catalog entries point to implemented projects; all P01–P10 acceptance items have evidence and no unresolved mandatory placeholder.
- **R-A02 — reproducible builds:** a clean checkout builds with the wrapper on the pinned JDK. Every project builds independently. Linux CI passes; run the documented Windows/PowerShell and macOS entry-point checks when those environments are available and state any platform that remains unverified.
- **R-A03 — meaningful validation:** required unit/property/integration checks ran and passed; deliberate foundation failure proved the harness can fail; final logs show no blanket test skips.
- **R-A04 — standalone demos:** every catalog demo runs, checks its assertions, and terminates with the correct status. At least two different service projects run together without port or data collisions.
- **R-A05 — failure evidence:** each project shows its specified failure, security, or adversarial scenario with a verifiable observed result; bounded tests are described with their bounds.
- **R-A06 — measurement integrity:** every published performance claim links to raw results, workload, environment, and actual source identity. Unmet goals remain visibly unmet.
- **R-A07 — maintenance:** pinned dependencies/actions/images, verified migration/recovery instructions, appropriate SBOM/notices, no real secrets or generated binary bloat in Git.
- **R-A08 — architecture:** no cross-project production imports, no accidental dependency cycles, pure algorithm/domain modules where specified, and shared-file ownership respected.
- **R-A09 — presentation:** root README and all project case studies tell a truthful technical story, links resolve, diagrams match code, and interview walkthroughs identify real implementations.
- **R-A10 — final handoff:** no unintended uncommitted changes, final local/remote SHA and CI state reported, known limitations explicit, and resumption instructions present for any concrete blocked gate.

If one platform is unavailable, report that platform-specific validation as blocked rather than claiming it passed. If a performance goal is missed, record the measured shortfall and optimize before declaring that goal complete. If an actual tool/access limit prevents a mandatory test, finish every unaffected gate and provide the exact command/environment needed to close the remaining gap. Do not substitute prose for unimplemented mandatory behavior.

## 9. Final portfolio content and handoff

The final root README should make it possible to assess the work quickly:

1. A one-paragraph description of the portfolio and the kinds of engineering it demonstrates.
2. A real build/status row, verified prerequisites, and a working clone/build/demo command sequence.
3. Ten project entries with purpose, original core contribution, meaningful guarantee, demo link, and case-study link.
4. Three recommended deep-dive paths for backend, distributed reliability, and JVM specialization.
5. A concise technology list and explanation of the independent-project architecture.
6. Links to measured results, acceptance status, operational limits, attribution, and contribution instructions.

Do not call the repository "production proven," "bank grade," "zero latency," "unhackable," or a replacement for established infrastructure. State the tested scope and show the code that supports it. The desired impression is technically demanding work that a reviewer can verify and an author can explain.

The coordinating agent's final report must contain:

- A ten-row table listing each project, runnable command, acceptance status, evidence link, and most important limitation.
- The implemented architecture and substantive design changes made during the build.
- The exact verification performed, failures found and fixed, and any remaining blocked item.
- Measured benchmark summaries, clearly separated from targets and simulations.
- Links to the README, individual project guides, demos, case studies, and source.
- Final local commit, remote branch commit, CI status, and working tree status, each based on actual observations.

Completion means the ten defined products work within their specified scope and the evidence is reviewable. Continue the implementation/review/fix cycle until those gates are satisfied, or until a specific external blocker has been isolated after all unaffected work is complete.

## 10. Source and version maintenance

Primary links throughout this file identify the documentation behind the selected mechanisms and terminology. They are not evidence that an implementation exists or that benchmark targets have been achieved. At the start of execution, verify the current stable APIs and compatibility, pin them, and keep the evidence date in `docs/technology-baseline.md`. Cite the particular documentation section used when a consequential implementation decision relies on behavior such as transaction retry, Kafka delivery, JFR event semantics, Lucene commits, or Raft persistence.

Keep the substantive scope of this brief intact when upgrading dependencies. A version migration can require code changes, contract tests, or an ADR; it does not authorize deleting the feature that made the project advanced.
