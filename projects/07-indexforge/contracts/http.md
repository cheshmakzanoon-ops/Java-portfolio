# HTTP contract

The Boot service binds `127.0.0.1:18700` by default. Request body limit65536 raw bytes;32 concurrent admitted requests;16 Tomcat workers; ingestion2 workers and8 queued tasks. Bounded search text512 Java characters/64 lexical tokens/depth8; pageSize1–100; candidate window500; suggestions20. All v1 routes require RS256 bearer JWT, issuer/audience/expiry/subject/typed tenant/search scope, and a server-side principal mapping. Writes additionally require ingest scope; reindex/metrics require server-side administration.

- `GET /health` → `{status:UP}`.
- `POST /v1/ingestions` body `{repository, revision, idempotencyKey, path, archive, complete, deletions}`. Tenant comes from authenticated server context. Returns202 `{id,state}` after immutable registration and bounded queue admission. Paths are resolved only beneath `indexforge.sources/TENANT/REPOSITORY`, with tenant and repository authorized before any source open; binding symlinks are rejected.
- `GET /v1/ingestions/{id}` → authorized durable registration/completion and local queue state.
- `POST /v1/ingestions/{id}/resume` →202 through the same bounded queue, using persisted immutable manifest/CAS without requiring original source files.
- `DELETE /v1/ingestions/{id}` → authorized cooperative cancellation.
- `POST /v1/search` body `{text,pageSize,cursor,filters}`. Typed filter keys repository/language/kind/coordinates/version. Response `{generationId,scoringVersion,totalHits,relation,results,facets,cursor}`; each hit carries key/path/repository/digest/score/plain snippet/explanation (authorized matching-feature summary capped at4096 characters; raw Lucene collection statistics omitted).
- `POST /v1/suggest` body `{prefix}` → authorized identifier completions.
- `POST /v1/reindexes` → requires administrator; returns202 and rebuilds tenant index through the same bounded queue and committed-generation path, preserving each source revision.
- `GET /v1/metrics` → administrator-only operation counts and queued work.

Errors:400 invalid request/query/cursor;401 token invalid;403 authorization denied;409 reused key with different immutable manifest;410 retired generation;413 raw body too large;422 source/storage failure;429 request or queue capacity. Error envelopes do not contain source names or exception messages. Snippets/explanations are JSON strings, intended to be rendered as text; there is no HTML UI.
