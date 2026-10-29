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

## Explain and lint a policy before publishing it

Two administrator-only routes work on a draft and touch no tenant state or audit log. Like `simulate`, they require current administrative membership.

`POST /v1/tenants/{tenant}/explain` takes the same body as `simulate` and returns the decision together with its cause: the effective role closure, whether an RBAC grant exists, and for every applicable rule its three-valued result, the canonical condition text, and the attributes the request did not supply. For a request that supplies only `subject.clearance` it reports, for example:

```text
INDETERMINATE  roles=[editor, reader]  granted=true
  clearance  ALLOW  UNKNOWN  missing=[resource.classification, subject.suspended]
             subject.clearance >= resource.classification and not subject.suspended
  freeze     DENY   UNKNOWN  missing=[subject.suspended]
             subject.suspended
```

`POST /v1/tenants/{tenant}/lint` takes `{"policy": Definition}` and returns the policy digest, error and warning counts and the findings. Each finding is a statement about the policy, proved over every possible input including requests that omit attributes (which make a comparison UNKNOWN, never TRUE):

| Code | Severity | What is proved |
| --- | --- | --- |
| `NEVER_MATCHES` | warning | The condition cannot be TRUE for any input, so the rule has no effect |
| `DENY_ALWAYS_APPLIES` | warning | The DENY condition is TRUE for every request, so its whole scope is denied |
| `ALLOW_SHADOWED_BY_DENY` | warning | Whenever the ALLOW rule is TRUE, some DENY rule is TRUE or UNKNOWN (alone or together), so it can never lead to ALLOW |
| `SCOPE_NEVER_ALLOWS` | error | Every ALLOW rule in an action/resource scope is unreachable, so no request can be allowed |
| `ALLOW_WITHOUT_GRANT`, `GRANT_WITHOUT_ALLOW_RULE` | warning | An ALLOW rule has no role grant, or a grant has no ALLOW rule, so the scope can never allow |
| `ALLOW_UNCONDITIONAL`, `DUPLICATE_RULE`, `DUPLICATE_SET_ELEMENT`, `UNUSED_ROLE` | info or warning | Redundancy and dead structure |
| `ANALYSIS_LIMIT` | info | A bounded search was not settled; no claim is made about that rule |

For the draft `open: ALLOW subject.clearance >= 3` with `freeze: DENY subject.clearance >= 1`, it reports `ALLOW_SHADOWED_BY_DENY` for `open` and `SCOPE_NEVER_ALLOWS` for the scope. Findings never rely on a guess: the search is exhaustive over a finite set of representative attribute values (every constant in the policy, its neighbours and the absent state) and role sets, and an undecided search is reported as `ANALYSIS_LIMIT`. Comparisons between two different attributes, such as `resource.department == subject.department`, are treated as free propositions, so the analysis stays sound but may miss a finding that depends on how two attributes relate. See [architecture](docs/ARCHITECTURE.md#static-policy-analysis) for the method and its verification.

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
