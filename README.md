# Java engineering portfolio

Ten independent Java 25 systems, each built around a specific engineering problem: accounting invariants, durable execution, consensus, event-time processing, authorization, matching, retrieval, JVM diagnostics, static analysis, and constrained planning.

Each project contains its implementation, executable examples, tests, design decisions, and operating limits. The shared Maven parent supplies dependency versions and verification policy; projects do not depend on one another.

## Start here if you are reviewing this portfolio

For an efficient hiring review, see the [five-minute runnable example and backend
engineering walkthrough](docs/hiring-guide.md). Start with **The Pit** if you do not
have Docker, or **Daybook**, **Handoff**, and **Gatehouse** for backend
architecture, transaction semantics, and authorization. Source code, tests, operating
limits, and failure evidence are linked directly; historical checks do not certify
later changes.

The [21 October engineering pass](docs/job-readiness-20261021.md) records the latest
authorization, recovery, diagnostics, and configuration fixes, with current local test
counts and the acceptance work that remains open.

A short, no-Docker tour of the newest capabilities:

```bash
projects/06-thepit/scripts/pit.sh tour    # The Pit: price-time priority, restart from the journal, independent audit
```

The Pit can now replay and verify any command journal against an independent oracle (`pit.sh verify`) and serves market data and an anonymous trade tape; Gatehouse can explain a decision rule by rule and statically prove that a draft policy contains dead, shadowed or all-denying rules before it is published; Daybook serves an authoritative trial balance, journal and account reads, and can open accounting periods through a restricted database function. Each project README documents its additions, and each project's status page records what was executed and what was not.

A later pass on the seven projects that had not changed since the 21 October rename added one substantial capability to each, with tests that check it against an independent reference where one exists:

