# Security model

IndexForge treats source content, archive metadata, query text, and HTTP bodies as untrusted input. The local data directory and configured source-root ancestors are controlled by the administrator. The administrative CLI trusts the local OS account; it is not an authentication boundary against that account.

## Input acquisition

Only explicitly configured roots are read. HTTP source selection is confined to `SOURCES/TENANT/REPOSITORY` after server-side permission checks. Parent traversal, binding symlinks, final-file symlinks, malformed UTF-8, and invalid path spellings are rejected.

Source text and ZIP members remain data: IndexForge does not execute builds, load archive classes, or crawl networks. ZIP data is never extracted. Bounded container validation checks central-directory and local-header bounds and entry-count agreement. Every member, including directory entries, passes the bounded streaming read and central/local CRC/size/name checks. Directory entries carrying payload are rejected. ZIP64 and spanned archives are unsupported; ordinary ZIP accepts at most 65,534 entries, even if a larger general snapshot quota is configured. Per-file, traversal, aggregate-byte, and expansion quotas apply independently.

Structural source analysis uses a bounded child JVM with no annotation processing, class loading, filesystem symbol resolution, or compilation. Unsupported input and uncertain bindings abstain explicitly. Unicode identifier admission and facts use the same UTF-16 quota.

## Authentication and authorization

The protected HTTP profile verifies RS256 signatures against JWKS, issuer, audience `indexforge`, expiry, subject, typed tenant, and search scope. Writes also require ingest scope. Original signed JSON claim types are checked after cryptographic decoding because libraries can coerce a numeric subject to a string. Server-side mappings determine tenant, repositories, and administrator status; a token cannot grant itself repository access.

Protected startup requires explicit issuer, JWKS, and users configuration. The disposable development issuer is loopback-only and refuses an external bind. Never use development credentials or real confidential data for a public deployment.

Authorization precedes candidate selection, hit counts, facets, snippets, suggestions, explanations, and cache reads. Admission rejects missing repository sets, invalid scope identifiers, more than 64 repositories, and principal identities over 1,024 UTF-16 units. Stored repository grants are copied before publication. A final permission-version check discards stale responses. Tenant index directories are physically separate. Cursors authenticate tenant, principal, permission version, query, generation, and expiration; transferred, modified, or expired cursors fail. A retired generation returns `410`. Tenant administration does not grant process-wide observability: search/failure counters and queue gauges are scoped to that tenant.

## Confidentiality limits

Public explanations contain matching features of an authorized document and its score, rather than raw Lucene explanations. Raw explanations include collection statistics from other repositories in the tenant.

BM25 still uses tenant-wide collection statistics internally. Scores and ordering can therefore depend on inaccessible repositories within the same tenant. ACL filtering prevents direct document disclosure, but does not establish statistical noninterference or constant-time confidentiality on shared hardware. Clients must render snippets and explanation strings as text.

## Resource and failure boundaries

Bodies are bounded before JSON conversion; request admission and ingestion queues are bounded. Query parsing bounds Boolean depth and clause count and rejects wildcard, fuzzy and range operators; it supplies no regular-expression execution path. Client errors use stable generic codes rather than inaccessible document names.

Publication receipts bind schema, job, manifest, and generation identity with checksums. Malformed, truncated, or oversized receipts fail explicitly. When pointer replacement succeeds but directory synchronization reports failure, the actual pointer is reconciled before another publication. An unreadable ambiguous pointer blocks writes until recovery.

Shutdown freezes new lease admission before closing any tenant reader. A rejected close preserves all publishers and the OS ownership lock; admitted writers finish or cancel before the engine releases its resources. Failed development-issuer startup releases its bound port.

This model excludes an attacker who can replace source-root ancestors concurrently, alter server data, steal credentials as the same OS user, or redefine running bytecode. Local filesystem recovery is not a guarantee for distributed filesystems, disk loss, or arbitrary hardware power failure. No production security certification is claimed.
