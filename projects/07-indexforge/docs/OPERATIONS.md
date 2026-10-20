# Operations

Run with JDK 25 and the repository's checksum-pinned Maven wrapper. IndexForge needs local filesystem storage with atomic rename and directory-force support; it needs no Docker daemon or external search cluster. One process owns a data directory, including reader-only CLI commands. Stop HTTP before opening the same data offline.

## Build and local CLI

From the repository root:

```bash
source scripts/cloud-env.sh # managed workspace only
projects/07-indexforge/scripts/verify.sh
projects/07-indexforge/scripts/up.sh
INDEXFORGE_DOCUMENTS=2000 projects/07-indexforge/scripts/demo.sh
projects/07-indexforge/scripts/down.sh
```

The scripts resolve their repository root and reject a stale source fingerprint. `up` prepares state and starts no daemon; `down` preserves it. `reset-demo` removes only the named seeded demonstration directories. The demo asserts transactional replacement under concurrent search, interrupted-build recovery and repository isolation; its elapsed time is not a benchmark.

For an explicit source snapshot, use a different data directory. These commands run the packaged application directly:

```bash
mkdir -p projects/07-indexforge/.runtime/example-sources/src
cat > projects/07-indexforge/.runtime/example-sources/src/AtomicReader.java <<'JAVA'
/** Atomic publication preserves every reader lease. */
public final class AtomicReader {
  public String atomicPublication() { return "reader lease"; }
}
JAVA
java -Xmx2g -jar projects/07-indexforge/cli/target/indexforge-cli-1.0.0-SNAPSHOT.jar \
  ingest projects/07-indexforge/.runtime/cli-example demo public \
  projects/07-indexforge/.runtime/example-sources . example-v1 cli-example-v1
java -Xmx2g -jar projects/07-indexforge/cli/target/indexforge-cli-1.0.0-SNAPSHOT.jar \
  search projects/07-indexforge/.runtime/cli-example demo public 'atomic'
java -Xmx2g -jar projects/07-indexforge/cli/target/indexforge-cli-1.0.0-SNAPSHOT.jar \
  verify-generation projects/07-indexforge/.runtime/cli-example demo public
```

The ingest command prints the durable job id. `resume DATA TENANT REPOSITORY JOB_ID` uses its immutable manifest/CAS and does not need the original source tree. Reusing a key with changed content returns a conflict; use a new key for a new revision. Nested selections preserve configured-root-relative document keys. See the [CLI contract](../contracts/cli.md) for explicit deletion and artifact metadata syntax. Invalid commands and argument counts fail before opening data.

## Authenticated HTTP example

Prepare two documents beneath the server-selected tenant/repository root:

```bash
mkdir -p projects/07-indexforge/.runtime/http-sources/demo/public/example
cat > projects/07-indexforge/.runtime/http-sources/demo/public/example/AtomicReader.java <<'JAVA'
/** Atomic publication preserves reader leases. */
public final class AtomicReader { public String atomicRead() { return "reader"; } }
JAVA
cat > projects/07-indexforge/.runtime/http-sources/demo/public/example/AtomicWriter.java <<'JAVA'
/** Atomic publication commits one complete snapshot. */
public final class AtomicWriter { public String atomicWrite() { return "writer"; } }
JAVA
java -Xmx2g -jar projects/07-indexforge/service/target/indexforge-service-1.0.0-SNAPSHOT-exec.jar \
  --spring.profiles.active=dev --server.address=127.0.0.1 --server.port=18700 \
  --indexforge.issuer-port=18701 \
  --indexforge.data=projects/07-indexforge/.runtime/http-example \
  --indexforge.sources=projects/07-indexforge/.runtime/http-sources
```

After `GET /health` returns `{"status":"UP"}`, run this in another terminal:

```bash
python3 projects/07-indexforge/demo/http_workflow.py \
  --token-file projects/07-indexforge/.runtime/http-example/dev-token
```

The [executable example](../demo/http_workflow.py) reads the credential into an Authorization header without printing it. It submits a partial snapshot, polls the durable job, searches with a typed repository filter, follows signed one-hit pages within the same generation, requests suggestions, resumes the completed job, reindexes and reads tenant metrics. It exits nonzero on HTTP failure, an unexpected result or a bounded polling deadline. Repeating it with unchanged files/key is safe. It is deliberately restricted to loopback HTTP for disposable credentials.

The ingestion body is ordinary JSON:

```json
{"repository":"public","revision":"example-v1","idempotencyKey":"http-example-v1","path":"example","archive":false,"complete":false,"deletions":[]}
```

The server resolves `path` beneath `SOURCES/demo/public`; clients cannot select a different tenant or configured repository root. `202` means a registered job was accepted into the bounded queue. Poll `GET /v1/ingestions/ID` for `COMPLETE`, `FAILED` or `CANCELLED`. Queue rejection returns `429`; durable registration remains resumable. `DELETE /v1/ingestions/ID` is cooperative cancellation and cannot reverse an already committed generation. The [HTTP contract](../contracts/http.md) specifies schemas and error codes.

Development mode starts an ephemeral RSA issuer on loopback port 18701 and writes a one-hour token to `DATA/dev-token` with owner-only POSIX permissions. Alice maps to `demo/public` with administration; Bob maps to `other/public` without administration. Both JWT scopes and server permissions apply. Startup releases the issuer port if token persistence fails. Stop the foreground service normally; a restart creates a new disposable signing key.

## Protected configuration and admission

