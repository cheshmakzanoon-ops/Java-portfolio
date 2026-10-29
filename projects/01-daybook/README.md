# Daybook

Daybook is a multi-tenant accounting engine for a synthetic marketplace. Its core is a PostgreSQL transaction that posts a balanced journal, updates authoritative balances, and records the idempotency receipt and reporting event in one commit. It also supports full reversals, accounting-period closure, statement reconciliation, and reporting replay.

The project uses synthetic data and has no payment-network connection. Its guarantees cover the documented database and application boundaries; it makes no regulatory-compliance claim.

## Run locally

Requirements: JDK 25, the repository Maven wrapper, Docker with Compose, Bash, Python 3, and Linux `/proc`/`flock` for the local lifecycle. Run these commands from the repository root. In the managed workspace, first run `source scripts/cloud-env.sh` to select the pinned JDK and Maven proxy configuration.

```bash
./scripts/portfolio.sh verify daybook
./scripts/portfolio.sh integration daybook
./scripts/portfolio.sh up daybook
./scripts/portfolio.sh demo daybook
./scripts/portfolio.sh down daybook
```

The API listens on `127.0.0.1:18100`; PostgreSQL uses `127.0.0.1:18101`. The lifecycle script explicitly selects the disposable `dev` authentication profile. Protected mode requires a trusted issuer configured through `--ledger.issuer=...`. Keep development mode on loopback and do not expose it through a public proxy.

The demo asserts live HTTP results, recovers a committed JVM writer after termination, imports the same CSV twice, leaves ambiguous reconciliation candidates for review, and checks shadow reporting. `reset-demo.sh` resets only the named synthetic tenant. `down` stops services and retains the database volume.

## Read the books

Beyond posting and statements, the API exposes the authoritative state directly (all `ledger.read`, all computed from posted lines rather than from reporting projections):

| Route | Purpose |
| --- | --- |
| `GET /v1/trial-balance?asOf=2026-06-30` | Per-currency total debits, total credits and a `balanced` flag, with every account's normal-side balance |
| `GET /v1/accounts`, `GET /v1/accounts/{id}` | Accounts with cumulative debits, credits and balance on the normal side; cursor paging |
| `GET /v1/journals/{id}` | A journal with numbered lines, exact amounts and links to the journal it reverses or is reversed by |
| `GET /v1/periods`, `POST /v1/periods` | List periods; open one (`ledger.write`) for an inclusive date range |

Opening a period used to need administrative SQL. `POST /v1/periods` calls a restricted `SECURITY DEFINER` function (migration V4), so the runtime role still has no table-level write privilege. An identical range is idempotent, any other overlap is `PERIOD_OVERLAP_CONFLICT`, and tenant creation remains a platform action. The trial balance includes every journal effective on or before `asOf`, so back-dating a posting changes the report for any later date; it is a statement of the books now, not a record of what was known on that date. Because every journal balances within one currency, a report whose `balanced` is false is a data-damage signal, not an accounting position.

## Design boundaries

- **Exact amounts:** HTTP inputs are decimal strings; the domain uses `BigInteger` minor units. CAD and USD have two decimal places; JPY has none. The versioned [currency fixture](fixtures/currency-scales-v1.csv) defines those scales. Individual amounts and cumulative debit/credit balances are bounded by `10^30 − 1` minor units; journals contain 2–100 lines.
- **Atomic posting:** the restricted database function validates and writes the journal, balances, receipt, and outbox event together. Application transactions use serializable isolation with bounded retries of the complete operation and distinguish acknowledged commit, unknown outcome, and cleanup failure.
- **Tenant identity:** verified JWT claims supply the tenant. Request bodies cannot select another tenant; repository queries bind tenant identifiers and composite foreign keys preserve their relationships.
- **Reporting consistency:** PostgreSQL journals and balances are authoritative. The reporting projector exposes freshness, tracks applied events individually, and promotes a shadow replay only after comparison with the source.
- **Reconciliation:** only an unambiguous reference, currency, and amount match is automatic. Manual decisions retain actor, reason, and evidence. Invalid UTF-8 and NUL-containing imports are rejected before database access; malformed rows remain visible in valid files. Parsing produces immutable input before database retries, and competing manual decisions cannot consume one posting twice.

## Verification and further reading

Ordinary verification covers domain properties, strict request parsing, transaction retry behavior, and local validation. The integration profile exercises real PostgreSQL permissions, compatible schema upgrades, forced transaction interleavings, injected projection failure, recovery, reconciliation, and signed HTTP identities. Docker is required; a missing database is a failed prerequisite.

For workload development, `projects/01-daybook/scripts/benchmark.sh 1000 1` is a functional smoke run. Full measurements and JMH require an otherwise idle host. The optional Maven `benchmarks` profile adds the isolated CPU benchmark module. See [performance](docs/PERFORMANCE.md) for executed results and their limits; a smoke run does not establish capacity.

[Architecture](docs/ARCHITECTURE.md) · [Invariants](docs/INVARIANTS.md) · [Operations](docs/OPERATIONS.md) · [Security](docs/SECURITY.md) · [API contract](contracts/openapi.yaml) · [Verification status](docs/STATUS.md) · [Technical walkthrough](docs/INTERVIEW.md)

Dependencies retain their upstream licenses. All fixtures are synthetic.
