# Hiring reviewer guide

This repository is a **Java backend and systems engineering portfolio**, not ten deployed
products. Each project is isolated and has its own implementation, tests and failure contract.
The most useful evidence is runnable behavior and source, not a claimed seniority label.

## Five-minute path (no Docker)

Start with [MatchCore](../projects/06-matchcore/README.md), a deterministic price/time
matching engine. The demo requires JDK 25, Bash, Python 3, and the Maven wrapper;
it requires no account, network service, database, or container.

```bash
./scripts/portfolio.sh verify matchcore
./scripts/portfolio.sh demo matchcore
```

The demo asserts four executions, eight traded lots, exact end balances, no open orders,
idempotent commands and recovery from an interrupted cancellation. Follow the
[order-book implementation](../projects/06-matchcore/matching-core/src/main/java/io/github/cheshmakzanoon/portfolio/matchcore/Book.java),
[adversarial matching tests](../projects/06-matchcore/matching-core/src/test/java/io/github/cheshmakzanoon/portfolio/matchcore/BookTest.java),
[journal contract](../projects/06-matchcore/contracts/journal-v1.md),
and [current verification status](../projects/06-matchcore/docs/STATUS.md).

## Backend engineering path (25–45 minutes)

| Project | Engineering problem and tradeoff | Evidence to examine |
| --- | --- | --- |
| [ClearLedger](../projects/01-clearledger/README.md) | Atomic double-entry journal + receipt + outbox, serializable retries, reversal and reconciliation | [Invariants](../projects/01-clearledger/docs/INVARIANTS.md), [architecture](../projects/01-clearledger/docs/ARCHITECTURE.md), [integration and status](../projects/01-clearledger/docs/STATUS.md) |
| [DurableFlow](../projects/02-durableflow/README.md) | Deterministic workflow replay and leases; ambiguous external effects remain explicitly unknown | [Architecture](../projects/02-durableflow/docs/ARCHITECTURE.md), [invariants](../projects/02-durableflow/docs/INVARIANTS.md), [verification](../projects/02-durableflow/docs/STATUS.md) |
| [TenantShield](../projects/05-tenantshield/README.md) | Tenant-scoped RBAC + ABAC, strict JWTs, audited decisions, revocation and cache correctness | [Architecture](../projects/05-tenantshield/docs/ARCHITECTURE.md), [security](../projects/05-tenantshield/docs/SECURITY.md), [verification](../projects/05-tenantshield/docs/STATUS.md) |

The service demonstrations above need Docker, Compose, JDK 25, Bash, Python 3, and
their documented local dependencies. From the repository root:

```bash
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger

./scripts/portfolio.sh up durableflow
./scripts/portfolio.sh demo durableflow
./scripts/portfolio.sh down durableflow

./scripts/portfolio.sh up tenantshield
./scripts/portfolio.sh demo tenantshield
./scripts/portfolio.sh down tenantshield
```

These are synthetic local demonstrations, **not** live financial systems or production
identity services. Check each project's security and operations guide before exposing a port.

## Other specializations

- **Distributed systems:** [QuorumKV](../projects/03-quorumkv/README.md) shows Raft
  transitions, snapshots, and simulated failure schedules; [PulseStream](../projects/04-pulsestream/README.md)
  shows event-time watermarks and PostgreSQL/Kafka recovery.
- **JVM internals and security analysis:** [ByteGuard](../projects/09-byteguard/README.md)
  builds bounded bytecode control-flow and taint analysis; [ThreadLens](../projects/08-threadlens/README.md)
  handles JFR traces and instrumentation lifecycle.
- **Algorithms and performance:** [MatchCore](../projects/06-matchcore/README.md),
  [IndexForge](../projects/07-indexforge/README.md), and
  [RouteWeaver](../projects/10-routeweaver/README.md) exercise matching, retrieval,
  and constrained planning respectively.

## What the portfolio proves — and what it does not

The [engineering review](quality-review.md) records 839 ordinary Java cases and 114
integration cases on its **2026-10-20** tested source, plus scoped mutation campaigns.
Those are historical, source-bound observations; later commits require fresh checks.
Current release criteria and evidence are in the [acceptance matrix](acceptance-matrix.md)
and [release contract](release.md).

A successful local demo proves its stated assertions, not secure public deployment,
production capacity, uninterrupted uptime, or regulatory compliance. The release gate
is still incomplete. The open work includes IndexForge's ranking acceptance target,
ThreadLens's full overhead protocol, upstream container vulnerabilities, the live OWASP
NVD scan, and independent hosted CI/release verification. Historical failed experiments
remain visible. Unsupported Windows/macOS setups are not described as certified.

## Questions worth asking in an interview

1. What happens when an operation committed to PostgreSQL but the client loses the reply?
   Review ClearLedger's idempotency receipt and DurableFlow's external-effect uncertainty.
2. What is persisted before an acknowledged match can leave the journal? Review MatchCore's
   `FileChannel.force(true)` contract, corrupt-frame recovery, and independent replay.
3. What prevents a cached authorization plan from bypassing a membership revocation?
   Review TenantShield's transaction snapshot, versioned policy cache, and tenant claims.
4. How does a minority partition avoid authorizing a quorum read, and how are snapshots
   published after interrupted transfer? Review QuorumKV's Raft/transport boundaries.
5. Which benchmarks failed and why? Reconcile source identifiers, raw measurements, and
   acceptance thresholds rather than relying on a historical speed headline.

This guide is intended to make **reviewing** the work easier. It is not a certification of
professional experience or a substitute for explaining the code and tradeoffs in person.
