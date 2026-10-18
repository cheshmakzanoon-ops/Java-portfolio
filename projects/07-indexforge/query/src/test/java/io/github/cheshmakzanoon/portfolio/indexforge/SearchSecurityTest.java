package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SearchSecurityTest {
  @TempDir Path root;

  SnapshotManifest manifest(SearchEngine e, String tenant, String repo, int count, String word)
      throws IOException {
    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String content = "public void " + word + i + "() {} shared phrase engine";
      entries.add(
          new SnapshotManifest.Entry(
              "src/" + word + i + ".java",
              e.contentStore().put(content.getBytes(StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of(word + i),
              "g:a",
              "1"));
    }
    return new SnapshotManifest(tenant, repo, "v1", false, entries, Set.of());
  }

  @Test
  void codeHintsRequireTwoTopicsIgnoreDecoysAndPreserveExplicitSyntaxAndAuthorization()
      throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "t", Set.of("r", "hidden"), true);
    auth.update("reader", "t", Set.of("r"), false);
    String pattern =
        "StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);";
    var request = new SearchEngine.Request("unicode encoding", 10, null, Map.of());
    try (var engine =
        new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.CODE_MEDIUM)) {
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (var source :
          Map.of(
                  "Real.java",
                  "class Real { void read(){" + pattern + "} }",
                  "Quoted.java",
                  "class Quoted { String example=\"" + pattern + "\"; }",
                  "Comment.java",
                  "//" + pattern,
                  "Archive.java",
                  "class Archive { void read(){new ZipInputStream(input);} }")
              .entrySet())
        entries.add(
            new SnapshotManifest.Entry(
                source.getKey(),
                engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
                "java",
                "source",
                List.of(),
                "",
                ""));
      engine.ingest(
          admin,
          "hints",
          new SnapshotManifest("t", "r", "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      String hiddenHash =
          engine
              .contentStore()
              .put(
                  ("class Hidden { void read(){" + pattern + "} }")
                      .getBytes(StandardCharsets.UTF_8));
      engine.ingest(
          admin,
          "hidden",
          new SnapshotManifest(
              "t",
              "hidden",
              "v1",
              false,
              List.of(
                  new SnapshotManifest.Entry(
                      "Hidden.java", hiddenHash, "java", "source", List.of(), "", "")),
              Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      var ablated = engine.search("reader", request, QueryPlanner.Mode.NO_CODE_FEATURES);
      assertEquals(3, ablated.totalHits(), "existing topic expansion still matches API mentions");
      Map<String, Float> previous = new TreeMap<>();
      ablated.results().forEach(hit -> previous.put(hit.path(), hit.score()));
      var result = engine.search("reader", request);
      assertEquals(3, result.totalHits());
      for (var hit : result.results()) {
        if (hit.path().equals("Real.java")) assertTrue(hit.score() > previous.get(hit.path()));
        else {
          assertEquals(previous.get(hit.path()), hit.score());
          assertFalse(hit.explanation().contains("code_features:"));
        }
      }
      assertEquals("Real.java", result.results().getFirst().path());
      assertEquals(Map.of("r", 3), result.facets());
      assertTrue(result.results().getFirst().explanation().contains("code_features:"));
      assertFalse(result.toString().contains("Hidden.java"));
      assertEquals(result, engine.search("reader", request));
      for (String explicit :
          List.of(
              "\"unicode encoding\"",
              "unicode AND encoding",
              "content:unicode OR content:encoding",
              "unicode"))
        assertEquals(
            0,
            engine
                .search("reader", new SearchEngine.Request(explicit, 10, null, Map.of()))
                .totalHits(),
            explicit);
      assertEquals(
          0,
          engine
              .search("reader", new SearchEngine.Request("astronomy telescope", 10, null, Map.of()))
              .totalHits());
      auth.update("reader", "t", Set.of(), false);
      assertTrue(engine.search("reader", request).results().isEmpty());
    }
    assertTrue(CodeFeatureQuery.plan(null).isEmpty());
    assertTrue(CodeFeatureQuery.plan("unicode unicode").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> CodeFeatureQuery.plan("a".repeat(513)));
  }

  @Test
  void cursorScopeCanonicalizationSeparatesAmbiguousCodeUnitsAndPreservesRepositorySetOrder() {
    var clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    var cursor = new SearchCursor(new byte[32], clock);
    var first =
        new AuthorizationFilter.Access(
            String.valueOf((char) 1) + "#", "t", Set.of("a", "z"), 1, false);
    String token = cursor.encode(first, "q", UUID.randomUUID().toString(), 1);
    var reordered =
        new AuthorizationFilter.Access(
            first.principal(), "t", new LinkedHashSet<>(List.of("z", "a")), 1, false);
    assertEquals(1, cursor.decode(token, reordered, "q").offset());
    var collision =
        new AuthorizationFilter.Access(
            new String(new char[] {0x12, 3}), "t", Set.of("a", "z"), 1, false);
    assertThrows(IllegalArgumentException.class, () -> cursor.decode(token, collision, "q"));
    var surrogate =
        new AuthorizationFilter.Access(String.valueOf((char) 0xd800), "t", Set.of("a"), 1, false);
    String opaque = cursor.encode(surrogate, "q", UUID.randomUUID().toString(), 1);
    var replacement =
        new AuthorizationFilter.Access(String.valueOf((char) 0xfffd), "t", Set.of("a"), 1, false);
    assertThrows(IllegalArgumentException.class, () -> cursor.decode(opaque, replacement, "q"));
  }

  @Test
  void cursorRejectsChangedRankingProfileAcrossRestartButSameProfileContinues() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), true);
    var request = new SearchEngine.Request("shared", 1, null, Map.of());
    String cursor, first;
    try (var engine =
        new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_DEFAULT)) {
      engine.ingest(
          access,
          "cursor",
          manifest(engine, "t", "r", 2, "method"),
          GenerationBuilder.Fault.none(),
          () -> false);
      var page = engine.search("reader", request);
      cursor = page.cursor();
      first = page.results().getFirst().key();
      assertNotNull(cursor);
    }
    var next = new SearchEngine.Request("shared", 1, cursor, Map.of());
    try (var engine =
        new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_DEFAULT)) {
      var continuation = engine.search("reader", next);
      assertEquals(1, continuation.results().size());
      assertNotEquals(first, continuation.results().getFirst().key());
    }
    try (var engine =
        new SearchEngine(
            root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_NO_LENGTH)) {
      assertThrows(IllegalArgumentException.class, () -> engine.search("reader", next));
    }
  }

  @Test
  void cursorBindsActualScopeDespiteRestartVersionReuseAndSupportsNewlinePrincipals() {
    var clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    var cursor = new SearchCursor(new byte[32], clock);
    var access = new AuthorizationFilter.Access("line\nprincipal", "t", Set.of("r"), 1, false);
    String generation = UUID.randomUUID().toString();
    String token = cursor.encode(access, "query", generation, 1);
    assertEquals(new SearchCursor.Position(generation, 1), cursor.decode(token, access, "query"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            cursor.decode(
                token,
                new AuthorizationFilter.Access("line\nprincipal", "t", Set.of("other"), 1, false),
                "query"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            cursor.decode(
                token,
                new AuthorizationFilter.Access("line\nprincipal", "t", Set.of("r"), 1, true),
                "query"));
  }

  @Test
  void perLeaseSimilarityControlsLengthNormalizationAndAblationRestoresDefault() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), true);
    var request = new SearchEngine.Request("needle", 10, null, Map.of());
    Map<String, Float> defaults = new TreeMap<>(), baseline = new TreeMap<>();
    try (var engine =
        new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_DEFAULT)) {
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (var source :
          Map.of("Long.txt", "needle " + "padding ".repeat(90), "Short.txt", "needle").entrySet())
        entries.add(
            new SnapshotManifest.Entry(
                source.getKey(),
                engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
                "text",
                "source",
                List.of(),
                "",
                ""));
      engine.ingest(
          access,
          "bm25",
          new SnapshotManifest("t", "r", "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      try (var lease = engine.generations("t").acquire()) {
        assertSame(
            lease.searcher(),
            lease.searcher(),
            "all response operations share one configured searcher");
      }
      engine
          .search("reader", request)
          .results()
          .forEach(hit -> defaults.put(hit.path(), hit.score()));
      engine
          .search("reader", request, QueryPlanner.Mode.BASELINE)
          .results()
          .forEach(hit -> baseline.put(hit.path(), hit.score()));
      assertTrue(defaults.get("Short.txt") > defaults.get("Long.txt"));
    }
    try (var engine =
        new SearchEngine(
            root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_NO_LENGTH)) {
      var noLength = engine.search("reader", request);
      assertEquals(2, noLength.totalHits());
      assertEquals(
          noLength.results().getFirst().score(), noLength.results().getLast().score(), .00001);
      assertEquals(
          "Long.txt", noLength.results().getFirst().path(), "equal scores use stable key ordering");
      assertTrue(noLength.scoringVersion().endsWith("BM25_NO_LENGTH"));
      Map<String, Float> ablated = new TreeMap<>(), unchangedBaseline = new TreeMap<>();
      engine
          .search("reader", request, QueryPlanner.Mode.NO_SIMILARITY)
          .results()
          .forEach(hit -> ablated.put(hit.path(), hit.score()));
      engine
          .search("reader", request, QueryPlanner.Mode.BASELINE)
          .results()
          .forEach(hit -> unchangedBaseline.put(hit.path(), hit.score()));
      assertEquals(defaults, ablated);
      assertEquals(baseline, unchangedBaseline);
      assertEquals(
          noLength, engine.search("reader", request), "warmed configuration remains stable");
      assertEquals(
          0,
          engine
              .search("reader", new SearchEngine.Request("astronomy", 10, null, Map.of()))
              .totalHits());
    }
  }

  @Test
  void coverageRewardsDistinctAuthorizedTermsAndAblationRestoresOriginalScores() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "t", Set.of("r", "denied"), true);
    auth.update("reader", "t", Set.of("r"), false);
    var request = new SearchEngine.Request("atomic publication", 10, null, Map.of());
    Map<String, Float> original = new HashMap<>();
    try (var engine =
        new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.CURRENT)) {
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (var source :
          Map.of("Both.txt", "atomic publication", "Partial.txt", "atomic ".repeat(80)).entrySet())
        entries.add(
            new SnapshotManifest.Entry(
                source.getKey(),
                engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
                "text",
                "source",
                List.of(),
                "",
                ""));
      engine.ingest(
          admin,
          "visible",
          new SnapshotManifest("t", "r", "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      engine.ingest(
          admin,
          "hidden",
          manifest(engine, "t", "denied", 1, "secret"),
          GenerationBuilder.Fault.none(),
          () -> false);
      engine
          .search("reader", request)
          .results()
          .forEach(hit -> original.put(hit.path(), hit.score()));
    }
    try (var engine =
        new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.COORDINATED)) {
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
      for (String explicit :
          List.of("\"atomic publication\"", "atomic AND publication", "content:atomic", "atomic")) {
        var result =
            engine.search("reader", new SearchEngine.Request(explicit, 10, null, Map.of()));
        assertFalse(result.results().isEmpty());
        assertTrue(
            result.results().stream()
                .noneMatch(hit -> hit.explanation().contains("coverageBonus")));
      }
      assertEquals(
          0,
          engine
              .search("reader", new SearchEngine.Request("galactic telescope", 10, null, Map.of()))
              .totalHits());
      engine.search("reader", new SearchEngine.Request("the and", 10, null, Map.of()));
      assertEquals(response, engine.search("reader", request));
    }
    for (var profile : RankingProfile.values()) {
      try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.BASELINE, profile)) {
        var response = engine.search("reader", request);
        assertEquals(2, response.totalHits());
        assertTrue(
            response.results().stream()
                .noneMatch(hit -> hit.explanation().contains("coverageBonus")));
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

  @Test
  void conceptAssociationsRequireContextAndPreserveExplicitQuerySemantics() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("developer", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root, auth)) {
      Map<String, String> documents =
          Map.of(
              "Relevant.java",
              "class GenerationLease { int references; void retire() {} }",
              "UnrelatedArtifact.java",
              "class ArtifactVersion { int version=42; }",
              "UnrelatedLease.java",
              "class LeaseCounter { int references; void retire() {} }");
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (var source : documents.entrySet())
        entries.add(
            new SnapshotManifest.Entry(
                source.getKey(),
                engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
                "java",
                "source",
                List.of(),
                "",
                ""));
      engine.ingest(
          access,
          "concepts",
          new SnapshotManifest("t", "r", "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      var plain = new SearchEngine.Request("snapshot reader", 10, null, Map.of());
      assertEquals(0, engine.search("developer", plain, QueryPlanner.Mode.NO_CONCEPTS).totalHits());
      var expanded = engine.search("developer", plain, QueryPlanner.Mode.SOURCE_AWARE);
      assertEquals(1, expanded.totalHits());
      assertEquals("Relevant.java", expanded.results().getFirst().path());
      assertFalse(expanded.results().stream().anyMatch(hit -> hit.path().startsWith("Unrelated")));
      for (String explicit :
          List.of(
              "snapshot AND reader",
              "\"snapshot reader\"",
              "content:snapshot OR content:reader",
              "snapshot"))
        assertEquals(
            0,
            engine
                .search(
                    "developer",
                    new SearchEngine.Request(explicit, 10, null, Map.of()),
                    QueryPlanner.Mode.SOURCE_AWARE)
                .totalHits(),
            explicit);
      assertEquals(
          1,
          engine
              .search(
                  "developer",
                  new SearchEngine.Request("path:Relevant.java", 10, null, Map.of()),
                  QueryPlanner.Mode.SOURCE_AWARE)
              .totalHits());
      assertEquals(
          0,
          engine
              .search(
                  "developer",
                  new SearchEngine.Request("galactic telescope", 10, null, Map.of()),
                  QueryPlanner.Mode.SOURCE_AWARE)
              .totalHits());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              engine.search(
                  "developer",
                  new SearchEngine.Request("a0".repeat(255), 10, null, Map.of()),
                  QueryPlanner.Mode.SOURCE_AWARE));
      assertTrue(SearchConcepts.expansion(null).isEmpty());
      assertThrows(IllegalArgumentException.class, () -> SearchConcepts.expansion("x".repeat(513)));
    }
  }

  @Test
  void proseStemmingMatchesInflectedPhrasesWhileTheAblationAndBaselineDoNot() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("developer", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root, auth)) {
      String content = "class Lease { /** Readers safely retire generations. */ void retire() {} }";
      var entry =
          new SnapshotManifest.Entry(
              "Lease.java",
              engine.contentStore().put(content.getBytes(StandardCharsets.UTF_8)),
              "java",
              "source",
              List.of("Lease", "retire"),
              "",
              "");
      engine.ingest(
          access,
          "prose",
          new SnapshotManifest("t", "r", "v1", false, List.of(entry), Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      var request =
          new SearchEngine.Request("\"reader safely retired generation\"", 10, null, Map.of());
      assertEquals(0, engine.search("developer", request, QueryPlanner.Mode.BASELINE).totalHits());
      assertEquals(0, engine.search("developer", request, QueryPlanner.Mode.NO_PROSE).totalHits());
      var enhanced = engine.search("developer", request, QueryPlanner.Mode.SOURCE_AWARE);
      assertEquals(1, enhanced.totalHits());
      assertEquals("Lease.java", enhanced.results().getFirst().path());
      assertTrue(enhanced.results().getFirst().explanation().contains("prose:"));
      assertFalse(enhanced.results().getFirst().explanation().contains("declaration:"));
      engine.search(
          "developer",
          new SearchEngine.Request("the", 10, null, Map.of()),
          QueryPlanner.Mode.SOURCE_AWARE);
    }
  }

  @Test
  void declarationRankingDistinguishesRealMethodsFromCallsAndQuotedExamples() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("developer", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root, auth)) {
      String declaration = "class Owner { public void targetMethod() {} }";
      String mentions =
          "class Example { String note=\""
              + "targetMethod ".repeat(40)
              + "\"; void caller(){ owner.targetMethod(); } }";
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (var source : Map.of("Owner.java", declaration, "Example.java", mentions).entrySet())
        entries.add(
            new SnapshotManifest.Entry(
                source.getKey(),
                engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
                "java",
                "source",
                List.of("targetMethod"),
                "",
                ""));
      engine.ingest(
          access,
          "declarations",
          new SnapshotManifest("t", "r", "v1", false, entries, Set.of()),
          GenerationBuilder.Fault.none(),
          () -> false);
      var request = new SearchEngine.Request("targetMethod", 10, null, Map.of());
      var baseline = engine.search("developer", request, QueryPlanner.Mode.BASELINE);
      assertEquals("Example.java", baseline.results().getFirst().path());
      var candidate = engine.search("developer", request, QueryPlanner.Mode.SOURCE_AWARE);
      assertEquals("Owner.java", candidate.results().getFirst().path());
      assertTrue(candidate.results().getFirst().explanation().contains("declaration:targetmethod"));
      assertTrue(candidate.results().getFirst().explanation().contains("declarationBonus=4"));
      var ablation = engine.search("developer", request, QueryPlanner.Mode.NO_IDENTIFIERS);
      assertEquals("Example.java", ablation.results().getFirst().path());
    }
  }

  private SnapshotManifest explanationManifest(SearchEngine engine) throws IOException {
    String terms =
        java.util.stream.IntStream.range(0, 48)
            .mapToObj(i -> "term" + i)
            .collect(java.util.stream.Collectors.joining(" "));
    String hash = engine.contentStore().put(terms.getBytes(StandardCharsets.UTF_8));
    return new SnapshotManifest(
        "t",
        "r",
        "v1",
        false,
        List.of(
            new SnapshotManifest.Entry("One.java", hash, "java", "source", List.of(), "", ""),
            new SnapshotManifest.Entry("Two.java", hash, "java", "source", List.of(), "", "")),
        Set.of());
  }

  @Test
  void complexExplanationsAreBoundedAcrossCacheAndPermissionChanges() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root, auth)) {
      engine.ingest(
          access,
          "explain",
          explanationManifest(engine),
          GenerationBuilder.Fault.none(),
          () -> false);
      String query =
          java.util.stream.IntStream.range(0, 48)
              .mapToObj(i -> "term" + i)
              .collect(java.util.stream.Collectors.joining(" "));
      var request = new SearchEngine.Request(query, 100, null, Map.of());
      var response = engine.search("reader", request, QueryPlanner.Mode.SOURCE_AWARE);
      assertEquals(2, response.totalHits());
      for (var hit : response.results()) {
        assertTrue(hit.explanation().length() <= 4096);
        assertTrue(hit.explanation().startsWith("Authorized match; score="));
        assertTrue(hit.explanation().contains("content:term"));
        assertFalse(hit.explanation().contains("avgdl"));
        assertFalse(hit.explanation().contains("docCount"));
        assertFalse(hit.explanation().contains("idf"));
      }
      assertEquals(response, engine.search("reader", request, QueryPlanner.Mode.SOURCE_AWARE));
      assertEquals(1, engine.cacheHits());
      auth.update("reader", "t", Set.of(), true);
      var denied = engine.search("reader", request, QueryPlanner.Mode.SOURCE_AWARE);
      assertEquals(0, denied.totalHits());
      assertTrue(denied.results().isEmpty());
      assertTrue(denied.facets().isEmpty());
    }
  }

  @Test
  void deniedRepositoryStatisticsNeverAppearInFreshOrCachedExplanations() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "t", Set.of("r", "denied-marker"), true);
    auth.update("reader", "t", Set.of("r"), false);
    try (var engine = new SearchEngine(root, auth)) {
      engine.ingest(
          admin,
          "visible",
          manifest(engine, "t", "r", 1, "visible"),
          GenerationBuilder.Fault.none(),
          () -> false);
      engine.ingest(
          admin,
          "hidden",
          manifest(engine, "t", "denied-marker", 17, "secretmarker"),
          GenerationBuilder.Fault.none(),
          () -> false);
      var request = new SearchEngine.Request("shared", 10, null, Map.of());
      var fresh = engine.search("reader", request);
      var warmed = engine.search("reader", request);
      assertEquals(fresh, warmed);
      assertEquals(1, fresh.totalHits());
      assertEquals(Map.of("r", 1), fresh.facets());
      assertEquals(
          List.of("r"), fresh.results().stream().map(SearchEngine.Hit::repository).toList());
      String explanation = fresh.results().getFirst().explanation();
      assertTrue(explanation.contains("content:shared"));
      for (String forbidden :
          List.of(
              "denied-marker", "secretmarker", "docCount", "docFreq", "avgdl", "idf", "n,", "N,"))
        assertFalse(explanation.contains(forbidden), forbidden);
      assertFalse(fresh.results().getFirst().snippet().contains("secretmarker"));
      assertTrue(engine.suggest("reader", "secretmarker").isEmpty());
      auth.update("reader", "t", Set.of(), false);
      assertTrue(engine.search("reader", request).results().isEmpty());
    }
  }

  @Test
  void committedUnknownOutcomeCannotOverwriteNewerRevisionAfterResumeOrRestart() throws Exception {
    for (var boundary :
        List.of(GenerationBuilder.Boundary.AFTER_POINTER, GenerationBuilder.Boundary.RETIRE)) {
      for (boolean restart : List.of(false, true)) {
        Path data = root.resolve(boundary + "-" + restart);
        var auth = new AuthorizationFilter();
        var access = auth.update("writer", "t", Set.of("r"), true);
        String oldJob;
        try (var engine = new SearchEngine(data, auth)) {
          var initial = manifest(engine, "t", "r", 1, "initial");
          engine.ingest(access, "initial", initial, GenerationBuilder.Fault.none(), () -> false);
          String oldHash =
              engine.contentStore().put("stale content".getBytes(StandardCharsets.UTF_8));
          var old =
              new SnapshotManifest(
                  "t",
                  "r",
                  "old",
                  false,
                  List.of(
                      new SnapshotManifest.Entry(
                          "same.java", oldHash, "java", "source", List.of(), "", "")),
                  Set.of());
          oldJob = engine.register(access, "ambiguous", old).id();
          assertThrows(
              IOException.class,
              () ->
                  engine.resume(
                      access,
                      oldJob,
                      at -> {
                        if (at == boundary) throw new IOException("publication outcome unknown");
                      },
                      () -> false));
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
          assertEquals(
              1,
              reopened
                  .search("writer", new SearchEngine.Request("fresh", 10, null, Map.of()))
                  .totalHits());
          assertEquals(
              0,
              reopened
                  .search("writer", new SearchEngine.Request("stale", 10, null, Map.of()))
                  .totalHits());
        }
      }
    }
  }

  private void assertNewerSurvives(
      SearchEngine engine, AuthorizationFilter.Access access, String oldJob) throws Exception {
    String hash = engine.contentStore().put("fresh content".getBytes(StandardCharsets.UTF_8));
    var newer =
        new SnapshotManifest(
            "t",
            "r",
            "new",
            false,
            List.of(
                new SnapshotManifest.Entry("same.java", hash, "java", "source", List.of(), "", "")),
            Set.of());
    engine.ingest(access, "newer", newer, GenerationBuilder.Fault.none(), () -> false);
    String generation = engine.generations("t").currentId();
    assertTrue(engine.completed(oldJob));
    engine.resume(access, oldJob);
    assertEquals(generation, engine.generations("t").currentId());
    assertEquals(
        1,
        engine.search("writer", new SearchEngine.Request("fresh", 10, null, Map.of())).totalHits());
    assertEquals(
        0,
        engine.search("writer", new SearchEngine.Request("stale", 10, null, Map.of())).totalHits());
  }

  @Test
  void corruptCompletionReceiptFailsStatusResumeAndStartupExplicitly() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("a", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root, auth)) {
      var job =
          engine.ingest(
              access,
              "job",
              manifest(engine, "t", "r", 1, "one"),
              GenerationBuilder.Fault.none(),
              () -> false);
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
      var reader =
          new SafeSourceReader(sources, SafeSourceReader.Limits.defaults(), engine.contentStore());
      engine.ingest(
          access,
          "full",
          reader.directory("t", "r", "one", Path.of("."), true, Set.of(), () -> false),
          GenerationBuilder.Fault.none(),
          () -> false);
      Files.writeString(sources.resolve("src/Foo.java"), "replacement");
      engine.ingest(
          access,
          "file",
          reader.directory("t", "r", "two", Path.of("src/Foo.java"), false, Set.of(), () -> false),
          GenerationBuilder.Fault.none(),
          () -> false);
      assertEquals(
          List.of("src/Foo.java"),
          engine.generations("t").sources().stream().map(d -> d.entry().path()).toList());
      assertEquals(
          0,
          engine.search("a", new SearchEngine.Request("original", 10, null, Map.of())).totalHits());
      Files.writeString(sources.resolve("src/Foo.java"), "finalcontent");
      engine.ingest(
          access,
          "directory",
          reader.directory("t", "r", "three", Path.of("src"), false, Set.of(), () -> false),
          GenerationBuilder.Fault.none(),
          () -> false);
      assertEquals(1, engine.generations("t").sources().size());
      assertEquals(
          "src/Foo.java",
          engine
              .search("a", new SearchEngine.Request("finalcontent", 10, null, Map.of()))
              .results()
              .getFirst()
              .path());
    }
  }

  @Test
  void sourceBindingsCannotContainTraversalRepositoryIdentities() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthorizationFilter.Access("a", "tenant", Set.of("../other"), 1, false));
  }

  @Test
  void replacementBuildDoesNotBlockSearchesOnCommittedGeneration() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("a", "t", Set.of("r"), true);
    try (var engine = new SearchEngine(root, auth);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      engine.ingest(
          access,
          "old",
          manifest(engine, "t", "r", 1, "old"),
          GenerationBuilder.Fault.none(),
          () -> false);
      String old = engine.generations("t").currentId();
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var next = manifest(engine, "t", "r", 1, "new");
      var build =
          pool.submit(
              () -> {
                engine.ingest(
                    access,
                    "new",
                    next,
                    boundary -> {
                      if (boundary == GenerationBuilder.Boundary.BEFORE_COMMIT) {
                        entered.countDown();
                        try {
                          if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IOException("build test release timeout");
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          throw new IOException(e);
                        }
                      }
                    },
                    () -> false);
                return null;
              });
      try {
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
        var search =
            pool.submit(
                () -> engine.search("a", new SearchEngine.Request("shared", 10, null, Map.of())));
        assertEquals(old, search.get(5, java.util.concurrent.TimeUnit.SECONDS).generationId());
      } finally {
        release.countDown();
      }
      build.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertNotEquals(old, engine.generations("t").currentId());
      assertEquals(
          2,
          engine.search("a", new SearchEngine.Request("shared", 10, null, Map.of())).totalHits());
    }
  }

  @Test
  void interruptedJobResumesFromImmutableManifestAfterSourceDisappears() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("a", "t", Set.of("r"), true);
    String id;
    try (var engine = new SearchEngine(root, auth)) {
      engine.ingest(
          access,
          "old",
          manifest(engine, "t", "r", 1, "previous"),
          GenerationBuilder.Fault.none(),
          () -> false);
      Path sources = root.resolve("source-input");
      Files.createDirectory(sources);
      Files.writeString(
          sources.resolve("Resume.java"), "class Resume { void immutableResumeContent() {} }");
      var m =
          new SafeSourceReader(sources, SafeSourceReader.Limits.defaults(), engine.contentStore())
              .directory("t", "r", "resume", Path.of("."), false, Set.of(), () -> false);
      var job = engine.register(access, "interrupted", m);
      id = job.id();
      assertThrows(
          IOException.class,
          () ->
              engine.ingest(
                  access,
                  "interrupted",
                  m,
                  atBoundary -> {
                    if (atBoundary == GenerationBuilder.Boundary.BEFORE_COMMIT)
                      throw new IOException("crash before commit");
                  },
                  () -> false));
      assertFalse(engine.completed(id));
      Files.delete(sources.resolve("Resume.java"));
      Files.delete(sources);
    }
    try (var engine = new SearchEngine(root, auth)) {
      engine.resume(access, id);
      assertTrue(engine.completed(id));
      var result =
          engine.search(
              "a", new SearchEngine.Request("immutableResumeContent", 10, null, Map.of()));
      assertEquals(1, result.totalHits());
      assertEquals("Resume.java", result.results().getFirst().path());
      String generation = engine.generations("t").currentId();
      engine.resume(access, id);
      assertEquals(generation, engine.generations("t").currentId());
    }
  }

  @Test
  void dataDirectoryHasExactlyOneOwnerAndFailedStartupReleasesIt() throws Exception {
    var auth = new AuthorizationFilter();
    try (var engine = new SearchEngine(root, auth)) {
      assertThrows(IOException.class, () -> new SearchEngine(root, auth));
    }
    try (var reopened = new SearchEngine(root, auth)) {
      assertNotNull(reopened.contentStore());
    }
    Files.createDirectories(root.resolve("index/bad!"));
    assertThrows(IOException.class, () -> new SearchEngine(root, auth));
    Files.delete(root.resolve("index/bad!"));
    try (var recovered = new SearchEngine(root, auth)) {
      assertNotNull(recovered.contentStore());
    }
  }

  @Test
  void baselineAblationsTypedFieldsAndAdministrativeReindex() throws Exception {
    var auth = new AuthorizationFilter();
    var a = auth.update("admin", "t", Set.of("r"), true);
    try (var e = new SearchEngine(root, auth)) {
      assertThrows(IllegalArgumentException.class, () -> e.reindex(a));
      e.ingest(
          a,
          "one",
          manifest(e, "t", "r", 3, "fooBar"),
          GenerationBuilder.Fault.none(),
          () -> false);
      for (var mode : QueryPlanner.Mode.values()) {
        var result =
            e.search("admin", new SearchEngine.Request("fooBar0", 10, null, Map.of()), mode);
        assertFalse(result.results().isEmpty());
        assertTrue(Float.isFinite(result.results().getFirst().score()));
      }
      for (String query :
          List.of(
              "content:shared",
              "symbol:foobar0",
              "language:java",
              "coordinates:\"g:a\"",
              "version:1",
              "kind:source",
              "path:src/fooBar0.java"))
        assertTrue(
            e.search("admin", new SearchEngine.Request(query, 10, null, Map.of())).totalHits() > 0,
            query);
      assertEquals(
          0, e.search("admin", new SearchEngine.Request("☃", 10, null, Map.of())).totalHits());
      assertThrows(IllegalArgumentException.class, () -> e.suggest("admin", "bad prefix"));
      String old = e.generations("t").currentId();
      var before = e.generations("t").sources();
      e.reindex(a);
      assertEquals(before, e.generations("t").sources());
      assertNotEquals(old, e.generations("t").currentId());
      assertEquals(
          3, e.search("admin", new SearchEngine.Request("shared", 10, null, Map.of())).totalHits());
      e.search("admin", new SearchEngine.Request("shared", 10, null, Map.of()));
      assertTrue(e.cacheHits() > 0);
      var reader = auth.update("reader", "t", Set.of("r"), false);
      assertThrows(SecurityException.class, () -> e.reindex(reader));
      assertThrows(
          SecurityException.class,
          () ->
              e.job(
                  auth.update("foreign", "other", Set.of("r"), false),
                  e.register(a, "job", manifest(e, "t", "r", 1, "new")).id()));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthorizationFilter.Access("", "t", Set.of("r"), 1, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AuthorizationFilter.Access("a", "../", Set.of("r"), 1, false));
    assertThrows(SecurityException.class, () -> auth.current("unknown"));
    assertThrows(
        IllegalArgumentException.class, () -> new SearchCursor(new byte[31], Clock.systemUTC()));
  }

  @Test
  void isolationCoversResultsCountsFacetsSuggestionsExplanationsCursorsAndRepeatedQueries()
      throws Exception {
    AuthorizationFilter auth = new AuthorizationFilter();
    var owner = auth.update("owner", "tenant1", Set.of("public", "denied"), true);
    var alice = auth.update("alice", "tenant1", Set.of("public"), false);
    var bob = auth.update("bob", "tenant2", Set.of("public"), false);
    try (var e = new SearchEngine(root, auth)) {
      e.ingest(
          owner,
          "1",
          manifest(e, "tenant1", "public", 5, "visible"),
          GenerationBuilder.Fault.none(),
          () -> false);
      e.ingest(
          owner,
          "2",
          manifest(e, "tenant1", "denied", 4, "topSecret"),
          GenerationBuilder.Fault.none(),
          () -> false);
      e.ingest(
          bob,
          "3",
          manifest(e, "tenant2", "public", 3, "foreignSecret"),
          GenerationBuilder.Fault.none(),
          () -> false);
      SearchEngine.Request request = new SearchEngine.Request("shared", 2, null, Map.of());
      for (int run = 0; run < 3; run++) {
        var r = e.search("alice", request);
        assertEquals(5, r.totalHits());
        assertEquals(Map.of("public", 5), r.facets());
        assertFalse(r.toString().contains("topSecret"));
        assertFalse(r.toString().contains("foreignSecret"));
        assertNotNull(r.cursor());
        assertEquals(2, r.results().size());
        assertThrows(
            IllegalArgumentException.class,
            () -> e.search("bob", new SearchEngine.Request("shared", 2, r.cursor(), Map.of())));
        var next = e.search("alice", new SearchEngine.Request("shared", 2, r.cursor(), Map.of()));
        assertNotEquals(r.results().getFirst().key(), next.results().getFirst().key());
        assertThrows(
            IllegalArgumentException.class,
            () -> e.search("alice", new SearchEngine.Request("phrase", 2, r.cursor(), Map.of())));
        assertEquals(List.of(), e.suggest("alice", "topSecret"));
        assertEquals(5, e.suggest("alice", "visible").size());
      }
      assertTrue(e.cacheHits() > 0);
      assertEquals(
          0,
          e.search("alice", new SearchEngine.Request("repository:denied", 10, null, Map.of()))
              .totalHits());
      assertEquals(
          0,
          e.search(
                  "alice",
                  new SearchEngine.Request("shared", 10, null, Map.of("repository", "denied")))
              .totalHits());
      var cursor = e.search("alice", request).cursor();
      auth.update("alice", "tenant1", Set.of(), false);
      assertThrows(
          IllegalArgumentException.class,
          () -> e.search("alice", new SearchEngine.Request("shared", 2, cursor, Map.of())));
      assertEquals(0, e.search("alice", request).totalHits());
      assertThrows(
          SecurityException.class,
          () ->
              e.ingest(
                  alice,
                  "denied",
                  manifest(e, "tenant1", "denied", 1, "x"),
                  GenerationBuilder.Fault.none(),
                  () -> false));
      assertTrue(Files.isDirectory(root.resolve("index/tenant1")));
      assertTrue(Files.isDirectory(root.resolve("index/tenant2")));
    }
  }

  @Test
  void tenThousandIdenticalRecordsYieldOneLogicalCopyPerKey() throws Exception {
    var auth = new AuthorizationFilter();
    var a = auth.update("a", "t", Set.of("r"), true);
    try (var e = new SearchEngine(root, auth)) {
      var m = manifest(e, "t", "r", 10000, "method");
      var first = e.ingest(a, "same", m, GenerationBuilder.Fault.none(), () -> false);
      String generation = e.generations("t").currentId();
      assertEquals(first, e.ingest(a, "same", m, GenerationBuilder.Fault.none(), () -> false));
      assertEquals(generation, e.generations("t").currentId());
      assertEquals(
          10000, e.search("a", new SearchEngine.Request("shared", 10, null, Map.of())).totalHits());
      assertEquals(10000, e.generations("t").sources().size());
      assertTrue(e.completed(first.id()));
      assertEquals(first, e.job(a, first.id()));
      assertThrows(
          IngestionJob.Conflict.class,
          () ->
              e.ingest(
                  a,
                  "same",
                  new SnapshotManifest("t", "r", "changed", false, m.entries(), Set.of()),
                  GenerationBuilder.Fault.none(),
                  () -> false));
    }
  }

  @Test
  void parserBoundsBooleanPhraseAndTypedFieldsAreReal() throws Exception {
    var auth = new AuthorizationFilter();
    var a = auth.update("a", "t", Set.of("r"), true);
    try (var e = new SearchEngine(root, auth)) {
      e.ingest(
          a, "1", manifest(e, "t", "r", 3, "fooBar"), GenerationBuilder.Fault.none(), () -> false);
      assertEquals(
          3,
          e.search(
                  "a",
                  new SearchEngine.Request(
                      "\"shared phrase\" AND language:java NOT kind:artifact", 10, null, Map.of()))
              .totalHits());
      assertEquals(
          3,
          e.search(
                  "a",
                  new SearchEngine.Request("fooBar0 OR fooBar1 OR fooBar2", 10, null, Map.of()))
              .totalHits());
      assertEquals(
          0,
          e.search("a", new SearchEngine.Request("\"phrase shared\"", 10, null, Map.of()))
              .totalHits());
    }
    for (String q :
        List.of(
            "",
            "a*",
            "unknown:x",
            "\"unclosed",
            "a AND",
            "a OR",
            ":",
            "(a",
            "a)",
            "(".repeat(10) + "a" + ")".repeat(10),
            "a ".repeat(65),
            "x".repeat(513)))
      assertThrows(IllegalArgumentException.class, () -> QueryAst.parse(q), q);
    assertThrows(
        IllegalArgumentException.class, () -> new SearchEngine.Request("x", 101, null, Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new SearchEngine.Request("x", 0, null, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchEngine.Request("x", 1, null, Map.of("unsafe", "x")));
  }

  @Test
  void cursorsRequireSignaturePrincipalPermissionQueryAndTime() {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 4);
    Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    var a = new AuthorizationFilter.Access("a", "t", Set.of("r"), 1, false);
    var codec = new SearchCursor(key, clock);
    String gen = "11111111-1111-1111-1111-111111111111";
    String c = codec.encode(a, "digest", gen, 20);
    assertEquals(new SearchCursor.Position(gen, 20), codec.decode(c, a, "digest"));
    assertEquals(
        new SearchCursor.Position(gen, 0),
        codec.decode(codec.encode(a, "digest", gen, 0), a, "digest"));
    assertEquals(
        new SearchCursor.Position(gen, 500),
        codec.decode(codec.encode(a, "digest", gen, 500), a, "digest"));
    var exactExpiry = new SearchCursor(key, Clock.offset(clock, Duration.ofSeconds(300)));
    assertThrows(IllegalArgumentException.class, () -> exactExpiry.decode(c, a, "digest"));
    Map<Integer, String> boundaryTokens = new HashMap<>();
    Map<Integer, String> boundaryDigests = new HashMap<>();
    for (int length = 1; length < 1600; length++) {
      String digest = "d".repeat(length);
      String boundary = codec.encode(a, digest, gen, 0);
      if (boundary.length() == 2047 || boundary.length() == 2048) {
        boundaryTokens.put(boundary.length(), boundary);
        boundaryDigests.put(boundary.length(), digest);
      }
    }
    for (int length : List.of(2047, 2048)) {
      String boundary = boundaryTokens.get(length);
      assertNotNull(boundary, "the signed boundary token must actually be constructed");
      assertEquals(length, boundary.length());
      assertEquals(0, codec.decode(boundary, a, boundaryDigests.get(length)).offset());
    }
    // SHA256 signature has one legal optional base64 padding byte. It remains signed
    // correctly, but crosses the raw-token limit and must fail before any parsing.
    String oversized = boundaryTokens.get(2048) + "=";
    assertEquals(2049, oversized.length());
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decode(oversized, a, boundaryDigests.get(2048)));

    assertThrows(IllegalArgumentException.class, () -> codec.decode(c + "x", a, "digest"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode(c, a, "other"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            codec.decode(
                c, new AuthorizationFilter.Access("b", "t", Set.of("r"), 1, false), "digest"));
    var expired = new SearchCursor(key, Clock.offset(clock, Duration.ofMinutes(6)));
    assertThrows(IllegalArgumentException.class, () -> expired.decode(c, a, "digest"));
    assertThrows(IllegalArgumentException.class, () -> codec.decode("x".repeat(2049), a, "digest"));
  }

  @Test
  void retiredCursorCannotReadNewGeneration() throws Exception {
    var auth = new AuthorizationFilter();
    var a = auth.update("a", "t", Set.of("r"), true);
    try (var e = new SearchEngine(root, auth)) {
      e.ingest(
          a, "1", manifest(e, "t", "r", 3, "old"), GenerationBuilder.Fault.none(), () -> false);
      var cursor = e.search("a", new SearchEngine.Request("shared", 1, null, Map.of())).cursor();
      e.ingest(
          a, "2", manifest(e, "t", "r", 3, "new"), GenerationBuilder.Fault.none(), () -> false);
      assertThrows(
          GenerationPublisher.RetiredGeneration.class,
          () -> e.search("a", new SearchEngine.Request("shared", 1, cursor, Map.of())));
    }
  }
}
