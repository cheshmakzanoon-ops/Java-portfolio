from pathlib import Path
p=Path('projects/07-indexforge/query/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/SearchSecurityTest.java');s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test
  void coverageRewardsDistinctAuthorizedTermsAndAblationRestoresOriginalScores() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "t", Set.of("r", "denied"), true);
    auth.update("reader", "t", Set.of("r"), false);
    var request = new SearchEngine.Request("atomic publication", 10, null, Map.of());
    Map<String, Float> original = new HashMap<>();
    try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.CURRENT)) {
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (var source : Map.of("Both.txt", "atomic publication", "Partial.txt", "atomic ".repeat(80)).entrySet())
        entries.add(new SnapshotManifest.Entry(source.getKey(),
            engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
            "text", "source", List.of(), "", ""));
      engine.ingest(admin, "visible", new SnapshotManifest("t", "r", "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(), () -> false);
      engine.ingest(admin, "hidden", manifest(engine, "t", "denied", 1, "secret"),
          GenerationBuilder.Fault.none(), () -> false);
      engine.search("reader", request).results().forEach(hit -> original.put(hit.path(), hit.score()));
    }
    try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.COORDINATED)) {
      var response = engine.search("reader", request);
      assertEquals(2, response.totalHits());
      for (var hit : response.results()) {
        float expectedBonus = hit.path().equals("Both.txt") ? 8 : 2;
        assertEquals(original.get(hit.path()) + expectedBonus, hit.score(), .0001);
        assertTrue(hit.explanation().contains("coverageBonus=" + expectedBonus));
      }
      assertEquals("Both.txt", response.results().getFirst().path());
      for (var hit : engine.search("reader", request, QueryPlanner.Mode.NO_COVERAGE).results()) {
        assertEquals(original.get(hit.path()), hit.score(), .0001);
        assertFalse(hit.explanation().contains("coverageBonus"));
      }
      for (String explicit : List.of("\\"atomic publication\\"", "atomic AND publication", "content:atomic", "atomic")) {
        var result = engine.search("reader", new SearchEngine.Request(explicit, 10, null, Map.of()));
        assertFalse(result.results().isEmpty());
        assertTrue(result.results().stream().noneMatch(hit -> hit.explanation().contains("coverageBonus")));
      }
      assertEquals(0, engine.search("reader", new SearchEngine.Request("galactic telescope", 10, null, Map.of())).totalHits());
      engine.search("reader", new SearchEngine.Request("the and", 10, null, Map.of()));
      assertEquals(response, engine.search("reader", request));
    }
    for (var profile : RankingProfile.values()) {
      try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.BASELINE, profile)) {
        var response = engine.search("reader", request);
        assertEquals(2, response.totalHits());
        assertTrue(response.results().stream().noneMatch(hit -> hit.explanation().contains("coverageBonus")));
      }
    }
    String previous = System.getProperty("indexforge.ranking-profile");
    try {
      System.setProperty("indexforge.ranking-profile", "unregistered");
      assertThrows(IllegalArgumentException.class, RankingProfile::current);
    } finally {
      if (previous == null) System.clearProperty("indexforge.ranking-profile");
      else System.setProperty("indexforge.ranking-profile", previous);
    }
  }

'''+s[at:];p.write_text(s)
