# CLI contract

All commands exit nonzero on failure. Unknown commands, missing/extra positional arguments, invalid search syntax and out-of-range workload counts fail before opening data. CLI access is explicitly local filesystem-trusted administrative access; remote users use authenticated HTTP. Run with JDK 25, `-Xmx2g`, and the shaded `cli/target/indexforge-cli-1.0.0-SNAPSHOT.jar`.

```
up DATA
ingest DATA TENANT REPOSITORY SOURCE_ROOT RELATIVE_PATH REVISION IDEMPOTENCY_KEY [DELETED_PATH ...]
search DATA TENANT REPOSITORY 'QUERY'
resume DATA TENANT REPOSITORY JOB_ID
reindex DATA TENANT REPOSITORY
verify-generation DATA TENANT REPOSITORY
evaluate DATA FIXTURES OUTPUT development
evaluate DATA FIXTURES OUTPUT final
demo DATA [DOCUMENTS=20000]
benchmark DATA NEW_OUTPUT [DOCUMENTS=100000] [SECONDS_PER_RUN=30]
```

RELATIVE_PATH selects a directory or one UTF-8 file within SOURCE_ROOT. Every resulting document path stays relative to SOURCE_ROOT: selecting `src/Foo.java` or directory `src` preserves `src/Foo.java`, so later partial selections replace the same stable key. Resume rebuilds persisted immutable input without reading the original checkout. Ingest imports a partial upload; absent files are preserved. Listed deletion paths are explicit. HTTP supports complete snapshots, where every missing earlier path requires a matching deletion. Evaluate requires the frozen fixture checksums and an independently approved review marker. `development` writes a selection bound to compiled ranking/retrieval bytecode and frozen fixture digests before `final` may execute held-out queries. The first final run creates HELDOUT-OPENED.txt; replay requires the same fixed model identity. No ranking output is a license to relabel data. No fixture is downloaded or executed.

Artifact metadata uses UTF-8 `.artifact` files within the selected snapshot/archive: first line `coordinates=GROUP:ARTIFACT`, second `version=VERSION`, followed by associated documentation text. Coordinates/version are validated and indexed as exact fields; the SHA-256 covers the complete original metadata/document bytes. Query example `coordinates:"io.demo:search" AND version:2.1` or typed HTTP filters. No artifact bytecode is loaded.

The finite wave2 workflow exposes these phases:

```
wave2-audit DATA FIXTURES OUTPUT
wave2-fit DATA FIXTURES OUTPUT
wave2-validate DATA FIXTURES OUTPUT
```

Use one fresh DATA/OUTPUT pair, in that order, with the same certified copied JAR. Audit exports all 40 DEV authorized candidate vectors without labels; fit attaches only DEV labels and runs the frozen grouped-fold L2 grid; validate freezes the artifact/model boundary before reading 20 VAL queries and checks C0's 540-row replay. Each phase directory uses CREATE_NEW and cannot be overwritten by a rerun. Failed attempts remain; diagnose before registering a new attempt. There is no wave2 final command. These phases never open held-out query content or first-final seals.

Runtime configuration is `indexforge.ranking-mode` (BASELINE default), `indexforge.ranking-profile` (CURRENT default), `indexforge.ranking-strategy` (CLASSIC default) and, only for PAIRWISE, `indexforge.ranking-model` (bounded checksummed numeric file). CLI uses JVM properties and Boot uses the equivalent application properties. A model is captured once at startup; replacement requires restart and changes model-bound nonbaseline cursor/cache identity. No automatic deployment follows validation.

Wave3 source-study preparation uses `prepare-api-catalog workerDirectory newCatalogFile`, which reads the installed JDK source archive and writes a new checksummed catalog/license payload. Explicit source strategies require `-Dindexforge.ranking-strategy=SOURCE_MEMBERS|SOURCE_RELATIONS|SOURCE_USAGE`, `-Dindexforge.ranking-model=/absolute/model.bin`, `-Dindexforge.api-catalog=/absolute/catalog.bin`, profile CODE_HIGH and an explicit mode. Legacy indexes require `reindex data tenant repository`; the worker/parser facts are rebuilt under a new atomic generation. Configuring new metadata over an old generation is rejected. Production defaults remain CLASSIC/BASELINE until a reviewed adoption guard changes them.

The finite wave3 study commands are `wave3-audit data fixtures output catalogFile`, then `wave3-fit` and `wave3-validate` with those same paths. They require the complete reviewed `ranking-wave3/FREEZE.sha256`, source-certified supported artifact and unchanged catalog/fixture identities. Each phase writes a new immutable directory and refuses overwrite/changed identity. No wave3 held-out or final command exists. These operators have synthetic correctness coverage; current relevance results require a separately declared study. Historical protocols and failed attempts remain preserved.

The shared content/identifier analyzer uses Lucene’s fixed 32,766-byte maximum per emitted term, measured after lowercase normalization in UTF-8. A larger whole token/component is omitted, never truncated into a misleading searchable prefix. Independently bounded components of the same identifier remain searchable, and original source offsets and skipped-word position gaps are preserved. Original source/CAS bytes are retained unchanged; a structurally unsupported or oversized unit does not thereby bypass ordinary lexical indexing. The same analyzer policy serves index and query analysis.

Benchmark output must be a new directory. Document counts are 3–100,000 and seconds per run are 1–3,600; validation precedes generation or output creation. The default remains the declared three 30-second runs.
