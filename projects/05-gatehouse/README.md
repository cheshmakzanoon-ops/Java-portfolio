# Gatehouse

Gatehouse is a document authorization service that combines role inheritance with a bounded, statically typed attribute policy language. Its central guarantee is a fresh, tenant-scoped database snapshot for every decision: a compiled-policy cache hit never bypasses membership or revocation checks.

The implementation includes a policy compiler and evaluator, PostgreSQL persistence with row-level security, an OIDC-protected decision API, and a document API that requests authorization before each read. Authentication uses Spring Security and a local Keycloak fixture. Policy evaluation has no scripting, reflection, network access, or user-defined functions.

## Run the demonstration

Use JDK 25, the repository Maven wrapper, Docker with Compose, Bash, Python 3, and curl. Run commands from the repository root. In the managed workspace, first run `source scripts/cloud-env.sh` to select the pinned JDK and trusted Maven proxy configuration.

```bash
projects/05-gatehouse/scripts/up.sh
projects/05-gatehouse/scripts/demo.sh
projects/05-gatehouse/scripts/down.sh
```

The demo asserts department and clearance checks, cross-tenant denial, conservative handling of missing attributes, suspension precedence, and revocation after warming the policy cache. It obtains real local Keycloak tokens and does not print them. All four fixture ports, 18500–18503, bind to loopback. Shutdown preserves data; `scripts/reset-demo.sh` removes only the Harbor and Orchard fixtures.

## Authorization contract

A rule condition can combine approved, typed fields with role predicates:

```text
hasRole("reader")
  and resource.department == subject.department
  and subject.clearance >= resource.classification
```

The route tenant is bound to the verified `(issuer, subject)` through current database membership. Subject and resource attributes are server-owned. A matching RBAC grant and a true ALLOW rule are both required. Explicit DENY takes precedence; an applicable unknown condition produces INDETERMINATE. Explanations include stable rule IDs and observed policy, resource, and tenant versions. Compiler-owned immutable plans index both grants and conditions by action/resource type; the cache stores no membership or decision results.

The document API accepts only a complete, strictly typed ALLOW response within explicit byte/time limits. A malformed response, corrupt stored policy or dependency failure refuses access. Runtime startup verifies the database role and RLS/FORCE RLS on all nine authorization tables.

Each decision and its mandatory audit commit in one repeatable-read transaction. A snapshot established after a revocation commit must deny the revoked membership. An older snapshot may finish under its observed epoch. A remote authorization response does not make a subsequent resource write atomic with revocation.

Policy publication and rollback require one strong `If-Match` tag containing the prior active version, such as `"7"`; initial publication uses `"0"`. Malformed, weak, multiple, wildcard, and out-of-range tags return 400 before database access. A valid but stale version returns 409. Rollback creates and publishes a new immutable revision rather than moving the active pointer backward.

## Verify changes

```bash
./mvnw -f projects/05-gatehouse/pom.xml -B -ntp verify
./mvnw -f projects/05-gatehouse/pom.xml -B -ntp -Pintegration,mutation verify
python3 projects/05-gatehouse/scripts/test_process_lifecycle.py
```

For the managed workspace, add `-Dmaven.repo.local=/workspace/Java-portfolio/.work/A05/m2`. Ordinary verification needs no Docker. Integration uses actual PostgreSQL and Keycloak; missing Docker is an error. The mutation profile covers the policy engine, parser, evaluator and nested plan types at the unchanged 70% gate. Process tests exercise descendant cleanup and failed-startup resource ownership.

The historical reference study includes three 100,000-request HTTP runs and separate JMH compilation/evaluation measurements. Throughput declined substantially between runs; it establishes neither stable capacity nor a production service-level objective. See [performance](docs/PERFORMANCE.md) for all repetitions and measurement boundaries.

| Guide | Contents |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Module boundaries, transaction flow, caching and publication |
| [Invariants](docs/INVARIANTS.md) | Policy and tenant-isolation contracts |
| [Security](docs/SECURITY.md) | Trust boundaries, limits and deployment exclusions |
| [Operations](docs/OPERATIONS.md) | Startup, reset, diagnosis and runtime preparation |
| [API](contracts/openapi.yaml) · [Grammar](contracts/grammar.ebnf) | Request contracts and policy syntax |
| [Verification status](docs/STATUS.md) · [Review](docs/REVIEW.md) | Executed checks, historical evidence and open release work |
