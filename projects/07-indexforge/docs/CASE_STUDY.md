# Transactional search beyond the inverted index

Lucene solves indexing and BM25 retrieval. IndexForge addresses the application lifecycle around it: bounded input acquisition, stable identity, atomic publication, read lifetime, authorization, and honest evaluation.

## A response needs one snapshot

Returning new counts with old snippets is incorrect even when each Lucene reader is valid. IndexForge therefore leases one committed generation for the whole response. Replacement builds happen separately; publication validates and warms the committed reader before changing a checksummed pointer. Old leases remain valid until closed. This costs replacement disk space and build work, but makes visibility and recovery explicit.

A concurrency regression showed why the lock boundary matters. Holding the engine monitor during an entire replacement blocked requests that could have searched the old generation. Per-tenant writer locks now serialize builds while keeping the publisher-map critical section short. A latch-controlled regression requires an old-generation search to return before the replacement is released. Acquiring a new lease can still pause during final publication validation.

## Atomic publication does not imply idempotent jobs

A job could publish its pointer, fail before recording completion, and later retry after another job had published. Without durable identity reconciliation, the retry could overwrite the newer snapshot. Committed metadata now records immutable job and manifest identity. Before another writer publishes, the current identity is reconciled into a checksummed completion receipt. Regressions exercise live retry and restart after pointer and retirement faults, requiring the newer generation to survive.

Input identity has similar consequences. A full snapshot and a later single-file selection must identify `src/Foo.java` by the same key. Paths remain relative to the configured repository root, avoiding duplicate records caused by rebasing a selected file to `Foo.java`. Canonical manifest order also aligns hashing, deserialization, equality, and retry behavior.

## Authorization needs more than final-hit filtering

Filtering only returned documents leaves candidates, counts, facets, suggestions, caches, and explanations exposed. Each stage uses the authenticated access context, and a final permission-version check discards obsolete results. Signed cursors bind that context to the query and generation.

Review found two less obvious leaks. Raw Lucene explanations exposed collection statistics from denied repositories; public explanations now return authorized-document features and the final score, with the residual score/order statistical channel documented. Rendering filter maps as text also created cache/cursor identity collisions. Versioned, length-prefixed encoding with sorted tuples replaces display text; direct warmed-cache and crossed-cursor tests cover the collision.

## Source features must abstain when uncertain

Source parsing never executes submitted code or claims compiler attribution. Work, output, memory, and child lifetime are bounded. Two regressions strengthen that boundary: enum constant-specific overrides prevent exact virtual binding, and supplementary Unicode identifiers use code-point tokenization while retaining the wire format's UTF-16 quota. Unsupported cases remain unknown rather than becoming misleading usage counts.

## A failed relevance experiment is still useful evidence

The initial illustrative corpus was rejected before measurement. The frozen replacement uses substantive source, provenance, distractors, and reviewed labels. Both published final candidates improved nDCG but reduced identifier MRR relative to baseline:

| Configuration | Held-out nDCG@10 | Identifier MRR@10 |
| --- | ---: | ---: |
| Baseline | 0.6012721858 | 0.8833333333 |
| First selected candidate | 0.6267961004 | 0.8000000000 |
| Later fixed candidate C1 | 0.6594266978 | 0.8000000000 |

Neither candidate met the 0.75 / 0.90 targets. The fixed guard retains baseline; labels and defaults were not changed to turn a failed experiment into a pass. The repeated use of the authored held-out corpus is disclosed, and the preserved results do not establish general search quality. [Status](STATUS.md) and [performance](PERFORMANCE.md) identify the historical artifact boundaries and remaining work.
