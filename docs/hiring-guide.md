# Hiring reviewer guide

This repository is a **Java backend and systems engineering portfolio**, not ten deployed
products. Each project is isolated and has its own implementation, tests and failure contract.
The most useful evidence is runnable behavior and source, not a claimed seniority label.

## Five-minute path (no Docker)

Start with [The Pit](../projects/06-thepit/README.md), a deterministic price/time
matching engine. The demo requires JDK 25, Bash, Python 3, and the Maven wrapper;
it requires no account, network service, database, or container.

```bash
./scripts/portfolio.sh doctor thepit
./scripts/portfolio.sh verify thepit
./scripts/portfolio.sh demo thepit
```

For a Windows PowerShell walkthrough without Git Bash or Docker:

```powershell
.\scripts\portfolio.ps1 demo thepit
```

The PowerShell entrypoint runs Maven verification before executing the same asserted
The Pit Java demo with an isolated synthetic journal. Native Windows execution has
**not yet been independently certified**; report any toolchain or path failure rather
than treating this command as verified by Linux CI.

For a narrated version of the same ideas (price-time priority, queue priority under replacement, durable rejections, idempotent retry, restart from the journal and an independent replay audit), run `projects/06-thepit/scripts/pit.sh tour`; `pit.sh verify <journal>` replays any journal against a structurally independent matcher.

The demo asserts four executions, eight traded lots, exact end balances, no open orders,
idempotent commands and recovery from an interrupted cancellation. Follow the
[order-book implementation](../projects/06-thepit/matching-core/src/main/java/io/github/cheshmakzanoon/portfolio/thepit/Book.java),
[adversarial matching tests](../projects/06-thepit/matching-core/src/test/java/io/github/cheshmakzanoon/portfolio/thepit/BookTest.java),
[journal contract](../projects/06-thepit/contracts/journal-v1.md),
and [current verification status](../projects/06-thepit/docs/STATUS.md).

## Backend engineering path (25–45 minutes)

| Project | Engineering problem and tradeoff | Evidence to examine |
| --- | --- | --- |
| [Daybook](../projects/01-daybook/README.md) | Atomic double-entry journal + receipt + outbox, serializable retries, reversal and reconciliation | [Invariants](../projects/01-daybook/docs/INVARIANTS.md), [architecture](../projects/01-daybook/docs/ARCHITECTURE.md), [integration and status](../projects/01-daybook/docs/STATUS.md) |
| [Handoff](../projects/02-handoff/README.md) | Deterministic workflow replay and leases; ambiguous external effects remain explicitly unknown | [Architecture](../projects/02-handoff/docs/ARCHITECTURE.md), [invariants](../projects/02-handoff/docs/INVARIANTS.md), [verification](../projects/02-handoff/docs/STATUS.md) |
| [Gatehouse](../projects/05-gatehouse/README.md) | Tenant-scoped RBAC + ABAC, strict JWTs, audited decisions, revocation and cache correctness | [Architecture](../projects/05-gatehouse/docs/ARCHITECTURE.md), [security](../projects/05-gatehouse/docs/SECURITY.md), [verification](../projects/05-gatehouse/docs/STATUS.md) |

The service demonstrations above need Docker, Compose, JDK 25, Bash, Python 3, and
their documented local dependencies. From the repository root:

```bash
./scripts/portfolio.sh up daybook
./scripts/portfolio.sh demo daybook
./scripts/portfolio.sh down daybook

./scripts/portfolio.sh up handoff
./scripts/portfolio.sh demo handoff
./scripts/portfolio.sh down handoff

./scripts/portfolio.sh up gatehouse
./scripts/portfolio.sh demo gatehouse
./scripts/portfolio.sh down gatehouse
```

These are synthetic local demonstrations, **not** live financial systems or production
identity services. Check each project's security and operations guide before exposing a port.

## Other specializations

- **Distributed systems:** [Majority](../projects/03-majority/README.md) shows Raft
  transitions, snapshots, and simulated failure schedules; [Late Arrival](../projects/04-latearrival/README.md)
  shows event-time watermarks and PostgreSQL/Kafka recovery.
- **JVM internals and security analysis:** [Faultline](../projects/09-faultline/README.md)
  builds bounded bytecode control-flow and taint analysis; [Backtrace](../projects/08-backtrace/README.md)
  handles JFR traces and instrumentation lifecycle.
- **Algorithms and performance:** [The Pit](../projects/06-thepit/README.md),
  [Stacks](../projects/07-stacks/README.md), and
  [Switchback](../projects/10-switchback/README.md) exercise matching, retrieval,
  and constrained planning respectively.

## What the portfolio proves — and what it does not

The [21 October continuation](job-readiness-20261021.md) records **1,030 ordinary Java
methods and 54 selected integration methods**, plus **114 shared tooling tests**, on
its stated source inputs. It also links the actual The Pit recovery demo, new
authorization regressions, and remaining release limits. Integration scopes not rerun
in that continuation are explicitly marked; earlier integration results are not
counted as current executions.

The [engineering review](quality-review.md) records 839 ordinary Java cases and 114
integration cases on its **2026-10-20** tested source, plus scoped mutation campaigns.
Those are historical, source-bound observations; later commits require fresh checks.
Current release criteria and evidence are in the [acceptance matrix](acceptance-matrix.md)
and [release contract](release.md).

A successful local demo proves its stated assertions, not secure public deployment,
production capacity, uninterrupted uptime, or regulatory compliance. The release gate
is still incomplete. The open work includes Stacks's ranking acceptance target,
Backtrace's full overhead protocol, upstream container vulnerabilities, the live OWASP
NVD scan, and independent hosted CI/release verification. Historical failed experiments
remain visible. Unsupported Windows/macOS setups are not described as certified.

## Questions worth asking in an interview

1. What happens when an operation committed to PostgreSQL but the client loses the reply?
   Review Daybook's idempotency receipt and Handoff's external-effect uncertainty.
2. What is persisted before an acknowledged match can leave the journal? Review The Pit's
   `FileChannel.force(true)` contract, corrupt-frame recovery, and independent replay.
3. What prevents a cached authorization plan from bypassing a membership revocation?
   Review Gatehouse's transaction snapshot, versioned policy cache, and tenant claims.
4. How does a minority partition avoid authorizing a quorum read, and how are snapshots
   published after interrupted transfer? Review Majority's Raft/transport boundaries.
5. Which benchmarks failed and why? Reconcile source identifiers, raw measurements, and
   acceptance thresholds rather than relying on a historical speed headline.

This guide is intended to make **reviewing** the work easier. It is not a certification of
professional experience or a substitute for explaining the code and tradeoffs in person.
