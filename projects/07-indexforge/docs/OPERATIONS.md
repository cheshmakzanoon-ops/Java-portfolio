# Operations

Run the CLI scripts from any directory; they resolve the repository root and require a successful verified source fingerprint. No background process is started by `up`, and `down` preserves all data. `reset-demo` removes only this project's named seeded demo directories. Failed or interrupted demos are kept under ignored `.runtime/` for diagnosis.

Optional disposable local OIDC HTTP service:

```bash
java -Xmx2g -jar projects/07-indexforge/service/target/indexforge-service-1.0.0-SNAPSHOT-exec.jar \
 --spring.profiles.active=dev --server.address=127.0.0.1 --server.port=18700 \
 --indexforge.issuer-port=18701 --indexforge.data=projects/07-indexforge/.runtime/http \
 --indexforge.sources=projects/07-indexforge/.runtime/sources
```

The dev issuer generates an ephemeral RSA key, publishes real OIDC metadata/JWKS on loopback18701, and writes a one-hour disposable Alice token to `DATA/dev-token` with owner-only POSIX permissions. It never prints the token. Read that file into your HTTP client's Authorization header without committing or logging it. Default Alice has tenant demo/public/admin; Bob has other/public/non-admin (the local dev token factory grants search/ingest scopes; Bob can ingest only his allowed repository). Prepare each selected checkout under `SOURCES/TENANT/REPOSITORY`, for example `sources/demo/public/project`, then submit `path:"project"`; cross-repository/tenant path selection is rejected. Dev refuses a non-loopback bind. Stop the foreground process normally; no global container resources exist.

Protected profile: `--spring.profiles.active=protected --indexforge.issuer=ISSUER --indexforge.jwks=JWKS --indexforge.users='subject:tenant:repo,repo:admin;...'`. The users mapping is mandatory outside dev. A JWT proves identity; tenant/repository permissions come from the server mapping, not token-provided repository claims. Configure a trusted issuer and short token lifetime. This local service has no administrative policy HTTP route; restart with reviewed mappings or use the in-process policy API for embedded deployments. Runtime authorization-version changes are exercised by tests.

Health `/health` reports process liveness; generation verification is explicit CLI `verify-generation`. Inspect authenticated `/v1/metrics` for searches, failed jobs and queued work. Queue overload returns429. Ingestion failures never publish incomplete content. After crash, resume the authorized registered job ID through HTTP or CLI, or retry the original immutable manifest/key; durable completion avoids replaying successful work and registered incomplete work is rebuilt from authoritative manifest/CAS. Checkpoints are validated progress hints, never index authority.

Recovery: stop writers; preserve DATA; inspect index/TENANT/quarantine; restart and verify the generation. CURRENT falls back to PREVIOUS when validation fails. If both fail, restore a consistent backed-up DATA tree or reingest source snapshots into a fresh data directory; never hand-edit checksums to bless corruption. Back up only while service is stopped or by filesystem-level consistent snapshot. Restore CAS/jobs/index/cursor.key together; do not merge generations. Cursor keys are local secrets; rotating them invalidates existing pagination.

Ranking defaults to BASELINE until held-out evidence justifies a candidate. Select an evaluated mode explicitly with `--indexforge.ranking-mode=SOURCE_AWARE` for HTTP or JVM property `-Dindexforge.ranking-mode=SOURCE_AWARE` for CLI; this configuration is part of the deployment record.

Atomic rename and directory force support are required. Local source roots must be explicitly configured and administratively controlled during ingestion. The reader rejects symlinks and uses NOFOLLOW_LINKS for final files; concurrent malicious rename of parent directories is outside the current local snapshot trust model. No archives are extracted or classes loaded. Observe CAS/job retention and available disk space; cleanup is an operator action with the service stopped.

A process-wide file lock enforces one application owner per DATA directory. Stop the HTTP service before using offline CLI commands against its data; separate CLI demonstrations use isolated directories. Concurrent local commands fail explicitly rather than racing generation publication. Startup failure releases ownership, and ordinary shutdown closes it after generation readers.

Completion receipts use checksummed schema1 binding job, manifest and generation. Old development builds wrote an unversioned generation UUID marker; those markers are intentionally rejected rather than trusted as durable completion. Demonstration data is disposable: use reset-demo for seeded data or a new data directory for historical pre-schema runs. Do not delete or alter completion receipts to force a retry against an active data directory. Corrupt receipts or an unreadable ambiguous publication pointer require operator investigation/recovery; preserve the original directory before repair. Current startup reconciles valid committed job identity before accepting another tenant publication.

Experimental ranking configuration is shared by CLI and HTTP: JVM properties `-Dindexforge.ranking-mode=MODE -Dindexforge.ranking-profile=PROFILE` configure CLI; Boot reads `indexforge.ranking-mode` and `indexforge.ranking-profile` through its Environment (including `--indexforge.ranking-mode=MODE --indexforge.ranking-profile=PROFILE`). Default remains BASELINE/CURRENT pending fixed held-out selection. A selected experiment is not automatically a production configuration; final deployment must use the exact frozen profile/mode and report its scoringVersion. BM25-v1 presets reuse Lucene BM25. Shared fixture `HELDOUT-OPENED.txt` is written only for final evaluation; final evaluation needs a writable fixture directory for that immutable model seal. Changing output/data paths cannot open a different model after the seal is claimed.
