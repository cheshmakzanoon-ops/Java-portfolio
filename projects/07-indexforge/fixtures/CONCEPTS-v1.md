# Concept candidate v1: pre-measurement feature record

This lexicon was handwritten by A07 using ordinary software terminology and observed v3 development/validation failures. No external public thesaurus, learned embedding, query ID, document name/path, or judgment-to-document lookup was used. The terms are related topic associations, not exact synonyms. In particular generation/snapshot/version and lease/reader/retire can refer to different software topics.

Scope: SOURCE_AWARE and existing feature variants, with NO_CONCEPTS removing this channel and NO_PROSE removing both English prose and its associations. Only plain multiword input can expand. Explicit Boolean operators, phrases, fields and single navigational identifiers preserve their existing semantics. At most6 distinct query-triggered groups with4 additional stemmed terms each; when at least2 groups are available, a new association-only match requires2 groups. Boost0.75. A768-term complete-plan budget rejects larger expanded plans explicitly.

The authored unrelated-topic controls include a version-only artifact class and a lease-only counter: neither may match an association-only snapshot+reader query, while a generation+lease implementation can. A completely unrelated astronomy query must remain empty. These controls do not imply universal semantic precision; development/validation failures and the NO_CONCEPTS ablation must be preserved, and held-out remains blind until final selection.

Catalog, in deterministic priority order:

- checkpoint resume progress completed resumable
- digest hash checksum sha256 cryptographic integrity corruption
- canonical sorted order deterministic stable ordering
- serialize encode bytes binary serialization writing
- persist durable save flush force fsync
- cancel interrupt abort interrupted cancellation
- archive zip expansion compressed compression
- quota limit bounded budget oversized unbounded
- path root directory checkout filesystem component
- symlink link escape traversal outside
- authorization permission access permitted forbidden denied revoke revocation
- principal subject identity user authenticated
- cursor pagination page continuation next
- count total hit aggregate facet
- boost weight ranking score relevance
- generation snapshot revision version index
- publish replace pointer atomic rename publication switch replacement
- recover restart fallback quarantine crash recovery
- lease reader reference retire lifetime close
- decode utf8 charset malformed encoding characters unicode
- snippet excerpt preview highlight
- explain explanation reason why feature
- artifact coordinates dependency package

Recorded before any concept-candidate measurements at 2026-10-18T02:02:42.470334+00:00.