- **Majority** gains Raft pre-vote, check-quorum and operator-driven leadership transfer (an isolated member no longer inflates the cluster's term, a stranded leader steps down, and a planned leader change costs one term and tens of milliseconds); it also fixes a deposed leader campaigning again on its next tick.
- **Faultline** gains four more sink rules (class loading, URL/socket destinations, XPath, LDAP filters), servlet request sources, a text report and `faultline rules`; model `1.0.0` rule files and baselines still load.
- **Backtrace** gains a flame graph in its HTML report and `export folded|speedscope|diff` for other profilers.
- **Handoff** gains `lint`, `graph` and a deterministic `simulate` for workflow definitions that run without a database; its linter finds that the shipped order workflow completes with a reservation never released when its approval signal times out.
- **Late Arrival** gains `explain`, which replays events through the real engine and says why each was accepted, late, duplicate or rejected, checked against its independent oracle.
- **Switchback**'s local search converges about 50 times faster and removes 10-20 times more cost at the production evaluation limit, with an exactly lossless pruning rule.
- **Stacks** gains `explain-query` and positioned query syntax errors.

```bash
projects/02-handoff/scripts/definition.sh lint projects/02-handoff/examples/order-fulfilment.json
projects/04-latearrival/scripts/explain.sh projects/04-latearrival/examples/late-data.jsonl
java -jar projects/07-stacks/cli/target/stacks-cli-1.0.0-SNAPSHOT.jar explain-query 'a b AND c'
```

The same pass repaired verification failures that the rename had left at HEAD: formatting gates in four projects, the 240 frozen Faultline corpus files whose bytes had been rewritten, a stale hash-vector test and a flaky Backtrace test. Each project's status page records exactly what was executed, including the PostgreSQL and Kafka integration suites that were not run in the environment used.

## Choose a project

| Project | Engineering focus | Start here |
| --- | --- | --- |
| [Daybook](projects/01-daybook) | Balanced integer journals, serializable posting, idempotency, reversals, and reconciliation | [Invariants](projects/01-daybook/docs/INVARIANTS.md) |
| [Handoff](projects/02-handoff) | Deterministic replay, task leases, fencing, timers, and uncertain external effects | [External-effect semantics](projects/02-handoff/docs/DECISIONS/001-external-effects.md) |
| [Majority](projects/03-majority) | Raft consensus, checksummed storage, snapshots, quorum reads, and history checking | [Architecture](projects/03-majority/docs/ARCHITECTURE.md) |
| [Late Arrival](projects/04-latearrival) | Event-time windows, watermarks, deduplication, and atomic recovery over Kafka/PostgreSQL | [Architecture](projects/04-latearrival/docs/ARCHITECTURE.md) |
| [Gatehouse](projects/05-gatehouse) | A bounded policy language, immutable compilation, tenant isolation, and OIDC | [Security model](projects/05-gatehouse/docs/SECURITY.md) |
| [The Pit](projects/06-thepit) | Price/time matching, exact risk reservations, forced journals, and restart-safe commands | [Invariants](projects/06-thepit/docs/INVARIANTS.md) |
| [Stacks](projects/07-stacks) | Immutable indexes, bounded ingestion, authorized retrieval, and relevance evaluation | [Architecture](projects/07-stacks/docs/ARCHITECTURE.md) |
| [Backtrace](projects/08-backtrace) | JFR collection, bounded bytecode instrumentation, and differential diagnosis | [Architecture](projects/08-backtrace/docs/ARCHITECTURE.md) |
| [Faultline](projects/09-faultline) | Bytecode control flow, abstract interpretation, taint propagation, and SARIF | [Analysis contract](projects/09-faultline/docs/ARCHITECTURE.md) |
| [Switchback](projects/10-switchback) | Route validation, exact small-instance oracles, optimization, and fenced publication | [Architecture](projects/10-switchback/docs/ARCHITECTURE.md) |

## Build and run

Prerequisites: JDK 25, Bash, Python 3, and the included Maven wrapper. Docker with Compose is required for database, broker, and issuer integration; it is unnecessary for The Pit's CLI workflow.

```bash
# Run from the repository root with JDK 25 on PATH.
./scripts/portfolio.sh verify thepit
./scripts/portfolio.sh integration thepit
./scripts/portfolio.sh demo thepit
```

The demo checks allocation order, replacement priority, command deduplication, and recovery after an interrupted durable cancellation. It asserts four executions, eight lots traded, Alice's cash balance of 19,200 ticks, inventory of eight lots, and no open orders.

For a service project, use its documented configuration and lifecycle:

```bash
./scripts/portfolio.sh up daybook
./scripts/portfolio.sh demo daybook
./scripts/portfolio.sh down daybook
```

`./scripts/portfolio.sh list` lists project entry points. `./mvnw -B -ntp verify` builds the ordinary reactor. See [development](docs/development.md) for toolchain setup, isolated dependency caches, integration tests, formatting, and shared build limits. In the managed cloud workspace, source `scripts/cloud-env.sh` before Maven.

## Design and verification

The portfolio favors explicit state transitions, checked integer arithmetic, bounded inputs, independent oracles, and tests at actual persistence and process boundaries. Frameworks provide infrastructure; the domain algorithms remain visible in project-local modules. [Architecture](docs/architecture.md) describes these boundaries.

Project status pages distinguish implementation, executed checks, and outstanding work. Historical benchmark packets identify the source and workload they measured; a later code change does not inherit those measurements. Repository-wide release validation, hosted CI, and vulnerability-feed access have separate requirements in the [acceptance matrix](docs/acceptance-matrix.md). Project checks and repository release criteria have separate outcomes.

The [engineering review](docs/quality-review.md) records the transaction, recovery, concurrency, parser and interface changes, their executed checks, and remaining limits. [Architecture](docs/architecture.md#where-decisions-live) compares each system's decision and acknowledgment boundaries.

Use synthetic data and local demo credentials only. Start with the [security policy](SECURITY.md) before exposing a service. Dependency licenses and algorithm attribution are documented in [third-party components](THIRD_PARTY.md). Contributions follow the [engineering guide](CONTRIBUTING.md); the source is licensed under [Apache 2.0](LICENSE).
