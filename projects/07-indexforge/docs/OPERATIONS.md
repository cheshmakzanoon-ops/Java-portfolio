# Operations

## Local CLI workflow

Run lifecycle scripts from any directory; they resolve the repository root and require a verified source fingerprint. `up` prepares local state and starts no daemon. `down` preserves data. `reset-demo` removes only named seeded demo directories; interrupted runs remain under ignored `.runtime/` for diagnosis.

One process owns each data directory through an OS file lock. Stop HTTP before using offline commands on its data. Use separate directories for independent CLI demonstrations; competing owners fail explicitly.

## Disposable HTTP service

After a successful integration build, start the service from the repository root:

```bash
java -Xmx2g -jar projects/07-indexforge/service/target/indexforge-service-1.0.0-SNAPSHOT-exec.jar \
  --spring.profiles.active=dev --server.address=127.0.0.1 --server.port=18700 \
  --indexforge.issuer-port=18701 \
  --indexforge.data=projects/07-indexforge/.runtime/http \
  --indexforge.sources=projects/07-indexforge/.runtime/sources
```

Development mode creates an ephemeral RSA issuer on loopback port 18701. It writes a one-hour disposable token to `DATA/dev-token` with owner-only POSIX permissions; it never prints the token. Read it directly into the HTTP client's Authorization header without logging or committing it. Alice maps to tenant `demo`, repository `public`, with administrative access. Bob maps to `other/public` without administrative access. Both token scopes and server-side repository permissions apply.

Place source snapshots under `SOURCES/TENANT/REPOSITORY`. For example, `sources/demo/public/project` is selected using `path: "project"`; selecting another tenant or repository is rejected. Development mode refuses a non-loopback bind. Stop the foreground service normally.

## Protected configuration

Use the `protected` profile with explicit `indexforge.issuer`, `indexforge.jwks`, and `indexforge.users`. The users mapping format is `subject:tenant:repo,repo:admin;...`. A verified JWT establishes identity; server mappings establish repository permissions. Configure a trusted issuer and short token lifetimes.

There is no administrative permission-management HTTP route. Restart with reviewed mappings, or use the in-process authorization API in an embedded deployment. Do not use development tokens or confidential input in a public deployment.

## Health and overload

`GET /health` reports process liveness; it does not verify every generation. Use CLI `verify-generation` for that check. Authenticated `/v1/metrics` reports searches, failed jobs, and queued work. Admission overload returns `429`.

Failed ingestion does not publish an incomplete index. Retry the registered job or original immutable manifest/key. Progress checkpoints are hints; durable manifests and content-addressed objects remain authoritative.

## Recovery and backup

1. Stop writers and preserve the complete data directory before repair.
2. Inspect `index/TENANT/quarantine`, restart, and verify the recovered generation. An invalid `CURRENT` can fall back to valid `PREVIOUS`.
3. If neither pointer validates, restore a consistent backup or ingest source snapshots into a fresh data directory. Do not edit checksums or completion receipts to bless corrupt state.

Back up with the service stopped, or use a filesystem-consistent snapshot. Restore content objects, jobs, indexes, and `cursor.key` together; never merge generations. The cursor key is a local secret; rotating it invalidates pagination.

Atomic rename and directory-force support are required. Source-root ancestors must remain under administrative control during ingestion; concurrent malicious parent replacement is outside the threat model. Content objects and job history require an operator retention policy.

Completion receipts bind schema, job, manifest, and generation with checksums. Historical unversioned markers are rejected. For disposable old demo data, use a new directory or the supported reset command. Preserve corrupt receipts and ambiguous pointers for investigation.

## Ranking and migration

The default remains `BASELINE/CURRENT`; existing candidate studies failed the quality guard. CLI experiments use JVM properties `-Dindexforge.ranking-mode=MODE -Dindexforge.ranking-profile=PROFILE`. HTTP accepts the matching Boot properties. Record the exact mode, profile, artifact, and returned scoring version.

Structural strategies additionally bind the parser artifact, JDK, catalog, and generation metadata. After a parser identity change, rebuild the matching catalog and explicitly reindex using the current artifact. An old structural generation must not silently supply observations to a new parser.

Evaluation is separate from service operation. Held-out seals bind the frozen model and cannot be bypassed by changing output directories. Existing protocols and results remain historical evidence; a new correctness build is not an additional quality or performance study.
