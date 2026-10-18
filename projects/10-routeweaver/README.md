# RouteWeaver

Constrained synthetic fleet dispatch with independently validated planning, original directed route moves, Timefold search, and durable PostgreSQL publication. All workload data is fictional.

From the repository root with JDK25, Maven wrapper, Docker/Compose:

```sh
projects/10-routeweaver/scripts/build.sh
projects/10-routeweaver/scripts/up.sh
projects/10-routeweaver/scripts/demo.sh
projects/10-routeweaver/scripts/down.sh
```

The live demo creates 60 deliveries/six vehicles, retains a same-version validated incumbent, publishes it, advances to observed stop boundaries, breaks a vehicle, audits release of its commitments, preserves completed history and onboard cargo, inserts a depot urgent delivery, uses the standby vehicle, and asserts stale publication returns409. Repeat seeded data only after the explicit `scripts/reset-demo.sh`. UI/API use http://127.0.0.1:19000. The launcher explicitly selects the trusted disposable loopback-only development profile. No real token is used in that profile.

Protected mode is the default outside that launcher: set ROUTEWEAVER_ISSUER to a real local OIDC issuer and ROUTEWEAVER_AUDIENCE (default routeweaver); tokens require verified signature/issuer/expiry, explicit subject/audience and a string tenant, plus routeweaver.read or routeweaver.write scopes. Set database credentials through environment bindings; committed credentials are disposable local PostgreSQL fixtures only. Do not bind development mode beyond loopback.

```sh
source scripts/cloud-env.sh
./mvnw -f projects/10-routeweaver/pom.xml -Dmaven.repo.local="$PWD/.work/A10/m2" -B -ntp -Pintegration verify
./mvnw -f projects/10-routeweaver/pom.xml -Dmaven.repo.local="$PWD/.work/A10/m2" -B -ntp -Pmutation verify
projects/10-routeweaver/scripts/benchmark.sh
```

Integration requires Docker and fails if unavailable. The benchmark driver preregisters ten deterministic100/250/500-job inputs,5/10/25 vehicles and three independent30-second runs, records real elapsed time, independent scores, heap observation, validation throughput and score traces; queue/HTTP latency is a distinct service boundary. Performance runs require an otherwise quiet reference host. `docs/STATUS.md` states observed gates and remaining gaps; implementation is not equivalent to released acceptance.


The [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [operations](docs/OPERATIONS.md), [security boundaries](docs/SECURITY.md), [performance protocol/results](docs/PERFORMANCE.md), [case study](docs/CASE_STUDY.md) and [engineering discussion](docs/INTERVIEW.md) describe the implemented guarantees and their limits. [Decisions](docs/DECISIONS/) explain the common depot and independent-validation/publication tradeoffs. [Demo walkthrough](demo/README.md) links the actual fixture generator and expected live observations.

Integer units, complete directed return evaluation, exact assigned/unassigned partition, physical residual cargo and same-input lexicographic retention are enforced independently from the solver. Published routes require current-input/revision compare-and-set; cancellation and worker expiry cannot silently install a plan. The tiny oracle certifies only exhausted bounded cases. Release 1 uses synthetic travel, a shared declared depot and observed stop-boundary replanning; it does not support cargo transfers, mid-road interpolation or large-instance optimality claims.

Executed results are tied to exact fingerprints in [evidence](evidence/) and [current status](docs/STATUS.md). Historical ordinary/PIT and PostgreSQL results remain separate from later source. Live demonstration and quiet workload claims are made only after their actual recorded runs; see [performance](docs/PERFORMANCE.md) for measured boundaries and open gates.
