# Java engineering portfolio

Ten independent Java 25 systems, each built around a specific engineering problem: accounting invariants, durable execution, consensus, event-time processing, authorization, matching, retrieval, JVM diagnostics, static analysis, and constrained planning.

Each project contains its implementation, executable examples, tests, design decisions, and operating limits. The shared Maven parent supplies dependency versions and verification policy; projects do not depend on one another.

## Choose a project

| Project | Engineering focus | Start here |
| --- | --- | --- |
| [ClearLedger](projects/01-clearledger) | Balanced integer journals, serializable posting, idempotency, reversals, and reconciliation | [Invariants](projects/01-clearledger/docs/INVARIANTS.md) |
| [DurableFlow](projects/02-durableflow) | Deterministic replay, task leases, fencing, timers, and uncertain external effects | [External-effect semantics](projects/02-durableflow/docs/DECISIONS/001-external-effects.md) |
| [QuorumKV](projects/03-quorumkv) | Raft consensus, checksummed storage, snapshots, quorum reads, and history checking | [Architecture](projects/03-quorumkv/docs/ARCHITECTURE.md) |
| [PulseStream](projects/04-pulsestream) | Event-time windows, watermarks, deduplication, and atomic recovery over Kafka/PostgreSQL | [Architecture](projects/04-pulsestream/docs/ARCHITECTURE.md) |
| [TenantShield](projects/05-tenantshield) | A bounded policy language, immutable compilation, tenant isolation, and OIDC | [Security model](projects/05-tenantshield/docs/SECURITY.md) |
| [MatchCore](projects/06-matchcore) | Price/time matching, exact risk reservations, forced journals, and restart-safe commands | [Invariants](projects/06-matchcore/docs/INVARIANTS.md) |
| [IndexForge](projects/07-indexforge) | Immutable indexes, bounded ingestion, authorized retrieval, and relevance evaluation | [Architecture](projects/07-indexforge/docs/ARCHITECTURE.md) |
| [ThreadLens](projects/08-threadlens) | JFR collection, bounded bytecode instrumentation, and differential diagnosis | [Architecture](projects/08-threadlens/docs/ARCHITECTURE.md) |
| [ByteGuard](projects/09-byteguard) | Bytecode control flow, abstract interpretation, taint propagation, and SARIF | [Analysis contract](projects/09-byteguard/docs/ARCHITECTURE.md) |
| [RouteWeaver](projects/10-routeweaver) | Route validation, exact small-instance oracles, optimization, and fenced publication | [Architecture](projects/10-routeweaver/docs/ARCHITECTURE.md) |

## Build and run

Prerequisites: JDK 25, Bash, Python 3, and the included Maven wrapper. Docker with Compose is required for database, broker, and issuer integration; it is unnecessary for MatchCore's CLI workflow.

```bash
# Run from the repository root with JDK 25 on PATH.
./scripts/portfolio.sh verify matchcore
./scripts/portfolio.sh integration matchcore
./scripts/portfolio.sh demo matchcore
```

The demo checks allocation order, replacement priority, command deduplication, and recovery after an interrupted durable cancellation. It asserts four executions, eight lots traded, Alice's cash balance of 19,200 ticks, inventory of eight lots, and no open orders.

For a service project, use its documented configuration and lifecycle:

```bash
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger
```

`./scripts/portfolio.sh list` lists project entry points. `./mvnw -B -ntp verify` builds the ordinary reactor. See [development](docs/development.md) for toolchain setup, isolated dependency caches, integration tests, formatting, and shared build limits. In the managed cloud workspace, source `scripts/cloud-env.sh` before Maven.

## Design and verification

The portfolio favors explicit state transitions, checked integer arithmetic, bounded inputs, independent oracles, and tests at actual persistence and process boundaries. Frameworks provide infrastructure; the domain algorithms remain visible in project-local modules. [Architecture](docs/architecture.md) describes these boundaries.

Project status pages distinguish implementation, executed checks, and outstanding work. Historical benchmark packets identify the source and workload they measured; a later code change does not inherit those measurements. Repository-wide release validation, hosted CI, and vulnerability-feed access have separate requirements in the [acceptance matrix](docs/acceptance-matrix.md). There is no blanket production-readiness claim.

The [latest quality review](docs/quality-review.md) summarizes the current hardening changes, test results, interface checks, and remaining validation limits.

Use synthetic data and local demo credentials only. Start with the [security policy](SECURITY.md) before exposing a service. Dependency licenses and algorithm attribution are documented in [third-party components](THIRD_PARTY.md). Contributions follow the [engineering guide](CONTRIBUTING.md); the source is licensed under [Apache 2.0](LICENSE).
