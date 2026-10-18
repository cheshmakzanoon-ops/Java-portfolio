# Generic API-topic hints v1: pre-measurement declaration

Frozen before scores. Previous BM25-v1 fixed rule selected HIGH_SATURATION/NO_PATH (.5532469485nDCG/.783333identifierMRR); FULL_LENGTH/SOURCE_AWARE had the separate .5526776463/.85 tradeoff. Both and all failures remain published. Mandatory .75/.90 targets remain unmet. No held-out ranking, labels change, project/path/document/customclass lookup is allowed.

This channel indexes explicitly heuristic TOPIC ASSOCIATIONS of generic JDK/Lucene token patterns, not verified working behavior, type resolution, compilation, or a security/durability guarantee. Shared bounded lexer ignores comments/string/char/textblock bodies, examines <=1MiB/100000tokens, and abstains for a locally declared type shadowing a recognized API name. Imports/shadowing outside the file, unrelated receiver names, incomplete source and delegation remain limitations. Inputs are never compiled/executed. Field is added only for Java source; existing content fields and plain BM25 baseline are unchanged.

Fixed ten pattern families:

- StandardCharsets.UTF_8 + newDecoder( + CodingErrorAction.REPORT: strict UTF8/malformed decoding topic.
- MessageDigest.getInstance(: digest/checksum/hash topic; no specific algorithm/strength is asserted.
- Mac.getInstance( + SecretKeySpec(: authentication/signature topic; no valid token protocol is asserted.
- Files.move( + StandardCopyOption.ATOMIC_MOVE: atomic filesystem rename/publication topic.
- FileChannel.open( + force(: durable/flush/storage topic; cross-receiver lexical association is not proven fsync correctness.
- ZipInputStream( or ZipFile(: archive/zip/entry/expansion topic; no safe extraction guarantee.
- DirectoryReader.open( or DirectoryReader + IndexSearcher(: index/reader/snapshot/search topic, deliberately NO durable-commit claim because NRT readers also exist.
- DirectoryReader + AutoCloseable + AtomicBoolean + compareAndSet(: reader-lifetime/lease topic; no proven reference-count/close correctness.
- BooleanClause.Occur.FILTER + TermQuery(: query-filter/restriction topic, deliberately NO authorization/tenant claim from a generic filter alone.
- Character.isWhitespace( + substring( + indexOf(: lexical scanning/parsing/text topic, deliberately NO proven Boolean grammar claim.

Exact topics/patterns live in CodeFeatures.java. Unknown direct sources/fragments are not called working examples. Comment/string/textblock and API-shadow decoys, missing-conjunct controls and near-topic archive controls must be negative. Proper positive fragments verify only token classification. Query channel activates only on plain multiword text; at least TWO distinct normalized terms from the fixed topic vocabulary must match a document's hint field. Explicit Boolean/phrase/field/single-identifier queries preserve semantics. No extra query expansion or hidden/global statistics are used. NO_CODE_FEATURES disables the channel; BASELINE always disables it. All candidates are filtered by tenant/repository before retrieval and version rechecked, with denied/warmed tests.

Prespecified four-profile order: CODE_NONE(weight0 control),CODE_LOW(1),CODE_MEDIUM(4),CODE_HIGH(12). Each fixes prior HIGH_SATURATION fieldweights content1/identifier2/symbol6/path1/declaration32/prose2/concept2/coverage0,k1=2,b=.75. All existing feature ablations plus NO_CODE_FEATURES remain published. Model identity includes CodeFeatures/CodeFeatureQuery, shared scanner, indexing mapper/builder and the full shaded CLI artifact when packaged (including dependencies); profile is also bound. New fields require a fresh generation; evaluator's model-specific ingestion key forces it.

Selection criterion unchanged: validationnDCG andidentifierMRR each>=baseline, maximize min(nDCG/.75,identifierMRR/.90), baseline wins equalobjective; fixed enum/profileorder breaks remainingties. Development is diagnostic and validation selects only among this frozen finitegrid; no post-result pattern changes in this round. Fixed final profile/mode/model must precede held-out opening; baseline retained unless candidate improves held-outnDCG withoutidentifierMRR regression. Report failed hard targets honestly.

Cursor correctness in the same certification binds scoringVersion/profile and canonical principal/sortedrepositoryscope/admin-role hash, preserving explicittenant/version/query/generation/time. Same-profile restart continues; changedprofile or same-version changedscope rejects. UTF16code-unit canonicalization avoids newline/delimiter/replacement aliases. These affect cursorcontext, not ranking scores; no data disclosure from the prior offset issue is claimed.

Recorded before measurements at 2026-10-18T03:35:49.777006+00:00.
