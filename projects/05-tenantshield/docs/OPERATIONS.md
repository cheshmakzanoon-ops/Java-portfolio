# Operations

## Build, start and stop

Run from the repository root with JDK 25, the Maven wrapper, Docker Compose, Python 3, Bash and curl. In the managed workspace, `source scripts/cloud-env.sh` selects the pinned JDK and trusted Maven configuration; no TLS verification bypass is needed.

```bash
source scripts/cloud-env.sh
./mvnw -f projects/05-tenantshield/pom.xml -B -ntp verify
projects/05-tenantshield/scripts/up.sh
projects/05-tenantshield/scripts/demo.sh
projects/05-tenantshield/scripts/down.sh
```

The managed cache can be selected with `-Dmaven.repo.local=/workspace/Java-portfolio/.work/A05/m2`. `up.sh` prepares verified executable artifacts, starts the PostgreSQL/Keycloak Compose project, migrates in a separate privileged process, seeds Harbor/Orchard and launches the two Java services. It waits for actual service readiness. PostgreSQL 18.6 Alpine and Keycloak 26.8.1 are pinned to registry digests in both Compose and integration tests. Initial Keycloak augmentation/import can be slow; Compose waits at most seven minutes; integration waits at most five minutes. The fixture uses a 768 MiB heap and two visible processors.

| Loopback port | Component | Readiness |
| --- | --- | --- |
| 18500 | Decision service | `GET /actuator/health`, no secret details |
| 18501 | Protected document service | An unauthenticated document read returns 401 |
| 18502 | Keycloak fixture | Realm discovery/token endpoint |
| 18503 | PostgreSQL | Compose health check, followed by migration/runtime-role verification |

Logs, PID files, service fingerprints and extracted runtime files live in `.work/A05/runtime/`. Tokens stay in memory and are not printed by the walkthrough. Default scripts deliberately use the reserved ports. Direct service deployments can override standard Spring address/port properties, `tenantshield.decision-url`, `tenantshield.oidc.issuer`, `tenantshield.oidc.jwks` and `tenantshield.oidc.audience`; configure both services consistently.

`down.sh` stops only matching owned JAR processes and this Compose project; it preserves the database volume. If startup fails, `up.sh` cleans its newly started service PIDs and newly created container IDs while preserving preexisting containers and volumes. It retains the failing exit status. Finite helper commands own a process group: normal exit, deadline and terminal signal all clean resistant descendants. A deadline exits 124; a terminal signal preserves shell status `128 + signal`.

`reset-demo.sh` stops owned services and removes only Harbor/Orchard fixture records in one owner transaction. It temporarily disables the immutable-delete trigger inside that explicit disposable reset transaction; runtime has no such privilege. Run `up.sh` and the demo again afterward. `reset-workload.sh` removes only `load-0` through `load-49`. Neither command globally prunes Docker or removes the other fixture set.

## API examples

Obtain a trusted token through the configured issuer. The following uses a token already held in `TENANTSHIELD_BEARER`; do not use `curl -v`, shell tracing or commit a token. The local [walkthrough](../demo/walkthrough.py) performs token acquisition and assertions without printing token values.

```bash
curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $TENANTSHIELD_BEARER" \
  -H 'Content-Type: application/json' \
  --data '{"action":"document:read","resource":{"type":"document","id":"doc-7"},"context":{"purpose":"support","hour":10}}' \
  http://127.0.0.1:18500/v1/tenants/harbor/decisions
```

A successful protocol exchange returns HTTP 200 with a decision such as:

```json
{"decision":"ALLOW","reason":"MATCHED_ALLOW","tenantEpoch":7,"policyVersion":1,"resourceVersion":1,"matchedRuleIds":["department-read"],"decisionId":"ca0fcf7b-c0f4-4bfb-9128-a7478da7a43c"}
```

Values above are illustrative. A valid denial also returns 200 from the decision API; the protected document API maps DENY to 403. Context accepts only `purpose` and int64 `hour`. These are caller annotations: `hour` is not trusted time. Subject/resource fields come from the database.

Create an immutable draft with a policy and 1–100 regression examples (see [fixture](../fixtures/policy.json)). A current tenant administrator can publish draft 8 when active version 7 is still current:

```bash
curl --fail-with-body --silent --show-error -X POST \
  -H "Authorization: Bearer $TENANTSHIELD_BEARER" -H 'If-Match: "7"' \
  http://127.0.0.1:18500/v1/tenants/harbor/policy-versions/8/publish
```

Use `"0"` for initial publication. Rollback uses the same precondition at `/policy-versions/{sourceVersion}/rollback`; it copies the validated source content to a new higher revision and publishes that revision atomically. On 409, obtain the current policy version from a fresh authorized decision and deliberately reassess the desired change before retrying. Never blindly repeat a stale publication.

## Diagnose and recover

