package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceServingTest {
  @TempDir Path root;

  private SourceApiCatalog catalog(String term) throws Exception {
    return new SourceApiCatalog(
        "a".repeat(64),
        SourceParserClient.bundleIdentity(),
        System.getProperty("java.runtime.version"),
        "synthetic catalog for tests only",
        Set.of("java.util.Arrays"),
        List.of(
            new SourceApiCatalog.Entry(
                "java.util.Arrays",
                "hashCode",
                List.of("byte[]"),
                "int",
                Set.of("hash", term),
                false)));
  }

  private static LinearRankingModel model(int dimensions, int weightIndex) {
    double[] scale = new double[dimensions], weight = new double[dimensions];
    Arrays.fill(scale, 1);
    weight[weightIndex] = 10;
    return new LinearRankingModel(
        new double[dimensions], scale, weight, "a".repeat(64), "b".repeat(64), 1);
  }

  private static SnapshotManifest manifest(
      SearchEngine engine, String repo, Map<String, String> sources) throws Exception {
    var entries = new ArrayList<SnapshotManifest.Entry>();
    for (var source : new TreeMap<>(sources).entrySet())
      entries.add(
          new SnapshotManifest.Entry(
              source.getKey(),
              engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
              source.getKey().endsWith(".java") ? "java" : "text",
              "source",
              List.copyOf(DeclarationScanner.names(source.getValue())),
              "",
              ""));
    return new SnapshotManifest("tenant", repo, "test", false, entries, Set.of());
  }

  private SearchEngine engine(
      AuthorizationFilter auth,
      RankingStrategy strategy,
      LinearRankingModel model,
      SourceApiCatalog catalog)
      throws Exception {
    return new SearchEngine(
        root,
        auth,
        QueryPlanner.Mode.SOURCE_AWARE,
        RankingProfile.CODE_HIGH,
        new RankingConfiguration(strategy, model, catalog));
  }

  private static List<String> paths(SearchEngine.Response response) {
    return response.results().stream().map(SearchEngine.Hit::path).toList();
  }

  @Test
  void legacyIndexRequiresExplicitMigrationAndServingSharesActualAuditVectorsAndMasks()
      throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "tenant", Set.of("repo"), true);
    var query = new SearchEngine.Request("byte hash", 20, null, Map.of());
    try (var legacy = engine(auth, RankingStrategy.SYNTAX_GATED, null, null)) {
      legacy.ingest(
          admin,
          "seed",
          manifest(
              legacy,
              "repo",
              Map.of(
                  "Good.java",
                      "final class Good { int value(byte[] data){return java.util.Arrays.hashCode(data);} }",
                  "State.java",
                      "final class State { byte[] state; int value(byte[] unused){return java.util.Arrays.hashCode(state);} }")),
          GenerationBuilder.Fault.none(),
          () -> false);
    }
    var catalog = catalog("comput");
    var model = model(16, 12);
    try (var source = engine(auth, RankingStrategy.SOURCE_RELATIONS, model, catalog)) {
      assertTrue(
          assertThrows(IOException.class, () -> source.search("admin", query))
              .getMessage()
              .contains("reindex"));
      source.reindex(admin);
      var audit = source.auditCandidates("admin", query, QueryPlanner.Mode.SOURCE_AWARE);
      assertEquals("source-relations-v1", audit.featureSchema());
      var vectors = new HashMap<String, double[]>();
      audit.candidates().forEach(c -> vectors.put(c.path(), c.features()));
      assertEquals(1, vectors.get("Good.java")[12]);
      assertEquals(0, vectors.get("State.java")[12]);
      var response = source.search("admin", query);
      assertEquals("Good.java", response.results().getFirst().path());
      for (var hit : response.results())
        assertEquals((float) model.score(vectors.get(hit.path())), hit.score());
      for (var mode :
          List.of(
              QueryPlanner.Mode.NO_MEMBER_SCOPE,
              QueryPlanner.Mode.NO_CODE_FEATURES,
              QueryPlanner.Mode.NO_COVERAGE,
              QueryPlanner.Mode.NO_PROSE))
        for (var row : source.auditCandidates("admin", query, mode).candidates())
          for (int i = 10; i < 16; i++) assertEquals(0, row.features()[i], mode + "/" + i);
      var noApi = source.auditCandidates("admin", query, QueryPlanner.Mode.NO_API_DOCS);
      assertTrue(
          noApi.candidates().stream()
              .filter(c -> c.path().equals("Good.java"))
              .anyMatch(c -> c.features()[11] == 0 && c.features()[12] == 1));
      var noIdentifiers = source.auditCandidates("admin", query, QueryPlanner.Mode.NO_IDENTIFIERS);
      assertTrue(
          noIdentifiers.candidates().stream()
              .allMatch(c -> c.features()[10] == 0 && c.features()[12] == 0));
    }
    try (var reopened = engine(auth, RankingStrategy.SOURCE_RELATIONS, model, catalog)) {
      assertEquals("Good.java", reopened.search("admin", query).results().getFirst().path());
    }
  }

  @Test
  void repositoryLocalUsageIsAuthorizedWarmableRevocableAndBoundToRestartIdentity()
      throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "tenant", Set.of("repo", "denied"), true);
    auth.update("reader", "tenant", Set.of("repo"), false);
    var catalog = catalog("comput");
    var model = model(16, 12);
    String cursor;
    var query = new SearchEngine.Request("pulse", 1, null, Map.of());
    try (var source = engine(auth, RankingStrategy.SOURCE_USAGE, model, catalog)) {
      source.ingest(
          admin,
          "visible",
          manifest(
              source,
              "repo",
              Map.of(
                  "One.java",
                      "final class One {private static int pulse(int n){return n;} int first(int n){pulse(n);return pulse(n);}}",
                  "Two.java",
                      "final class Two {private static int pulse(int n){return n;} int first(int n){return pulse(n);} int second(int n){return pulse(n);}}")),
          GenerationBuilder.Fault.none(),
          () -> false);
      var response = source.search("reader", query);
      assertEquals("Two.java", response.results().getFirst().path());
      assertTrue(response.results().getFirst().explanation().contains("observedCallerMembers=2"));
      cursor = response.cursor();
      assertNotNull(cursor);
      assertEquals(response, source.search("reader", query));
      Map<String, String> denied = new TreeMap<>();
      for (int i = 0; i < 17; i++)
        denied.put(
            "Hidden" + i + ".java",
            "final class Hidden"
                + i
                + " { private static int pulse(int n){return n;} int secretDeniedMarker(int n){return pulse(n);} }");
      source.ingest(
          admin,
          "hidden",
          manifest(source, "denied", denied),
          GenerationBuilder.Fault.none(),
          () -> false);
      var fresh = source.search("reader", query);
      assertEquals(paths(response), paths(fresh));
      assertEquals(2, fresh.totalHits());
      assertEquals(Map.of("repo", 2), fresh.facets());
      assertFalse(fresh.toString().contains("secretDeniedMarker"));
      assertTrue(source.suggest("reader", "secret").isEmpty());
      try (var lease = source.generations("tenant").acquire()) {
        var candidates =
            new CandidateRetriever()
                .retrieve(
                    lease,
                    auth.apply(
                        new org.apache.lucene.search.MatchAllDocsQuery(), auth.current("reader")));
        try (var view =
            new SourceFeatureView(candidates, lease, catalog, auth, auth.current("reader"))) {
          auth.update("reader", "tenant", Set.of(), false);
          assertThrows(SecurityException.class, () -> view.usage("pulse"));
        }
      }
      assertEquals(0, source.search("reader", query).totalHits());
    }
    auth.update("reader", "tenant", Set.of("repo"), false);
    // Preserve exact permit version for the restart identity check independently of revocation.
    String restartedCursor;
    try (var original = engine(auth, RankingStrategy.SOURCE_USAGE, model, catalog)) {
      restartedCursor = original.search("reader", query).cursor();
      assertNotNull(restartedCursor);
    }
    try (var changed = engine(auth, RankingStrategy.SOURCE_USAGE, model, catalog("different"))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              changed.search(
                  "reader", new SearchEngine.Request("pulse", 1, restartedCursor, Map.of())));
    }
  }

  @Test
  void wholePoolWithoutNewEvidencePreservesExactC1FloatScoresAndOrdering() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "tenant", Set.of("repo"), true);
    var query = new SearchEngine.Request("ordinary shared", 20, null, Map.of());
    try (var source =
        engine(auth, RankingStrategy.SOURCE_MEMBERS, model(12, 10), catalog("comput"))) {
      source.ingest(
          admin,
          "text",
          manifest(
              source,
              "repo",
              Map.of("a.md", "ordinary shared ordinary", "b.md", "ordinary shared notes")),
          GenerationBuilder.Fault.none(),
          () -> false);
    }
    SearchEngine.Response expected;
    try (var c1 = engine(auth, RankingStrategy.SYNTAX_GATED, null, null)) {
      expected = c1.search("admin", query);
    }
    try (var source =
        engine(auth, RankingStrategy.SOURCE_MEMBERS, model(12, 10), catalog("comput"))) {
      var actual = source.search("admin", query);
      assertEquals(paths(expected), paths(actual));
      assertEquals(
          expected.results().stream().map(SearchEngine.Hit::score).toList(),
          actual.results().stream().map(SearchEngine.Hit::score).toList());
    }
  }

  @Test
  void incompatibleCatalogAndVariableDimensionArtifactsFailExplicitly() throws Exception {
    var bad =
        new SourceApiCatalog(
            "a".repeat(64),
            "b".repeat(64),
            System.getProperty("java.runtime.version"),
            "",
            Set.of(),
            List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RankingConfiguration(RankingStrategy.SOURCE_RELATIONS, model(16, 12), bad));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RankingConfiguration(
                RankingStrategy.SOURCE_MEMBERS, model(16, 12), catalog("comput")));
    for (int dimensions : List.of(12, 16)) {
      var model = model(dimensions, dimensions - 1);
      Path file = root.resolve("model" + dimensions);
      model.writeNew(file);
      var read = LinearRankingModel.read(file);
      assertEquals(model.identity(), read.identity());
      double[] features = new double[dimensions];
      features[dimensions - 1] = .75;
      assertEquals(7.5, read.score(features));
      assertTrue(
          read.explanation(features).contains(SearchFeatureSchema.names(dimensions).getLast()));
      assertThrows(IllegalArgumentException.class, () -> read.score(new double[10]));
    }
  }
  @Test
  void sourceViewAdmissionAndEveryFailureReleaseOwnedPermitWhileDeniedGraphRemainsUnread() throws Exception {
    var auth=new AuthorizationFilter();var admin=auth.update("admin","tenant",Set.of("repo","denied"),true);auth.update("reader","tenant",Set.of("repo"),false);
    var catalog=catalog("comput");
    try(var source=engine(auth,RankingStrategy.SOURCE_USAGE,model(16,12),catalog)) {
      source.ingest(admin,"allowed",manifest(source,"repo",Map.of("Visible.java","final class Visible {private static int pulse(int n){return n;}int run(int n){return pulse(n);}}")),GenerationBuilder.Fault.none(),()->false);
      source.ingest(admin,"hidden",manifest(source,"denied",Map.of("Hidden.java","final class Hidden {private static int pulse(int n){return n;}int run(int n){return pulse(n);}}")),GenerationBuilder.Fault.none(),()->false);
      try(var lease=source.generations("tenant").acquire()) {
        var candidates=new CandidateRetriever().retrieve(lease,auth.apply(new org.apache.lucene.search.MatchAllDocsQuery(),auth.current("reader")));
        assertEquals(1,candidates.size());
        var views=new ArrayList<SourceFeatureView>();
        try {
          for(int i=0;i<8;i++)views.add(new SourceFeatureView(candidates,lease,catalog,auth,auth.current("reader")));
          assertTrue(assertThrows(IOException.class,()->new SourceFeatureView(candidates,lease,catalog,auth,auth.current("reader"))).getMessage().contains("admission"));
        } finally {for(var view:views)view.close();}
        for(var view:views){view.close();assertThrows(IllegalStateException.class,()->view.usage("pulse"));}
        var duplicate=List.of(candidates.getFirst(),candidates.getFirst());
        assertThrows(IllegalArgumentException.class,()->new SourceFeatureView(duplicate,lease,catalog,auth,auth.current("reader")));
        var original=candidates.getFirst();
        var wrongKey=new CandidateRetriever.Candidate(original.document(),"tenant/repo/forged.java",original.score(),original.features());
        assertThrows(IOException.class,()->new SourceFeatureView(List.of(wrongKey),lease,catalog,auth,auth.current("reader")));
        var outOfRange=new CandidateRetriever.Candidate(lease.reader().maxDoc(),"tenant/repo/absent.java",0,"");
        assertThrows(IllegalArgumentException.class,()->new SourceFeatureView(List.of(outOfRange),lease,catalog,auth,auth.current("reader")));
        assertThrows(IllegalArgumentException.class,()->new SourceFeatureView(Collections.nCopies(501,original),lease,catalog,auth,auth.current("reader")));
        var all=new CandidateRetriever().retrieve(lease,new org.apache.lucene.search.MatchAllDocsQuery());
        var denied=all.stream().filter(c->c.key().startsWith("tenant/denied/")).toList();assertEquals(1,denied.size());
        // Use the leased directory itself rather than assuming a product path layout.
        Path partition=((org.apache.lucene.store.FSDirectory)lease.reader().directory()).getDirectory().resolve(SourceRepositoryFacts.fileName("tenant","denied"));
        byte[] hiddenBytes=Files.readAllBytes(partition);Files.delete(partition);
        try {assertThrows(SecurityException.class,()->new SourceFeatureView(denied,lease,catalog,auth,auth.current("reader")));}
        finally {Files.write(partition,hiddenBytes);}
        var stale=auth.current("reader");auth.update("reader","tenant",Set.of(),false);
        assertThrows(SecurityException.class,()->new SourceFeatureView(candidates,lease,catalog,auth,stale));
        auth.update("reader","tenant",Set.of("repo"),false);
        try(var recovered=new SourceFeatureView(candidates,lease,catalog,auth,auth.current("reader"))) {
          assertTrue(recovered.usage("pulse").values().stream().allMatch(SourceUsageRanker.Inventory::declarationMembershipKnown));
        }
      }
    }
  }

  @Test
  void deniedSameOwnerCollisionAndCallerAdditionsNeverChangeAuthorizedGraphEvidence() throws Exception {
    var auth=new AuthorizationFilter();var admin=auth.update("admin","tenant",Set.of("repo","denied"),true);auth.update("reader","tenant",Set.of("repo"),false);var catalog=catalog("comput");
    try(var source=engine(auth,RankingStrategy.SOURCE_USAGE,model(16,12),catalog)) {
      source.ingest(admin,"allowed",manifest(source,"repo",Map.of("Same.java","final class Same {static int pulse(int n){return n;}int first(int n){return pulse(n);}}")),GenerationBuilder.Fault.none(),()->false);
      Map<Integer,SourceUsageRanker.Inventory> before;
      try(var lease=source.generations("tenant").acquire()) {
        var candidates=new CandidateRetriever().retrieve(lease,auth.apply(new org.apache.lucene.search.MatchAllDocsQuery(),auth.current("reader")));
        try(var view=new SourceFeatureView(candidates,lease,catalog,auth,auth.current("reader"))){before=view.usage("pulse");}
      }
      source.ingest(admin,"hidden-collision",manifest(source,"denied",Map.of("Same.java","final class Same {static int pulse(int n){return n;}int hiddenA(int n){return pulse(n);}int hiddenB(int n){return pulse(n);}}","Unparsed.java","x".repeat(65537))),GenerationBuilder.Fault.none(),()->false);
      try(var lease=source.generations("tenant").acquire()) {
        var candidates=new CandidateRetriever().retrieve(lease,auth.apply(new org.apache.lucene.search.MatchAllDocsQuery(),auth.current("reader")));
        try(var view=new SourceFeatureView(candidates,lease,catalog,auth,auth.current("reader"))) {
          var after=view.usage("pulse");
          assertEquals(before.values().stream().toList(),after.values().stream().toList());
          assertTrue(after.values().stream().flatMap(i->i.members().stream()).allMatch(m->m.observedCallers()==1&&m.status()==SourceUsageRanker.Status.USABLE_OBSERVED));
        }
      }
      var visible=source.search("reader",new SearchEngine.Request("pulse",20,null,Map.of()));assertEquals(1,visible.totalHits());assertFalse(visible.toString().contains("hiddenA"));
      assertEquals(visible,source.search("reader",new SearchEngine.Request("pulse",20,null,Map.of())));
    }
  }

}
