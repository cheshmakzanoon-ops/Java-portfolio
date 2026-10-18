from pathlib import Path
p=Path('projects/07-indexforge/query/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/SearchSecurityTest.java');s=p.read_text().replace('''        assertTrue(hit.explanation().endsWith("[explanation truncated]"));
        assertTrue(hit.explanation().contains("content:term"));''','''        assertTrue(hit.explanation().startsWith("Authorized match; score="));
        assertTrue(hit.explanation().contains("content:term"));
        assertFalse(hit.explanation().contains("avgdl"));
        assertFalse(hit.explanation().contains("docCount"));
        assertFalse(hit.explanation().contains("idf"));''');at=s.index('  @Test\n  void sourceBindings');s=s[:at]+'''  @Test
  void deniedRepositoryStatisticsNeverAppearInFreshOrCachedExplanations() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "t", Set.of("r", "denied-marker"), true);
    auth.update("reader", "t", Set.of("r"), false);
    try (var engine = new SearchEngine(root, auth)) {
      engine.ingest(admin, "visible", manifest(engine, "t", "r", 1, "visible"),
          GenerationBuilder.Fault.none(), () -> false);
      engine.ingest(admin, "hidden", manifest(engine, "t", "denied-marker", 17, "secretmarker"),
          GenerationBuilder.Fault.none(), () -> false);
      var request = new SearchEngine.Request("shared", 10, null, Map.of());
      var fresh = engine.search("reader", request);
      var warmed = engine.search("reader", request);
      assertEquals(fresh, warmed);
      assertEquals(1, fresh.totalHits());
      assertEquals(Map.of("r", 1), fresh.facets());
      assertEquals(List.of("r"), fresh.results().stream().map(SearchEngine.Hit::repository).toList());
      String explanation = fresh.results().getFirst().explanation();
      assertTrue(explanation.contains("content:shared"));
      for (String forbidden : List.of("denied-marker", "secretmarker", "docCount", "docFreq",
          "avgdl", "idf", "n,", "N,", "17", "18"))
        assertFalse(explanation.contains(forbidden), forbidden);
      assertFalse(fresh.results().getFirst().snippet().contains("secretmarker"));
      assertTrue(engine.suggest("reader", "secretmarker").isEmpty());
      auth.update("reader", "t", Set.of(), false);
      assertTrue(engine.search("reader", request).results().isEmpty());
    }
  }

  @Test
  void committedUnknownOutcomeCannotOverwriteNewerRevisionAfterResumeOrRestart() throws Exception {
    for (var boundary : List.of(GenerationBuilder.Boundary.AFTER_POINTER,
        GenerationBuilder.Boundary.RETIRE)) {
      for (boolean restart : List.of(false, true)) {
        Path data = root.resolve(boundary + "-" + restart);
        var auth = new AuthorizationFilter();
        var access = auth.update("writer", "t", Set.of("r"), true);
        String oldJob;
        try (var engine = new SearchEngine(data, auth)) {
          var initial = manifest(engine, "t", "r", 1, "initial");
          engine.ingest(access, "initial", initial, GenerationBuilder.Fault.none(), () -> false);
          String oldHash = engine.contentStore().put("stale content".getBytes(StandardCharsets.UTF_8));
          var old = new SnapshotManifest("t", "r", "old", false,
              List.of(new SnapshotManifest.Entry("same.java", oldHash, "java", "source", List.of(), "", "")), Set.of());
          oldJob = engine.register(access, "ambiguous", old).id();
          assertThrows(IOException.class, () -> engine.resume(access, oldJob, at -> {
            if (at == boundary) throw new IOException("publication outcome unknown");
          }, () -> false));
          if (!restart) assertNewerSurvives(engine, access, oldJob);
        }
        if (restart) {
          try (var engine = new SearchEngine(data, auth)) {
            assertTrue(engine.completed(oldJob));
            assertNewerSurvives(engine, access, oldJob);
          }
        }
        try (var reopened = new SearchEngine(data, auth)) {
          assertTrue(reopened.completed(oldJob));
          assertEquals(1, reopened.search("writer", new SearchEngine.Request("fresh", 10, null, Map.of())).totalHits());
          assertEquals(0, reopened.search("writer", new SearchEngine.Request("stale", 10, null, Map.of())).totalHits());
        }
      }
    }
  }

  private void assertNewerSurvives(SearchEngine engine, AuthorizationFilter.Access access,
      String oldJob) throws Exception {
    String hash = engine.contentStore().put("fresh content".getBytes(StandardCharsets.UTF_8));
    var newer = new SnapshotManifest("t", "r", "new", false,
        List.of(new SnapshotManifest.Entry("same.java", hash, "java", "source", List.of(), "", "")), Set.of());
    engine.ingest(access, "newer", newer, GenerationBuilder.Fault.none(), () -> false);
    String generation = engine.generations("t").currentId();
    assertTrue(engine.completed(oldJob));
    engine.resume(access, oldJob);
    assertEquals(generation, engine.generations("t").currentId());
    assertEquals(1, engine.search("writer", new SearchEngine.Request("fresh", 10, null, Map.of())).totalHits());
    assertEquals(0, engine.search("writer", new SearchEngine.Request("stale", 10, null, Map.of())).totalHits());
  }

  @Test
  void corruptCompletionReceiptFailsStatusResumeAndStartupExplicitly() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("a", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root, auth)) {
      var job = engine.ingest(access, "job", manifest(engine, "t", "r", 1, "one"),
          GenerationBuilder.Fault.none(), () -> false);
      Files.writeString(root.resolve("jobs").resolve(job.id() + ".done"), "truncated");
      assertThrows(UncheckedIOException.class, () -> engine.completed(job.id()));
      assertThrows(IOException.class, () -> engine.resume(access, job.id()));
    }
    assertThrows(IOException.class, () -> new SearchEngine(root, auth));
  }

  @Test
  void partialNestedFileAndDirectoryReplaceStableRootRelativeIdentity() throws Exception {
    Path sources = root.resolve("sources");
    Files.createDirectories(sources.resolve("src"));
    Files.writeString(sources.resolve("src/Foo.java"), "original");
    var auth = new AuthorizationFilter();
    var access = auth.update("a", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root.resolve("data"), auth)) {
      var reader = new SafeSourceReader(sources, SafeSourceReader.Limits.defaults(), engine.contentStore());
      engine.ingest(access, "full", reader.directory("t", "r", "one", Path.of("."), true,
          Set.of(), () -> false), GenerationBuilder.Fault.none(), () -> false);
      Files.writeString(sources.resolve("src/Foo.java"), "replacement");
      engine.ingest(access, "file", reader.directory("t", "r", "two", Path.of("src/Foo.java"), false,
          Set.of(), () -> false), GenerationBuilder.Fault.none(), () -> false);
      assertEquals(List.of("src/Foo.java"), engine.generations("t").sources().stream().map(d -> d.entry().path()).toList());
      assertEquals(0, engine.search("a", new SearchEngine.Request("original", 10, null, Map.of())).totalHits());
      Files.writeString(sources.resolve("src/Foo.java"), "finalcontent");
      engine.ingest(access, "directory", reader.directory("t", "r", "three", Path.of("src"), false,
          Set.of(), () -> false), GenerationBuilder.Fault.none(), () -> false);
      assertEquals(1, engine.generations("t").sources().size());
      assertEquals("src/Foo.java", engine.search("a", new SearchEngine.Request("finalcontent", 10, null, Map.of())).results().getFirst().path());
    }
  }

'''+s[at:];s=s.replace('List.of("denied-marker", "secretmarker", "docCount", "docFreq",\n          "avgdl", "idf", "n,", "N,", "17", "18")','List.of("denied-marker", "secretmarker", "docCount", "docFreq",\n          "avgdl", "idf", "n,", "N,")');p.write_text(s)
