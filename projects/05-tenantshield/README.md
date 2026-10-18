# TenantShield

A multi-tenant document authorization service with an original typed policy compiler, role inheritance, conservative three-valued ABAC semantics and fresh database snapshots. Every decision explains its rule matches, policy version, resource version and tenant authorization epoch. A small protected document service performs a new decision for each operation.

Requires JDK 25, Docker Compose, Python 3 and curl. From repository root:

```bash
projects/05-tenantshield/scripts/up.sh
projects/05-tenantshield/scripts/demo.sh
projects/05-tenantshield/scripts/down.sh
```

The asserted walkthrough obtains genuine local Keycloak tokens, publishes a department/clearance policy, permits Harbor Alice, denies cross-tenant and mismatched-department access, reports missing classification as INDETERMINATE, applies suspension deny, revokes membership after warming the AST cache, checks the new epoch and audit, then demonstrates the protected resource's denied response. It exits nonzero on any incorrect observation. Disposable fixture passwords are local only; tokens are never printed. Ports 18500–18503 bind loopback. Down preserves data; `scripts/reset-demo.sh` explicitly removes only Harbor/Orchard fixture records.

```bash
./mvnw -f projects/05-tenantshield/pom.xml -B -ntp verify
./mvnw -f projects/05-tenantshield/pom.xml -B -ntp -Pintegration verify
projects/05-tenantshield/scripts/benchmark.sh
```

In the managed cloud source `scripts/cloud-env.sh` first and pass `-Dmaven.repo.local=/workspace/Java-portfolio/.work/A05/m2`. Unit verification needs no Docker. Integration uses real PostgreSQL and Keycloak; missing Docker is an error. The prescribed workload seeds 50 tenants, 200 immutable revisions per tenant, 100 roles per revision and 1000 resources per tenant, then measures 100,000 authenticated HTTP requests three times. It separately measures cold compilation and warm AST evaluation with JMH. `benchmark-smoke.sh` is explicitly a smoke run and does not satisfy the full workload.

The AST cache never stores an ALLOW result; membership and epoch are read from a new repeatable-read snapshot even on a cache hit. Explicit denies take precedence and unknown applicable rules fail conservatively. PostgreSQL uses composite tenant keys, RLS, a non-owner runtime role and transaction-local tenant context. A snapshot established after a successful revocation commit must deny; a previously established snapshot may finish under its observed version. A remote decision alone does not make a later resource write atomic with revocation.

Read [reviewer demo](docs/DEMO.md), [technical walkthrough](docs/INTERVIEW.md), [architecture](docs/ARCHITECTURE.md), [invariants](docs/INVARIANTS.md), [policy grammar](contracts/grammar.ebnf), [OpenAPI](contracts/openapi.yaml), [security](docs/SECURITY.md), [operations](docs/OPERATIONS.md), [performance](docs/PERFORMANCE.md) and [execution status](docs/STATUS.md). Observed test/performance results are recorded only after execution; unclosed gates remain explicit in status.
