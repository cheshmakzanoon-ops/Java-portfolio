# The Pit

The Pit is a deterministic, single-instrument exchange simulator with an indexed price-time order book, exact cash and inventory reservations, and process-crash recovery from a forced command journal. A bounded single-writer gateway separates concurrent admission from business-state mutation. An independent reference matcher checks generated histories after every transition.

Prices use integer ticks of 0.01 quote units; quantities use integral lots. All monetary arithmetic is checked. The simulator has no exchange connection, trading account, deposits, or real-money execution.

## Build and run

Use JDK 25, Bash, and the repository Maven wrapper. No Docker, database, or external account is required. Run these commands from the repository root; in the managed workspace, source `scripts/cloud-env.sh` first to activate the pinned JDK and trusted Maven proxy settings.

```bash
./mvnw -f projects/06-thepit/pom.xml -B -ntp verify
./mvnw -f projects/06-thepit/pom.xml -B -ntp -Pintegration,mutation verify
./scripts/portfolio.sh demo thepit
```

For a dedicated local dependency cache, add `-Dmaven.repo.local="$PWD/.work/A06/m2"`. Ordinary verification runs matching, reservation, reference-model, journal, gateway, and HTTP tests. The integration profile adds real child-JVM SIGKILL checks before append, during a frame, before force, after force and after application, plus four slow HTTP uploads that must release handler capacity. The mutation profile covers matching/model/checkpoint, journal and gateway code.

On Windows PowerShell, a native launcher runs the Maven verification and the same asserted
CLI demo without requiring Git Bash or Docker:

```powershell
.\scripts\portfolio.ps1 demo thepit
```

This entrypoint does not replace the Linux CI checks or claim independent Windows
certification; it creates an isolated synthetic journal for each run.

The CLI demo asserts FIFO allocation, priority-preserving reduction, priority-losing increase, retry deduplication, and recovery after an unacknowledged cancellation. Final state must contain four executions, eight lots traded, Alice cash 19,200, inventory eight, no open orders, twelve command results, and the same reference/replay digest. The ordinary demo injects a storage fault; it does not claim to perform the separate process-kill test.

## Interfaces and guarantees

| Boundary | Contract |
| --- | --- |
| Matching | Best price, then admission sequence; execution at the resting maker's price; GTC, IOC, FOK and post-only GTC |
| Replacement | Reduction preserves priority; increased quantity or a new price loses priority; rejection leaves the existing order and holds unchanged |
| Risk | Cash-only balances, no borrowing or short sales, checked reservations, explicit incoming self-trade prevention |
| Admission | Bounded queue, exact run admission count, stable account/command identity, retry coalescing with caller-owned futures, conflicting-payload rejection |
| Durability | Force the complete command before apply, publish, or response; retained local storage must honor `FileChannel.force(true)` |
| Recovery | Fail-stop journal after admitted I/O failure; validate complete records; truncate only an incomplete final frame; atomically validate optional checkpoints against state and prefix ledger |

Retry decisions compare exact commands, including after restart; persisted v1 hash values remain compatibility metadata. Standalone `Book.restore(checkpoint)` restores state and fails closed on historical retry lookup; the overload accepting the ordered original prefix restores that authority. Gateway supplies it from retained journal records without rematching the prefix.

Well-formed business rejections are durable sequenced results. Malformed input, unauthorized requests, and pre-admission overload do not allocate a sequence. A timeout has an unknown outcome: query or retry the same stable command ID rather than issuing a new identity.

## Optional HTTP adapter

```bash
projects/06-thepit/scripts/serve.sh
```

The default `trusted-local-demo` mode binds to `127.0.0.1:18600` and trusts `X-Demo-Account: alice`. This header is not authentication. Keep this mode local. Setting `THEPIT_ISSUER` selects protected mode with RS256/JWKS verification, issuer/audience/time validation, scopes, and subject/account ownership.

Command enums must use their documented string values; scalar coercion, fractional integers, null primitives, duplicate JSON keys, and trailing input are rejected. Query names are URL-decoded before validation; duplicate or unknown parameters and negative sequence/pagination bounds return 400. HTTP REPLACE uses `newRemainingQtyLots` to avoid ambiguous total-versus-remaining quantity. Responses prevent caching. The standalone server defaults request/response time budgets to five seconds; explicit JVM settings override these global JDK defaults, and embedded hosts own that policy.

## Design and evidence

Read [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [HTTP contract](contracts/http.md), [journal format](contracts/journal-v1.md), [security](docs/SECURITY.md), and [operations](docs/OPERATIONS.md) for implementation boundaries. [Verification status](docs/STATUS.md), [review](docs/REVIEW.md), and [performance](docs/PERFORMANCE.md) distinguish current checks from historical measurements.

The historical workload peaked at 27 open orders despite a configured 100,000-order cap. Its throughput does not establish deep-book capacity. Process-crash recovery provides no disk-loss tolerance, replication, hardware power-loss proof, exchange compliance, or production latency guarantee.
