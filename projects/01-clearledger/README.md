# ClearLedger

A synthetic marketplace accounting engine with a privileged PostgreSQL posting boundary, durable idempotency receipts, full reversals, bank-statement review and verified reporting replay. Moves no money; no payment network or regulatory-compliance claim.

Requires JDK25, the repository Maven wrapper, Docker Compose and Python3. From the repository root:

```bash
./mvnw -f projects/01-clearledger/pom.xml -B -ntp verify
./mvnw -f projects/01-clearledger/pom.xml -B -ntp -Pintegration verify
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger
projects/01-clearledger/scripts/benchmark.sh 100000 3
```

`demo` checks live HTTP results, kills a committed JVM writer, imports the same CSV twice, shows unresolved ambiguity, and verifies shadow reporting. `reset-demo.sh` explicitly resets only the named synthetic tenant; `down` preserves the database volume. The default API is127.0.0.1:18100 and database127.0.0.1:18101. Configure a protected issuer using `--ledger.issuer=...`; only `up.sh` explicitly selects the disposable `dev` JWT profile.

[Architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [operations](docs/OPERATIONS.md), [security](docs/SECURITY.md), [OpenAPI](contracts/openapi.yaml), [measured results](docs/PERFORMANCE.md), [current gates](docs/STATUS.md), [walkthrough](docs/INTERVIEW.md).

Amounts use strict decimal strings at the boundary and BigInteger minor units internally. Input amount and cumulative debit/credit balances are each at most10^30−1 minor units; journals contain2–100 lines. CAD/USD use two decimal places; JPY uses zero, pinned by `fixtures/currency-scales-v1.csv`. PostgreSQL is the authoritative current ledger. Reporting exposes pending-event freshness.

Assisted-development provenance: implemented by coding agents under the owner's execution brief; no prior customers, deployments or employment history implied. Dependencies retain their upstream licenses. All fixtures are generated synthetic data.
