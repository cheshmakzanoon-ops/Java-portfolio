# Operations

Run from repository root: `projects/05-tenantshield/scripts/up.sh`, `scripts/demo.sh` under that project, then `scripts/down.sh`. JDK 25, Docker Compose, Python 3, curl and Maven Central access are required. Up builds/verifies the reactor, waits for PostgreSQL/Keycloak, runs privileged Flyway migrations in a separate process, seeds harbor/orchard, and starts both protected services. Default ports 18500 decision, 18501 document, 18502 Keycloak and 18503 database bind loopback. Override server port/address via standard Spring properties; override `tenantshield.decision-url` together with protected service configuration. The fixture scripts deliberately use the reserved port contract.

`GET /actuator/health` exposes only health, without secret details. Services/logs/PID files live under `.work/A05/runtime/`; bearer tokens are never printed by scripts. Down terminates only matching owned JAR processes and this Compose project, preserving PostgreSQL data. `reset-demo.sh` stops owned services and removes only harbor/orchard fixture records in one owner transaction, then up reseeds them. It does not globally prune Docker or remove workload tenants. The owner temporarily disables the immutable-delete trigger within that stopped, explicit reset transaction; runtime has no such privilege.

A 401 means token validation failed. A DENY/NO_MEMBERSHIP after revocation is expected. A 409 publication response requires rereading the active version and deliberately retrying with the new expected prior value. A 503 refuses authorization because database/digest/mandatory audit failed; inspect the local service log, runtime credentials and DB health. Restore dependencies and retry a fresh operation. Do not disable signature, audit, TLS, RLS or checks to force an allow.

Back up with PostgreSQL `pg_dump` using an owner/backup role; include membership, resources, immutable policy revisions, active pointer, tenant epochs and audit in a consistent backup. Restore into a separate empty PostgreSQL instance with the same migration schema/roles and validate active versions/FKs before directing service traffic. No live restore drill has been claimed unless recorded in evidence. Keep migration credentials out of application environment. Disposable fixture passwords must be replaced before any nonlocal deployment.

Launch scripts now verify a source fingerprint covering executable project inputs and the shared build contract. A changed input forces a verified rebuild before up; demo and benchmark reject services launched from another fingerprint. CLI helpers load only the runtime dependency graph packaged into the verification executable JAR, not arbitrary Maven cache/plugin/test JARs. Workload seed reuse checks policy digest and counts; `scripts/reset-workload.sh` explicitly removes only `load-0` through `load-49` and stops only owned services first. Harbor/Orchard demo data is retained.

Keycloak local fixture uses a bounded 768MiB heap and two visible processors. The five-minute initial readiness deadline includes Quarkus augmentation and realm import; authentication is never bypassed on a slow startup.

The protected-resource dependency refusal was also exercised live: after the asserted demo left Alice revoked, pausing only this project's PostgreSQL produced HTTP 503, and unpausing restored HTTP 403. To reproduce the same controlled failure on the running disposable stack, run from the repository root (the `finally` block restores the database):

```bash
PYTHONPATH=projects/05-tenantshield/demo python3 - <<'PY'
import subprocess, time, walkthrough
bearer = walkthrough.token('alice')
url = 'http://127.0.0.1:18501/v1/tenants/harbor/documents/doc-7'
compose = ['docker', 'compose', '-f', 'projects/05-tenantshield/infra/compose.yaml']
assert walkthrough.call('GET', url, bearer)[0] == 403, 'Run demo.sh first'
subprocess.run(compose + ['pause', 'postgres'], check=True)
try:
    assert walkthrough.call('GET', url, bearer)[0] == 503
finally:
    subprocess.run(compose + ['unpause', 'postgres'], check=True)
deadline = time.monotonic() + 20
while walkthrough.call('GET', url, bearer)[0] != 403:
    assert time.monotonic() < deadline, 'Database recovery deadline'
    time.sleep(.1)
print('PASS protected resource: 403 / 503 / 403')
PY
```

Tokens stay in process memory. This is a disposable fixture operation, not a production availability guarantee; do not pause another project's resources. The recorded run is in `evidence/protected-outage.json`.

The full workload can be prepared separately from measurement. On the running healthy stack, seed with the command below; it writes real database fixtures and prints progress, but collects no performance result. It is safe to rerun only when the existing seed passes its digest/count validation. Stop with `scripts/down.sh` after preparation when other projects need the reference host.

```bash
source projects/05-tenantshield/scripts/common.sh
export TENANTSHIELD_MIGRATION_URL=jdbc:postgresql://127.0.0.1:18503/tenantshield
export TENANTSHIELD_MIGRATION_USER=migration
export TENANTSHIELD_MIGRATION_PASSWORD=local-migration-only
bounded 1800 java -Xmx512m -cp "$(classpath)" \
  io.github.cheshmakzanoon.portfolio.tenantshield.Workload seed "$RUNTIME_DIR/seed.json"
unset TENANTSHIELD_MIGRATION_URL TENANTSHIELD_MIGRATION_USER TENANTSHIELD_MIGRATION_PASSWORD
```

The seed mode's output argument is reserved; row-count validation happens directly in PostgreSQL. Expected totals are 50 tenants, 10,000 immutable policy revisions, 1,000,000 role rows, 990,000 inheritance edges, 50,000 resources and 50 active policy pointers. A demo reset removes Harbor/Orchard only; these workload totals must remain unchanged. Repeat up/demo after reset to prove the interactive fixture is reproducible.

For a scheduled reference run, prepare outside the quiet window using `scripts/prepare-runtime.sh`. It starts no containers or application services: a missing/stale build runs the ordinary reactor verification, then records the unchanged source fingerprint and SHA256 of all three executable JARs. Every reuse checks those bytes. Extracted runtime classes and dependencies are checked against their exact packaged contents, including rejection of added, missing, modified or symlinked entries. A source stamp or `.complete` file alone is insufficient.

Use `TENANTSHIELD_NO_MAVEN=1 projects/05-tenantshield/scripts/up.sh` during the quiet reservation. This mode refuses an absent, changed or stale verified build before starting Docker; it never performs a surprise Maven rebuild. If the extracted runtime is damaged, preserve its evidence and explicitly remove only the identified owned cache directory before preparing again outside the reference window. The manifest provides local verification provenance, not remotely signed artifact attestation.

Compose and Testcontainers now pin PostgreSQL 18.3 and Keycloak 26.8.1 to the exact immutable registry digests observed in the passing real suite. These pins select the same tested image IDs; no version upgrade occurred. The retained seeded volume is unchanged.
