# Handoff

Handoff is a Java 25 workflow engine for finite, typed execution graphs. A deterministic interpreter makes decisions; PostgreSQL commits history and scheduled work atomically. External effects have explicit retry and uncertainty semantics, so a lost acknowledgement never becomes a claim that nothing happened.

The order-fulfilment and media-processing examples use synthetic data and a local HTTP provider simulator. This project is a bounded engineering implementation, not a production service or a substitute for an operational workflow platform.

## Build and run

Requirements: JDK 25, the repository Maven wrapper, Docker with Compose, Bash, Python 3, and curl. Run from the repository root. In the managed cloud workspace, source `scripts/cloud-env.sh` first to activate the pinned JDK and trusted Maven proxy configuration.

```bash
./mvnw -f projects/02-handoff/pom.xml -B -ntp verify
./mvnw -f projects/02-handoff/pom.xml -B -ntp -Pintegration verify
./scripts/portfolio.sh up handoff
./scripts/portfolio.sh demo handoff
./scripts/portfolio.sh down handoff
```

Ordinary verification runs model, replay, provider, build-artifact, and contract tests. The integration profile additionally starts PostgreSQL containers and exercises durable transactions, real process termination, JWT validation, and HTTP boundaries. An explicitly configured disposable native PostgreSQL 18 instance can run the same integration methods in isolated schemas; see [operations](docs/OPERATIONS.md#export-a-stable-history-without-blocking-workflow-writers). Missing Docker never silently selects another backend. The demo exits nonzero when an asserted recovery result fails and writes `projects/02-handoff/target/demo-result.json`.

The disposable runtime starts an API, two schedulers, three workers, PostgreSQL, and the provider simulator. Loopback ports are 18200 (API), 18201 (provider), and 18202 (PostgreSQL). `down` retains state; `./scripts/portfolio.sh reset-demo handoff` deletes this project's disposable database volume and provider log. Never reset data that is still needed to reconcile an unknown effect.

## Execution guarantees

- Each instance pins a definition content hash and interpreter semantics version. Upgrading a definition does not change running instances.
- History, state, and task creation share an instance transaction. Stable logical task IDs prevent replay from inserting duplicate work.
- Claims carry a lease generation and per-claim credential. Dispatch and completion verify that authorization against database time.
- Repeatable activities use a stable provider key. Expired non-idempotent calls with recorded dispatch intent stop in `UNKNOWN_OUTCOME` and require reconciliation.
- Cancellation is cooperative. Compensation follows recorded completion order in reverse and remains a separate, fallible business action.

These guarantees assume trusted compiled workers, retained PostgreSQL storage, and an external provider that honors its declared contract. Database fencing protects engine state; it cannot prevent an already authorized external call from taking effect.

## Code map

| Module | Responsibility |
| --- | --- |
| `workflow-model` | Immutable typed values, finite graph validation, canonical definition identity |
| `workflow-engine` | Pure replay, legal transitions, versioned compatibility adapters |
| `workflow-persistence` | PostgreSQL transactions, leases, timers, quotas, and operator decisions |
| `workflow-worker-sdk` | Bounded workers, fixed-destination provider transport, independent effect log |
| `workflow-api` | Tenant-scoped HTTP endpoints, role checks, and signed-token validation |
| `workflow-verification` | Contract tests, process-crash scenarios, and workload drivers |

## API and operations

The [OpenAPI contract](contracts/openapi.yaml) describes definition deployment, execution start/history, signal delivery, cancellation, reconciliation, and worker endpoints. Start and signal requests require idempotency keys. The protected profile requires `OIDC_ISSUER`, `OIDC_AUDIENCE`, a tenant claim, and the endpoint's role scope. The explicitly selected `dev` profile uses disposable Basic-auth accounts and refuses non-loopback binding.

Start inputs contain actual typed JSON scalars. For example:

```json
{
  "definitionHash": "<deployed definition hash>",
  "input": {
    "payload": {"type": "TEXT", "data": "order-42"},
    "context": {"order": {"type": "TEXT", "data": "synthetic-42"}}
  }
}
```

`INTEGER` requires a JSON integer and `BOOLEAN` requires a JSON boolean. Strings are not coerced. Activities consume recorded predecessor values; responses distinguish `VALUE`, `SKIPPED`, and `TIMED_OUT` outcomes.

Viewer reads use PostgreSQL read-only repeatable snapshots. Reading an execution does
not schedule activities, repair durable state, or wait on an instance write lock.
For larger histories, use `/v1/executions/{id}/history/page?limit=100`; follow
`nextAfter` with the returned `snapshotVersion` until `hasMore` is false. The
snapshot excludes later appends and every request checks the authenticated tenant.
The existing `/history` array response remains available for compatible clients.

Read [architecture](docs/ARCHITECTURE.md) for state and transaction boundaries, [invariants](docs/INVARIANTS.md) for guarantees, [operations](docs/OPERATIONS.md) for recovery procedures, and [security](docs/SECURITY.md) for the trust model. [Verification status](docs/STATUS.md) separates current checks from historical evidence. [Performance](docs/PERFORMANCE.md) describes the workload and its incomplete reference baseline; smoke measurements are not capacity claims.
