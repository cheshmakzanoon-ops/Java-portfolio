# ClearLedger

ClearLedger is a multi-tenant accounting engine for a synthetic marketplace. Its core is a PostgreSQL transaction that posts a balanced journal, updates authoritative balances, and records the idempotency receipt and reporting event in one commit. It also supports full reversals, accounting-period closure, statement reconciliation, and reporting replay.

The project uses synthetic data and has no payment-network connection. Its guarantees cover the documented database and application boundaries; it makes no regulatory-compliance claim.

## Run locally

Requirements: JDK 25, the repository Maven wrapper, Docker with Compose, Bash, and Python 3. Run these commands from the repository root. In the managed workspace, first run `source scripts/cloud-env.sh` to select the pinned JDK and Maven proxy configuration.

```bash
./scripts/portfolio.sh verify clearledger
./scripts/portfolio.sh integration clearledger
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger
```

The API listens on `127.0.0.1:18100`; PostgreSQL uses `127.0.0.1:18101`. The lifecycle script explicitly selects the disposable `dev` authentication profile. Protected mode requires a trusted issuer configured through `--ledger.issuer=...`. Keep development mode on loopback and do not expose it through a public proxy.

The demo asserts live HTTP results, recovers a committed JVM writer after termination, imports the same CSV twice, leaves ambiguous reconciliation candidates for review, and checks shadow reporting. `reset-demo.sh` resets only the named synthetic tenant. `down` stops services and retains the database volume.

## Design boundaries

- **Exact amounts:** HTTP inputs are decimal strings; the domain uses `BigInteger` minor units. CAD and USD have two decimal places; JPY has none. The versioned [currency fixture](fixtures/currency-scales-v1.csv) defines those scales. Individual amounts and cumulative debit/credit balances are bounded by `10^30 − 1` minor units; journals contain 2–100 lines.
- **Atomic posting:** the restricted database function validates and writes the journal, balances, receipt, and outbox event together. Application transactions use serializable isolation with bounded retries of the complete operation.
- **Tenant identity:** verified JWT claims supply the tenant. Request bodies cannot select another tenant; repository queries bind tenant identifiers and composite foreign keys preserve their relationships.
- **Reporting consistency:** PostgreSQL journals and balances are authoritative. The reporting projector exposes freshness, tracks applied events individually, and promotes a shadow replay only after comparison with the source.
- **Reconciliation:** only an unambiguous reference, currency, and amount match is automatic. Manual decisions retain actor, reason, and evidence. Invalid UTF-8 and NUL-containing imports are rejected before database access; malformed rows remain visible in valid files.

## Verification and further reading

Ordinary verification covers domain properties, strict request parsing, transaction retry behavior, and local validation. The integration profile exercises real PostgreSQL permissions, concurrency, recovery, reconciliation, and signed HTTP identities. Docker is required; a missing database is a failed prerequisite.

For workload development, `projects/01-clearledger/scripts/benchmark.sh 1000 1` is a functional smoke run. Full measurements and JMH require an otherwise idle host. The optional Maven `benchmarks` profile adds the isolated CPU benchmark module. See [performance](docs/PERFORMANCE.md) for executed results and their limits; a smoke run does not establish capacity.

[Architecture](docs/ARCHITECTURE.md) · [Invariants](docs/INVARIANTS.md) · [Operations](docs/OPERATIONS.md) · [Security](docs/SECURITY.md) · [API contract](contracts/openapi.yaml) · [Verification status](docs/STATUS.md) · [Technical walkthrough](docs/INTERVIEW.md)

Dependencies retain their upstream licenses. All fixtures are synthetic.
