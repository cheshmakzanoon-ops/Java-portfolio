# HTTP contract

The Boot service binds `127.0.0.1:18700` by default and accepts JSON. The [operations guide](../docs/OPERATIONS.md#authenticated-http-example) provides a complete executable workflow. Route definitions and application errors are implemented by [SearchController](../service/src/main/java/io/github/cheshmakzanoon/portfolio/stacks/SearchController.java) and [ErrorResponses](../service/src/main/java/io/github/cheshmakzanoon/portfolio/stacks/ErrorResponses.java).

## Authentication and limits

Every `/v1` route requires an RS256 bearer token with the configured issuer, audience `stacks`, expiry, string subject, typed tenant and `search` scope. The subject must also have a server-side principal mapping. Mutating routes require `ingest` scope; reindex and metrics additionally require server-side tenant administration. Tenant and repository permissions come from the authenticated server context.

Search and suggestions retain the verified token's tenant for the entire request. Permission changes can refresh repository grants within that tenant, but reassigning a subject to another tenant cannot redirect an in-flight request or cached response. A tenant mismatch returns `403 ACCESS_DENIED`; access to the reassigned tenant requires a token whose tenant matches the current server mapping.

| Boundary | Limit |
| --- | ---: |
| Raw request body, before JSON conversion | 65,536 bytes |
| Concurrent admitted requests / Tomcat workers | 32 / 16 |
| Background ingestion workers / queued tasks | 2 / 8 |
| Search text / lexical tokens / syntax depth | 512 UTF-16 units / 64 / 8 |
| Page size / candidate window / suggestions | 1–100 / 500 / 20 |
| Typed filters / value length | 8 / 256 UTF-16 units |

JSON request objects reject duplicate keys at any depth, trailing JSON values, unknown fields, and scalar coercion. Integer fields require integers; strings and Boolean flags require their declared JSON types. The documented nullable defaults below remain supported. Invalid JSON is rejected before source acquisition or durable registration.

Source acquisition happens before background queue admission and has separate file, archive and aggregate-byte quotas. See the [security model](../docs/SECURITY.md) for those boundaries and the administrator-controlled source-root assumption.

## Routes

| Method and path | Request and response |
| --- | --- |
| `GET /health` | Public liveness response: `{"status":"UP"}`. It does not scan index integrity. |
| `POST /v1/ingestions` | Body shown below. Returns `202` with `{"id":"…","state":"…"}` after immutable registration and queue admission. |
| `GET /v1/ingestions/{id}` | Returns the authorized job's `id` and `state`. Durable completion takes precedence over local queue state. |
| `POST /v1/ingestions/{id}/resume` | No body. Returns `202` through the same bounded queue; uses the immutable manifest and CAS without requiring the original source files. |
| `DELETE /v1/ingestions/{id}` | Requests authorized cooperative cancellation and returns `id` and `state`; a durable completion receipt takes precedence. It cannot reverse a committed generation. |
| `POST /v1/search` | Body and response described below. |
| `POST /v1/suggest` | `{"prefix":"atom"}` returns an array of authorized identifier completions. |
| `POST /v1/reindexes` | No body. Requires tenant administration and returns `202`. Rebuilds the tenant through the same publication path, preserving source revisions. |
| `GET /v1/metrics` | Requires tenant administration. Returns `searches`, `jobFailures` and `queuedJobs` for that tenant. Counters reset on process restart; the queue count is a point-in-time gauge. |

### Ingestion

```json
{"repository":"public","revision":"example-v1","idempotencyKey":"http-example-v1","path":"example","archive":false,"complete":false,"deletions":[]}
```

The repository identifier is 1–64 ASCII letters, digits, underscores or hyphens. Revision and idempotency key are nonblank and at most 128 UTF-16 units; path is nonblank and at most 4,096 units. Omitted/null `archive` and `complete` mean false; omitted/null `deletions` means an empty set. Directory ingestion supports partial snapshots and explicit deletions. Archive ingestion uses the archive's bounded snapshot representation.

The server resolves `path` beneath `stacks.sources/TENANT/REPOSITORY`, authorizes the binding before opening source and rejects binding symlinks. A client cannot select a different tenant or configured repository root. A reused key with different immutable input returns `409`.

Poll the job until `COMPLETE`, `FAILED` or `CANCELLED`. `QUEUED` and `RUNNING` describe this process's work. `CANCELLING` means an interrupt has been requested and the running attempt still owns its slot: repeated resume requests return that same state and cannot start a second attempt. A queued cancellation removes the runnable immediately and returns queue capacity. A running cancellation becomes terminal only when its worker exits; work that acknowledges successful publication remains `COMPLETE` even if cancellation raced it.

After restart or eviction from the 512-entry terminal-outcome cache, an incomplete durable registration can appear as `REGISTERED` and be resumed. Active attempts are never evicted. Queue rejection returns `429` while retaining the registered job and any prior terminal outcome; retrying a previously cancelled or failed registration may therefore still report that outcome until admission succeeds. Durable `COMPLETE` always takes precedence, including in cancellation responses. Resumes use the persisted manifest and CAS, without reopening the original source path. `jobFailures` counts failed executions once; cooperative cancellations do not count as failures.

Shutdown closes admission atomically with cancellation. Later submission returns `503 SERVICE_STOPPING`; it cannot enqueue work behind shutdown. A failed 10-second termination deadline remains an operational failure, and a later close may join the still-owned workers.

### Search and pagination

```json
{"text":"atomic","pageSize":1,"cursor":null,"filters":{"repository":"public"}}
```

Filter keys are `repository`, `language`, `kind`, `coordinates` and `version`. Query syntax supports terms, quoted phrases, parentheses, `AND`, `OR`, `NOT`, and the fields `content`, `symbol`, `path`, `repository`, `language`, `kind`, `coordinates` and `version`. Adjacent terms imply `OR`. Wildcard, fuzzy and range syntax are rejected.

The response contains `generationId`, `scoringVersion`, `totalHits`, `relation`, `results`, `facets` and nullable `cursor`. Each result contains `key`, `path`, `repository`, `sha256`, `score`, `snippet` and `explanation`. The explanation is an authorized matching-feature summary capped at 4,096 UTF-16 units; it omits raw Lucene collection statistics. Render snippets and explanations as plain text.

Pass the returned cursor with identical text, filters and page size. A cursor binds principal, permission version, request, scoring identity and generation; each returned continuation expires after five minutes. Cached content receives a fresh continuation, while an expired token remains invalid. A generation retired before continuation returns `410`; start a new search. The candidate window bounds pagination independently of `totalHits`.

## Errors

| Status | Meaning / application code |
| --- | --- |
| `400` | Invalid request, query or cursor: `INVALID_REQUEST` |
| `401` | Missing or invalid bearer token; handled by the resource-server authentication layer |
| `403` | Server-side authorization denied: `ACCESS_DENIED` |
| `409` | Key reused with different immutable input: `IDEMPOTENCY_CONFLICT` |
| `410` | Cursor generation retired: `GENERATION_RETIRED` |
| `413` | Raw body exceeds its quota: `BODY_TOO_LARGE` |
| `422` | Source acquisition or storage failure: `SOURCE_OR_STORAGE_FAILURE` |
| `429` | Request admission exhausted: `REQUEST_CAPACITY`; background queue exhausted: `QUEUE_FULL` |
| `503` | Background ingestion admission closed for shutdown: `SERVICE_STOPPING` |

Application error bodies use `{"error":"CODE"}` and omit source names and exception messages. Authentication errors follow the resource-server response. There is no browser UI.
