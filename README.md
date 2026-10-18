# Java engineering portfolio

Ten independent Java 25 projects explore transaction correctness, durable workflows, consensus, streaming, authorization, matching, search, JVM observation, bytecode analysis and route optimization. Each owns its original core, adapters, synthetic fixtures and executable evidence. The root shares build policy and dependency versions; projects do not call one another to pass their standalone tests.

**Release status: in progress.** Correctness milestones are implemented and tested; full performance studies, remaining live checks and final release validation are underway. The [acceptance matrix](docs/acceptance-matrix.md) records individual gates. [GitHub Actions](https://github.com/cheshmakzanoon-ops/Java-portfolio/actions) contains the actual hosted run state; a hosted passing run has not yet been verified from this environment.

## Build and run

The tested Linux toolchain is Eclipse Temurin **25.0.4.1+1**, Maven **3.10.0** through the checksum-pinned wrapper, Docker with Compose, Bash and Python 3. Preview features are disabled. Docker is needed for database/broker/issuer integration tests and relevant service demos. Native Windows and macOS checks remain unverified.

The PowerShell entrypoint uses Git Bash for project lifecycle commands and for the verified runtime preparation of IndexForge and RouteWeaver. Install Git Bash alongside the pinned JDK and Python when trying that unverified Windows workflow.

```bash
git clone https://github.com/cheshmakzanoon-ops/Java-portfolio.git
cd Java-portfolio
./scripts/portfolio.sh doctor
./scripts/portfolio.sh verify clearledger
./scripts/portfolio.sh integration clearledger
./scripts/portfolio.sh up clearledger
./scripts/portfolio.sh demo clearledger
./scripts/portfolio.sh down clearledger
```

In the managed cloud workspace, first run `source scripts/cloud-env.sh`; [bootstrap instructions](docs/execution/environment.md) describe the pinned local installation. `./mvnw -B -ntp verify` builds the complete ordinary reactor. `./scripts/portfolio.sh list` lists standalone entry points; replace `clearledger` above with any slug below. QuorumKV's demo owns its three-process cluster; stop a previously started QuorumKV cluster before running that demo. MatchCore, ThreadLens and ByteGuard are CLI first; their `up` command prepares local state.

## Projects

| Project | Original contribution and tested scope | Demo slug | Design and evidence |
| --- | --- | --- | --- |
| [ClearLedger](projects/01-clearledger) | Balanced integer journals, serializable idempotency, reversals, source-derived projection/restore audit and reconciliation. | `clearledger` | [Case study](projects/01-clearledger/docs/CASE_STUDY.md) · [Status](projects/01-clearledger/docs/STATUS.md) |
| [DurableFlow](projects/02-durableflow) | Versioned typed replay, fenced task leases, durable timers/signals and explicit uncertainty for external effects. | `durableflow` | [Case study](projects/02-durableflow/docs/CASE_STUDY.md) · [Status](projects/02-durableflow/docs/STATUS.md) |
| [QuorumKV](projects/03-quorumkv) | Original Raft state machine, checksummed storage, snapshots, quorum read barriers and bounded history checking over real TLS processes. | `quorumkv` | [Case study](projects/03-quorumkv/docs/CASE_STUDY.md) · [Status](projects/03-quorumkv/docs/STATUS.md) |
| [PulseStream](projects/04-pulsestream) | Event-time windows, watermarks, bounded deduplication and atomic state/offset/outbox recovery over Kafka and PostgreSQL. | `pulsestream` | [Case study](projects/04-pulsestream/docs/CASE_STUDY.md) · [Status](projects/04-pulsestream/docs/STATUS.md) |
| [TenantShield](projects/05-tenantshield) | Bounded policy language, deny precedence, versioned immutable compilation and PostgreSQL tenant isolation with an external OIDC issuer. | `tenantshield` | [Case study](projects/05-tenantshield/docs/CASE_STUDY.md) · [Status](projects/05-tenantshield/docs/STATUS.md) |
| [MatchCore](projects/06-matchcore) | Indexed price/time matching, risk holds, replacement priority, stable request identity and forced-journal recovery. | `matchcore` | [Case study](projects/06-matchcore/docs/CASE_STUDY.md) · [Status](projects/06-matchcore/docs/STATUS.md) |
| [IndexForge](projects/07-indexforge) | Immutable generation publication, bounded source ingestion, authorized query planning/reranking and original retrieval metrics over Lucene. | `indexforge` | [Case study](projects/07-indexforge/docs/CASE_STUDY.md) · [Status](projects/07-indexforge/docs/STATUS.md) |
| [ThreadLens](projects/08-threadlens) | Bounded JFR collection, original startup bytecode probes, differential analysis and live/offline diagnostic reports. | `threadlens` | [Case study](projects/08-threadlens/docs/CASE_STUDY.md) · [Status](projects/08-threadlens/docs/STATUS.md) |
| [ByteGuard](projects/09-byteguard) | Original bytecode CFG/frame/taint fixed points, bounded interprocedural summaries, provenance and SARIF reporting using ASM parsing. | `byteguard` | [Case study](projects/09-byteguard/docs/CASE_STUDY.md) · [Status](projects/09-byteguard/docs/STATUS.md) |
| [RouteWeaver](projects/10-routeweaver) | Independent route validation, complete tiny-instance oracle, asymmetric local moves, Timefold adapter and fenced solve/publication lifecycle. | `routeweaver` | [Case study](projects/10-routeweaver/docs/CASE_STUDY.md) · [Status](projects/10-routeweaver/docs/STATUS.md) |

For backend correctness, start with [ClearLedger's invariants](projects/01-clearledger/docs/INVARIANTS.md). For distributed reliability, read [DurableFlow's external-effect decision](projects/02-durableflow/docs/DECISIONS/001-external-effects.md). For JVM depth, follow [ThreadLens's architecture](projects/08-threadlens/docs/ARCHITECTURE.md) and [overhead protocol](projects/08-threadlens/docs/PERFORMANCE.md).

## Verification and limits

Every project has a status page, invariants, operations/security guides, performance protocol and interview walkthrough. Status pages distinguish executed evidence from open work. Full measurements require an explicitly quiet reference host; shared-host diagnostics and smaller smoke runs do not satisfy them. Large recordings/build outputs stay outside Git, while small evidence envelopes preserve commands, source identities, results and artifact locations. The [coordinator checkpoint](docs/execution/checkpoint.md) records the current queue and resumption steps.

The [architecture](docs/architecture.md) and [pinned baseline](docs/technology-baseline.md) explain boundaries and third-party roles. Spring, PostgreSQL, Kafka, Lucene, JFR/ASM and Timefold supply their documented infrastructure; the repository claims the original domain/algorithm work described above. See [third-party attribution](THIRD_PARTY.md), [security reporting](SECURITY.md), [contributing](CONTRIBUTING.md) and the [license](LICENSE). Guarantees apply to documented bounded models and executed fixtures; these projects have no claimed commercial deployment history.
