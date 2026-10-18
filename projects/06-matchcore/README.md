# MatchCore

A deterministic, single-instrument exchange **simulator**. Original indexed price-time matching, exact cash/inventory reservations, a forced local command journal, bounded single-writer admission, restart-safe command identities, independent reference matching and an authenticated HTTP adapter.

Java 25 and the repository Maven wrapper are required. No Docker, database, paid service or real trading account is used.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/06-matchcore/pom.xml -Dmaven.repo.local="$PWD/.work/A06/m2" -B -ntp verify
./mvnw -f projects/06-matchcore/pom.xml -Dmaven.repo.local="$PWD/.work/A06/m2" -B -ntp -Pintegration verify
./scripts/portfolio.sh demo matchcore
```

The demo asserts Bob then Carol maker allocation, priority-preserving reduction, priority-losing increase behind Dave, retry deduplication and cancellation forced before an unknown client outcome. It reopens the journal and verifies four executions, eight lots, Alice cash 19200, inventory 8, zero orders, twelve command results and the independent reference digest. `CrashIT` separately SIGKILLs actual child JVMs at four boundaries; the ordinary CLI uses an injected I/O fault and explicitly labels it.

`projects/06-matchcore/scripts/serve.sh` runs a disposable **trusted-local-demo** API on 127.0.0.1:18600. Supply `X-Demo-Account: alice`; this mode intentionally trusts local callers. Set `MATCHCORE_ISSUER` to an OIDC issuer URL to use protected mode: RS256/JWKS verification, issuer, audience `matchcore`, expiry, scopes and subject/account ownership are enforced by Spring Security's JWT decoder. See [API contract](contracts/http.md) and [security](docs/SECURITY.md).

[Architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [operations](docs/OPERATIONS.md), [performance](docs/PERFORMANCE.md), [case study](docs/CASE_STUDY.md), [current acceptance](docs/STATUS.md).

Durability means process-crash recovery on retained local storage honoring `FileChannel.force(true)`. There is no disk-loss tolerance, replication, exchange compliance or nanosecond claim. Full journals remain available; snapshots are an optional recovery acceleration, never authority over a corrupt log. Price ticks are 0.01 quote units; quantities are integral lots. All money uses checked integers.
