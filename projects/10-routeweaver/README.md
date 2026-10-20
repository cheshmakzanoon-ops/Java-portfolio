# RouteWeaver

RouteWeaver is a synthetic fleet-dispatch application that treats route feasibility and publication as separate correctness boundaries. It combines immutable planning inputs, an independent route validator, original insertion and directed-route moves, Timefold improvement search, and PostgreSQL-backed solve jobs.

The dispatcher can inspect routes and shift timelines, compare a candidate with its same-input baseline, request cancellation, and explicitly publish a validated incumbent. Travel times, coordinates, jobs, and vehicles are fictional.

## Run the demonstration

From the repository root, use Java 25, the pinned Maven wrapper, and Docker with Compose. In the managed cloud environment, first source `scripts/cloud-env.sh`.

```sh
projects/10-routeweaver/scripts/build.sh
projects/10-routeweaver/scripts/up.sh
projects/10-routeweaver/scripts/demo.sh
projects/10-routeweaver/scripts/down.sh
```

The dispatcher listens at `http://127.0.0.1:19000` during the demo. The launcher explicitly enables a disposable local profile; the page needs no token in that profile. Browser requests must use a loopback Host and the same origin. A protected deployment instead requires an OIDC issuer, tenant-scoped tokens, and read/write scopes; see [security](docs/SECURITY.md).

The demo creates 60 deliveries and six vehicles, publishes a valid plan, observes completed stops, records a breakdown, releases applicable commitments through an audit entry, inserts urgent depot cargo, and replans. It asserts preserved completed history and physical cargo, then verifies that an older plan cannot be published. To repeat the seeded scenario, run `projects/10-routeweaver/scripts/reset-demo.sh` before the next demo. Shutdown preserves database state.

## Verify changes

```sh
source scripts/cloud-env.sh
./mvnw -f projects/10-routeweaver/pom.xml -B -ntp verify
./mvnw -f projects/10-routeweaver/pom.xml -B -ntp -Pintegration verify
./mvnw -f projects/10-routeweaver/pom.xml -B -ntp -Pmutation verify
python3 projects/10-routeweaver/scripts/check-ui.py
python3 projects/10-routeweaver/scripts/test-runtime.py
```

Ordinary verification covers the domain, independent oracle, planning properties, real OIDC/HTTP input checks, formatting, and coverage. Integration adds PostgreSQL tests and requires Docker; missing Docker is a failure. The UI check uses installed Chromium and the existing Playwright Python package with deterministic API fixtures. It verifies browser behavior separately from the live-service check in `scripts/browser-check.py`.

Performance runs use `scripts/benchmark.sh` on an otherwise idle host. The [performance report](docs/PERFORMANCE.md) records the measured source, complete corpus outcomes, variability, and cooperative-budget limits. Historical measurements do not certify later source changes.

## Engineering boundaries

- Integer units and checked arithmetic govern distance, time, load, and objective calculations.
- An independent validator checks the complete directed route, return arc, assigned/unassigned partition, skills, capacity, windows, and frozen work.
- Leases and fencing protect durable job writes. Publication separately compares the active problem version and published-plan revision.
- Replanning begins at observed stop boundaries. Cargo stays on its observed vehicle; failed onboard cargo is reported as stranded.

Release 1 uses one declared depot and synthetic directed travel. It does not model road interpolation, cargo transfer, real traffic, or large-instance optimality.

Read the [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [API contract](contracts/API.md), [operations](docs/OPERATIONS.md), [decisions](docs/DECISIONS/), and [case study](docs/CASE_STUDY.md). The [validation status](docs/STATUS.md) distinguishes current checks from historical evidence and outstanding release work.

Current verification: [73-method integration/mutation and packaged-browser recovery record](evidence/deep-hardening-current.json). See [validation status](docs/STATUS.md) for source boundaries and retained limitations.
