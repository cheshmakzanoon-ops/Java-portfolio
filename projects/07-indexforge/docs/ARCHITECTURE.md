# Architecture

IndexForge separates input acquisition, durable index lifetime, search policy, and adapters. Production modules do not depend on another portfolio project.

| Module | Responsibility |
| --- | --- |
| `ingest` | Configured source roots, strict UTF-8, bounded archives, content-addressed storage, immutable manifests, and resumable jobs. No Lucene or HTTP dependency. |
| `index` | Document mapping, identifier analysis, source observations, Lucene commits, generation publication, leases, and recovery. |
| `query` | Bounded grammar, access context, query planning, ranking, snippets, signed cursors, and application services. |
| `service` | HTTP authentication, admission limits, ingestion jobs, and response mapping. |
| `cli` | Administrative local commands over the same application services. |
| `evaluation` | Frozen-corpus metrics and finite ranking experiments. |

## Ingestion and publication

A document's stable identity is `(tenant, repository, canonical relative path)`. Immutable manifests use canonical entry order and content digests; retry keys bind a tenant, repository, and manifest. File and directory selections retain paths relative to the configured repository root. A partial upload merges with the existing snapshot; deletions must be explicit.

Content-addressed storage verifies SHA-256 on writes and reads. A complete tenant generation is capped at 100,000 documents, 128 MiB aggregate UTF-8 source, and 64 MiB metadata. Upload acquisition has a separate 256 MiB bound; passing that bound does not guarantee that the retained generation fits. Deletions and replacements are accounted for before loading replacement text.

Publication builds in sibling staging, commits Lucene, opens and warms a committed reader, checks counts and digests, renames staging to a generation, records `PREVIOUS`, then atomically replaces `CURRENT`. File and directory metadata are forced. Unsupported atomic replacement or directory force fails explicitly. This model assumes local storage with same-filesystem rename; it does not establish durability on arbitrary distributed storage or hardware.

Startup validates `CURRENT`, then `PREVIOUS`, quarantining incomplete staging. If committed data exists but neither pointer validates, startup preserves the files and refuses to guess. Committed metadata includes job and manifest identity. Before a later writer can publish, the current job is reconciled into a checksummed completion receipt. This prevents a retried job from overwriting a newer publication after an ambiguous earlier success.

## Read lifetime and concurrency

Each request acquires exactly one committed generation lease. Publication retires the old reader, but outstanding leases remain valid. Cleanup retains current, previous, and leased generations. A cursor into a retired reader returns `410`; cursors do not pin storage indefinitely. Content-store and job retention remain an operator responsibility.

Per-tenant writer locks serialize replacement builds. A short map lock protects publisher lookup. Existing leases continue during publication; acquiring a new lease can pause while the publisher validates the next generation. Search is not claimed to be lock-free.

## Search and authorization

The grammar supports uppercase `AND`, `OR`, unary `NOT`, parentheses, phrases, and allowlisted fields. Adjacent terms use `OR`; `AND` binds more tightly. Limits are 512 characters, 64 tokens, and depth 8. Wildcard, regex, and fuzzy syntax are rejected.

Identifier analysis preserves source offsets and emits whole identifiers and acronym, camel-case, and underscore components at synonym positions. Terms are lowercased with `Locale.ROOT`; tenant, repository, and path identities remain case-sensitive. A normalized term exceeding Lucene's UTF-8 limit is omitted whole rather than truncated into a false match.

Retrieval and deterministic reranking consider at most 500 authorized candidates. The configured profile and complete inference artifact identity participate in score versions, caches, and cursors. Optional source and prose features have explicit ablations; the [performance and relevance record](PERFORMANCE.md) describes the measured configurations.

Authorization is part of retrieval, counts, facets, snippets, suggestions, explanations, and the 128-entry response cache. Cache keys include principal, allowed repositories, permission version, generation, query, filters, mode, and page. A lease and permission-version recheck are required even on a cache hit. [Security](SECURITY.md) describes the remaining statistical channel through tenant-wide BM25 statistics.

## Conservative source observations

Structural ingestion parses syntax in a bounded child JVM without compilation, annotation processing, class loading, or symbol attribution. Unsupported input abstains as a whole unit. Identifier tokens are consumed by Unicode code point, while the 256-unit UTF-16 quota matches the facts and wire format.

The repository-local call graph reports only supported bindings. An enum with constant-specific class bodies is not treated as final: instance calls may dispatch to an override. Static and private bindings can remain exact where their other preconditions hold. Unknown receivers, inheritance, incomplete call inventories, and ambiguous overloads are not converted into zero usage or guessed targets.

The identifier-specific usage lane accepts at most 128 UTF-16 units, matching its graph work bound. Longer supplementary identifiers remain valid ordinary searches and bypass that optional lane; they do not become serving errors.
