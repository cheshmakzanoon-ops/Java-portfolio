# DurableFlow

A Java 25 workflow engine with a small deterministic interpreter and PostgreSQL as the durable serialization boundary. It exposes uncertain external effects instead of claiming exactly-once execution.

Six Maven modules separate immutable graph definitions, the pure replay interpreter, JDBC persistence, trusted workers, the Spring Boot HTTP adapter, and executable verification. Order fulfilment and media-processing fixtures use synthetic data.

```bash
./mvnw -f projects/02-durableflow/pom.xml -B -ntp verify
./mvnw -f projects/02-durableflow/pom.xml -B -ntp -Pintegration verify
./scripts/portfolio.sh up durableflow
./scripts/portfolio.sh demo durableflow
projects/02-durableflow/scripts/benchmark.sh --smoke
./scripts/portfolio.sh down durableflow
```

Requirements: JDK 25, Maven wrapper network access, Docker Compose, curl, Bash, and Python 3. The local disposable profile binds to loopback on 18200 (API), 18201 (provider), and 18202 (PostgreSQL). `up` builds and starts an API, two scheduler processes, three workers, a durable fake HTTP provider, and PostgreSQL. The full benchmark expands to eight worker processes and submits 10,000 executions with eight activities each, duplicate signals, 20% first-attempt rejection, and 5,000 timers. `reset-demo` deletes only this project's disposable database volume and provider log.

For reserved quiet measurements, stop owned runtime and run `projects/02-durableflow/scripts/prepare.sh` first, then export `DURABLEFLOW_PREPARED=1`. Startup validates unchanged source/dependencies, successful verification and all runtime JAR hashes without running Maven. Missing or stale preparation refuses startup. See [operations](docs/OPERATIONS.md). Ordinary and integration commands always execute their tests.

The asserted demo kills actual worker processes after an independent provider effect is logged, rejects their stale acknowledgements, recovers an idempotent receipt, pauses a non-idempotent unknown outcome, appends an operator resolution, restarts a scheduler during a wait, preserves a pinned definition across upgrade, demonstrates visible compensation failure, and joins parallel media activities. It writes `target/demo-result.json`; failures produce a nonzero exit.

Guarantees: instance history and scheduling change atomically; logical task IDs remain stable on replay; lease generations fence engine state; signal/timeout races have one serialized winner; non-idempotent dispatch ambiguity stops automatic work. External uniqueness additionally depends on provider idempotency and receipt retention. Compensation is a separate business effect, not rollback.

The runtime carries real scalar values, rather than strings with type labels. For the order fixture, the start body has `"input":{"payload":{"type":"TEXT","data":"order-42"},"context":{"order":{"type":"TEXT","data":"synthetic-42"}}}` alongside its `definitionHash`. INTEGER data must be a JSON integer and BOOLEAN data a JSON boolean; string coercion is rejected. Activities consume their predecessors' recorded outputs. Responses expose VALUE, SKIPPED and TIMED_OUT outcomes. [ADR 002](docs/DECISIONS/002-typed-semantics.md) explains joins, timeouts and the frozen legacy semantics adapter.

See [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [operations](docs/OPERATIONS.md), [security](docs/SECURITY.md), [performance](docs/PERFORMANCE.md), and [current verified status](docs/STATUS.md). Evidence and limitations are recorded after execution; the presence of a command is not a claim that its release gate passed.