| Observation | Meaning and action |
| --- | --- |
| 400 | Invalid JSON, reference, context, policy or precondition. Correct the request; quote one canonical int64 version. |
| 401 | Signature, issuer, audience, subject or time validation failed. Check issuer configuration and acquire a valid token. |
| Decision `DENY/NO_MEMBERSHIP` or document 403 | Current membership is absent/revoked, or policy denies. Check the tenant/principal and current administrative data. |
| 409 | Stale version, serialization failure or deadlock. Re-read committed state and deliberately retry the complete operation. |
| 413 / 429 | Body or admission limit reached. Reduce payload/concurrency/rate; retry with backoff. |
| 503 | Database, audit, persisted policy/attributes or decision protocol unavailable. Inspect the owned service logs and dependency health, repair the cause, then issue a fresh operation. |
| Startup runtime-role rejection | Restore the non-owner/non-administrative role and RLS/FORCE RLS on all nine tables. Verify migrations against an owner connection, then restart. |

A failed transaction rolls back membership, epoch and audit together. If commit acknowledgement is lost, the outcome may be ambiguous: inspect committed membership/version/audit before repeating a mutation. A timeout is not proof that PostgreSQL did not commit. SQLSTATE 40001/40P01 is a retryable conflict; other SQL errors refuse authorization.

For stored-policy digest/parse errors, isolate the tenant and inspect the active immutable revision using an authorized administrative path and owner-controlled backup. Restore a known valid backup or publish a validated higher revision. Do not edit immutable content in place or disable the digest check. Failed plan loads are not cached; repaired/new content is retried naturally. If an already warm plan exists, it is safe only under the documented immutable-storage contract; restart the service after an owner-level repair to clear plans.

For invalid stored attributes, repair membership through its administrative API or resource data through the owning maintenance process. String limits are 256 UTF-16 units, and text must have well-formed surrogate pairs. PostgreSQL `varchar(256)` counts characters differently for supplementary text; a database-valid value can still exceed the evaluator budget and correctly produce 503. Preserve the bound and correct the data. Never disable signature, audit, RLS, digest or parser checks to force an allow.

The historical [protected outage drill](../evidence/protected-outage.json) paused only this project's disposable PostgreSQL after the demo revoked Alice: document responses changed 403 → 503 → 403. Reproduce only on an isolated disposable fixture and always unpause in a `finally`/cleanup handler. Integration covers database/audit outages without requiring a manual pause. A local drill is not an availability guarantee.

The current image is PostgreSQL 18.6 Alpine. Historical reference data used Debian-based PostgreSQL 18.3. Preserve that volume for historical reproduction; changing libc/locale distributions requires an explicit export and restore into a fresh cluster, followed by collation, encoding and decision validation. A passing fresh-cluster suite does not certify a raw volume transplant.

Back up with PostgreSQL `pg_dump` under an owner/backup role, including membership, resources, immutable revisions, active pointers, epochs and audit in one consistent backup. Restore into a separate empty instance with matching roles/schema; verify foreign keys, RLS/runtime guard, active digests and representative decisions before switching traffic. A live backup/restore drill has not been recorded. Keep migration credentials outside running application environments.

## Artifact preparation and workload data

`prepare-runtime.sh` starts no containers or application services. If needed, it verifies the ordinary reactor, then records the source fingerprint and SHA-256 of all three executable JARs. Reuse verifies those bytes. Extraction loads only the packaged runtime graph and rejects added, missing, modified or symlinked entries. Source and shared build inputs participate in the fingerprint; `demo.sh` refuses a service started from another fingerprint. The manifest is local provenance, not remote signed attestation.

Use `TENANTSHIELD_NO_MAVEN=1 projects/05-tenantshield/scripts/up.sh` when a build is prohibited during a reserved measurement window. It refuses stale/missing/changed artifacts before Docker startup. Preserve any damaged extraction evidence and remove only the identified owned cache directory before preparing again. Preparation also lists JMH methods (`-l`) from the exact extracted runtime classpath and requires both methods; discovery performs no benchmark iterations. Archive-root BenchmarkList/CompilerHints are mapped and byte-verified under `classes/META-INF`.

The historical workload can be prepared independently of measurement on the healthy disposable stack:

```bash
source projects/05-tenantshield/scripts/common.sh
export TENANTSHIELD_MIGRATION_URL=jdbc:postgresql://127.0.0.1:18503/tenantshield
export TENANTSHIELD_MIGRATION_USER=migration
export TENANTSHIELD_MIGRATION_PASSWORD=local-migration-only
bounded 1800 java -Xmx512m -cp "$(classpath)" \
  io.github.cheshmakzanoon.portfolio.tenantshield.Workload seed "$RUNTIME_DIR/seed.json"
unset TENANTSHIELD_MIGRATION_URL TENANTSHIELD_MIGRATION_USER TENANTSHIELD_MIGRATION_PASSWORD
```

Seed reuse verifies digest and row counts. Expected totals are 50 tenants, 10,000 revisions, 1,000,000 roles, 990,000 edges, 50,000 resources and 50 active pointers. The seed mode's output path is reserved; it validates rows directly in PostgreSQL. This is a large data preparation command, not part of routine development verification. See [performance](PERFORMANCE.md) before running measurements; historical results do not certify later source changes.
