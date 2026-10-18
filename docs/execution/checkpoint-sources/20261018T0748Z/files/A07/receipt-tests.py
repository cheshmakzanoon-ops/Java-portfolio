from pathlib import Path
p=Path('projects/07-indexforge/ingest/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/IngestionSafetyTest.java');s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test
  void completionReceiptsBindRegisteredManifestAndRejectCorruptOrOversizedMetadata() throws Exception {
    var jobs = new IngestionJob(dir.resolve("jobs"));
    var manifest = new SnapshotManifest("t", "r", "v1", false, List.of(), Set.of());
    var job = jobs.register("key", manifest);
    String generation = UUID.randomUUID().toString();
    assertFalse(jobs.completed(job.id()));
    assertThrows(IOException.class, () -> jobs.complete(job.id(), "0".repeat(64), generation));
    assertThrows(IOException.class, () -> jobs.complete(job.id(), job.manifestHash(), "invalid"));
    jobs.complete(job.id(), job.manifestHash(), generation);
    assertTrue(jobs.completed(job.id()));
    Path receipt = dir.resolve("jobs").resolve(job.id() + ".done");
    String original = Files.readString(receipt);
    jobs.complete(job.id(), job.manifestHash(), UUID.randomUUID().toString());
    assertEquals(original, Files.readString(receipt));
    for (int field = 0; field < 5; field++) {
      String[] parts = original.split("\\\\n");
      parts[field] = "invalid";
      Files.writeString(receipt, String.join("\\n", parts));
      assertThrows(IOException.class, () -> jobs.completed(job.id()), "corrupt field " + field);
    }
    Files.writeString(receipt, "truncated");
    assertThrows(IOException.class, () -> jobs.completed(job.id()));
    Files.writeString(receipt, "x".repeat(513));
    assertThrows(IOException.class, () -> jobs.completed(job.id()));
    Files.writeString(receipt, original);
    assertTrue(jobs.completed(job.id()));
  }

'''+s[at:];p.write_text(s)
p=Path('projects/07-indexforge/docs/OPERATIONS.md');s=p.read_text().replace('Bob has other/public/read','Bob has other/public/non-admin (the local dev token factory grants search/ingest scopes; Bob can ingest only his allowed repository)');p.write_text(s)
p=Path('projects/07-indexforge/contracts/cli.md');s=p.read_text().replace('basename','configured-source-root-relative path');p.write_text(s)
p=Path('projects/07-indexforge/docs/SECURITY.md');s=p.read_text();s+='''
User-facing explanations export only matching features of an authorized document, its numeric score and explicit reranking bonuses. Raw Lucene explanations are never returned: their n/N/avgdl/document-frequency statistics include the tenant reader's inaccessible repositories. Denied-repository tests exercise fresh and warmed responses, facets, snippets, suggestions and permission revocation. Lucene BM25 still uses tenant-reader collection statistics internally, so numeric scores and result order can depend on documents in other repositories in the same tenant. This is a statistical confidentiality limitation distinct from timing; ACL statistical noninterference is not claimed. Tenant indexes are physically separate.

Committed generations record the immutable job ID and manifest digest. Startup and every subsequent tenant writer reconcile the current committed identity into an atomic checksummed completion receipt before another generation can replace it. Receipts bind schema, job ID, manifest digest and generation UUID; malformed/truncated/oversized receipts fail explicitly. A publication whose atomic pointer rename succeeded before directory fsync failed is reconciled from the actual pointer; an unreadable ambiguous pointer blocks subsequent writes until restart recovery.
''';p.write_text(s)
p=Path('projects/07-indexforge/docs/ARCHITECTURE.md');s=p.read_text().replace('score explanations are Lucene explanations for the authorized weighted query','score explanations export only matching authorized-document field/term features, numeric score and reranking bonuses; raw Lucene collection statistics are withheld');s+='''
Directory and selected-file ingestion preserve paths relative to the configured repository source root. Selecting `src/Foo.java` after a full-root snapshot replaces `src/Foo.java`; selecting `src` does not rebase it to `Foo.java`. ZIP entries retain their declared normalized repository-relative names. Root binding is therefore part of ingestion identity, and HTTP selects within the authorized tenant/repository source root.

Optional handwritten concept associations apply only to plain multiword queries and are disabled for explicit Boolean, phrase, field and single-identifier queries. At most6 groups contribute4 additional terms each, with two independently triggered groups required when available. This catalog was authored using ordinary software terminology and observed development/validation failures, not an external thesaurus. Generation/snapshot/version and lease/reader/retire are associations, not synonyms. The frozen catalog/provenance is in fixtures/CONCEPTS-v1.md; NO_CONCEPTS is retained as an ablation. Held-out data has not influenced the catalog.
''';p.write_text(s)
p=Path('projects/07-indexforge/contracts/http.md');s=p.read_text().replace('stored explanation capped at4096 characters with a truncation marker','authorized matching-feature summary capped at4096 characters; raw Lucene collection statistics omitted');p.write_text(s)
