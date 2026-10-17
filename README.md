# Java engineering portfolio

Ten independent Java 25 engineering projects are under implementation against the [master brief](docs/JAVA_PORTFOLIO_MASTER_EXECUTION_BRIEF.md). This is an execution checkpoint, not a completed portfolio release.

## Build

Install JDK 25, then run `./mvnw -B -ntp verify`. In the managed cloud workspace, first `source scripts/cloud-env.sh`. Maven Wrapper pins Maven 3.10.0 and verifies its distribution checksum. Integration tests additionally require Docker.

| Project | Contract and status |
|---|---|
| ClearLedger | [projects/01-clearledger](projects/01-clearledger) |
| DurableFlow | [projects/02-durableflow](projects/02-durableflow) |
| QuorumKV | [projects/03-quorumkv](projects/03-quorumkv) |
| PulseStream | [projects/04-pulsestream](projects/04-pulsestream) |
| TenantShield | [projects/05-tenantshield](projects/05-tenantshield) |
| MatchCore | [projects/06-matchcore](projects/06-matchcore) |
| IndexForge | [projects/07-indexforge](projects/07-indexforge) |
| ThreadLens | [projects/08-threadlens](projects/08-threadlens) |
| ByteGuard | [projects/09-byteguard](projects/09-byteguard) |
| RouteWeaver | [projects/10-routeweaver](projects/10-routeweaver) |

Recommended deep dives: ClearLedger for accounting invariants, DurableFlow for failure semantics, ThreadLens for JVM diagnostics. See the [acceptance matrix](docs/acceptance-matrix.md), [toolchain](docs/technology-baseline.md), and [execution checkpoint](docs/execution/checkpoint.md) for observed progress and limitations.
