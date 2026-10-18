# CLI contract

All commands exit nonzero on failure. CLI access is explicitly local filesystem-trusted administrative access; remote users use authenticated HTTP. Run with JDK25, `-Xmx2g`, and the shaded `cli/target/indexforge-cli-1.0.0-SNAPSHOT.jar`.

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
benchmark DATA OUTPUT [DOCUMENTS=100000] [SECONDS_PER_RUN=30]
```

RELATIVE_PATH selects a directory or one UTF8 file within SOURCE_ROOT. Every resulting document path stays relative to SOURCE_ROOT: selecting `src/Foo.java` or directory `src` preserves `src/Foo.java`, so later partial selections replace the same stable key. Resume rebuilds persisted immutable input without reading the original checkout. Ingest imports a partial upload; absent files are preserved. Listed deletion paths are explicit. HTTP supports complete snapshots, where every missing earlier path requires a matching deletion. Evaluate requires the frozen fixture checksums and an independently approved review marker. `development` writes a selection bound to compiled ranking/retrieval bytecode and frozen fixture digests before `final` may execute held-out queries. The first final run creates HELDOUT-OPENED.txt; replay requires the same fixed model identity. No ranking output is a license to relabel data. No fixture is downloaded or executed.

Artifact metadata uses UTF8 `.artifact` files within the selected snapshot/archive: first line `coordinates=GROUP:ARTIFACT`, second `version=VERSION`, followed by associated documentation text. Coordinates/version are validated and indexed as exact fields; the SHA256 covers the complete original metadata/document bytes. Query example `coordinates:"io.demo:search" AND version:2.1` or typed HTTP filters. No artifact bytecode is loaded.