Use the `protected` profile with explicit `indexforge.issuer`, `indexforge.jwks` and `indexforge.users`. The users format is `subject:tenant:repo,repo:admin;...`. A verified JWT establishes identity; server mappings establish repository access and tenant administration. There is no permission-management HTTP route. Restart with reviewed mappings, or use the in-process authorization API in an embedded application. Development mode rejects a non-loopback bind.

Raw JSON bodies are limited to 65,536 bytes before conversion. Request admission is 32; Tomcat has 16 workers; the ingestion executor has 2 workers and 8 queued jobs. Source acquisition occurs before queue submission and is bounded independently. A tenant has one serialized writer. HTTP shutdown cancels queued/running tasks and waits up to 10 seconds for cooperative termination; the engine then excludes writers, freezes new leases and closes readers. An external live lease rejects engine close before any publisher closes and retains the data-directory lock.

`GET /health` is liveness, not a full integrity scan. CLI `verify-generation` opens and verifies the committed reader. `/v1/metrics` is tenant-administrator-only: `searches`, `jobFailures` and `queuedJobs` belong to that tenant. Queued work is a point-in-time gauge and can change while a response is assembled.

## Failure and recovery decisions

| Observed failure | Durable outcome and next action | Executable regression |
| --- | --- | --- |
| Invalid UTF-8, ZIP name/header mismatch, expansion quota or directory payload | Acquisition fails before job publication. Fix the input; ZIP data is never extracted or executed. | [ArchiveDirectoryTest](../ingest/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/ArchiveDirectoryTest.java), [IngestionSafetyTest](../ingest/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/IngestionSafetyTest.java) |
| Failure/cancellation during build, or permission revoked before publish | The owned staging tree is discarded; the prior committed generation remains searchable. Retained CAS/job registration supports authorized retry. | [GenerationLifecycleTest](../index/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/GenerationLifecycleTest.java), [EngineLifecycleTest](../query/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/EngineLifecycleTest.java) |
| First publication fails after rename but before pointer installation | The unreferenced renamed directory is removed. Restart can ingest again without an orphan generation blocking recovery. | `failedFirstPublicationDoesNotLeaveAnUnrecoverableOrphanGeneration` in GenerationLifecycleTest |
| Pointer replacement reports failure after installation | Reconcile the actual pointer and completion receipt before another writer. Retrying the old job cannot supersede a later completed revision. | [SearchSecurityTest](../query/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/SearchSecurityTest.java) |
| Publication pointer becomes unreadable at an ambiguous boundary | Further writes fail until restart recovery. Preserve the complete directory before diagnosis; do not invent a pointer. | SearchSecurityTest |
| `CURRENT` is corrupt, `PREVIOUS` validates | Startup quarantines the invalid pointer and repairs `CURRENT` before serving the fallback. If repair fails, startup fails and preserves `PREVIOUS`. | `corruptCurrentPointerFallsBackAndStagingIsQuarantined` and `failedRecoveryRepairPreservesTheValidatedFallbackAndFailsStartup` in GenerationLifecycleTest |
| Neither pointer validates while generation files exist | Startup preserves the files and refuses to guess. Restore a consistent backup or ingest source into a fresh data directory. | GenerationLifecycleTest and [SearchSecurityTest](../query/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/SearchSecurityTest.java) |
| Another process owns `OWNER.lock` | The second process fails before serving or changing generations. Stop the existing owner or select separate data. | SearchSecurityTest |
| A live external lease prevents close | Close rejects, all publishers remain usable and ownership remains locked. Release the lease, then close again. | EngineLifecycleTest and GenerationLifecycleTest |
| Cursor is modified, expired, permission-stale or retired | Invalid context returns400; a retired generation returns410. Begin a new search for a fresh cursor. | [SearchSecurityTest](../query/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/SearchSecurityTest.java) |

Back up with the service stopped or with a filesystem-consistent snapshot. Restore CAS, jobs, indexes and `cursor.key` together; never merge generation directories. Rotating the cursor key invalidates pagination. Preserve corrupt receipts, ambiguous pointers and quarantine before repair; do not edit checksums to bless damaged state. Historical unversioned completion markers are rejected.

CAS objects and job history need an operator retention policy. Generation cleanup retains current, previous and leased generations; it is not a general garbage collector. Source-root ancestors must remain under administrative control during acquisition. This local-storage protocol does not cover malicious concurrent ancestor replacement, disk loss or arbitrary distributed filesystems.

## Ranking and migration

The default remains `CLASSIC/BASELINE/CURRENT`; existing candidates failed the relevance guard. CLI configuration uses `-Dindexforge.ranking-mode=MODE`, `-Dindexforge.ranking-profile=PROFILE` and `-Dindexforge.ranking-strategy=STRATEGY`; Boot accepts the equivalent application properties. Numeric models are captured once at startup. Record the returned scoring version and exact artifact.

Structural strategies bind parser bundle, JDK, API catalog and generation metadata. After a parser/JDK identity change, explicitly prepare a new catalog using the current artifact and reindex the retained source. A mismatched structural generation is rejected; setting new metadata over old facts is insufficient. The ordinary baseline remains available over lexical indexes.

Evaluation is a separate offline activity. The legacy development command interprets only development/validation query payloads; final selection must match before the fixture-wide/output seals are claimed and held-out payloads are interpreted. Wave2/wave3 have no held-out command. Existing protocols, failed attempts and measurements remain immutable historical evidence. Performance output requires a new directory and admits 3–100,000 documents and 1–3,600 seconds per run; run a declared workload only when intentionally measuring it.
