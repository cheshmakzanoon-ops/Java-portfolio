from pathlib import Path
b=Path('projects/07-indexforge');root='src/test/java/io/github/cheshmakzanoon/portfolio/indexforge'
(b/'index'/root/'CodeFeaturesTest.java').write_text(r'''package io.github.cheshmakzanoon.portfolio.indexforge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CodeFeaturesTest {
  @Test
  void genericApiTokenPatternsProduceExplicitlyHeuristicTopics() {
    Map<String,String> fragments = Map.of(
        "strict_utf8", "StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)",
        "message_digest", "MessageDigest.getInstance(algorithm)",
        "mac", "Mac.getInstance(algorithm); new SecretKeySpec(bytes, algorithm)",
        "atomic_move", "Files.move(from,to,StandardCopyOption.ATOMIC_MOVE)",
        "durable_force", "FileChannel.open(path); channel.force(true)",
        "archive", "new ZipInputStream(input)",
        "index_reader", "DirectoryReader.open(directory)",
        "reader_lifetime", "class Holder implements AutoCloseable { DirectoryReader reader; AtomicBoolean state; void close(){state.compareAndSet(false,true);} }",
        "query_filter", "new TermQuery(term); BooleanClause.Occur.FILTER",
        "lexical_scan", "Character.isWhitespace(c); input.substring(0); input.indexOf(c)");
    for (var entry : fragments.entrySet()) {
      assertTrue(CodeFeatures.features(entry.getValue()).contains(entry.getKey()),entry.getKey());
      assertFalse(CodeFeatures.topics(CodeFeatures.features(entry.getValue())).isBlank());
    }
    assertEquals(Set.of("archive"),CodeFeatures.features("new ZipFile(path)"));
    assertEquals(Set.of("index_reader"),CodeFeatures.features("DirectoryReader reader; new IndexSearcher(reader)"));
    assertTrue(CodeFeatures.vocabulary().contains("malformed"));
  }

  @Test
  void commentsStringsTextBlocksPartialApiMentionsAndShadowedNamesAreNotEvidence() {
    String fragment = "StandardCharsets.UTF_8.newDecoder(CodingErrorAction.REPORT); Files.move(a,b,StandardCopyOption.ATOMIC_MOVE);";
    String decoy = "//"+fragment+"\n/*"+fragment+"*/\nclass Example { String code=\""+fragment+"\"; String block=\"\"\"\n"+fragment+"\n\"\"\"; }";
    assertEquals(Set.of(),CodeFeatures.features(decoy));
    assertEquals(Set.of(),CodeFeatures.features("class Files {} "+fragment));
    for (String incomplete : List.of(
        "StandardCharsets.UTF_8; CodingErrorAction.REPORT;",
        "StandardCharsets.UTF_8.newDecoder();",
        "Mac.getInstance(x);", "Files.move(a,b);", "FileChannel.open(path);",
        "DirectoryReader reader;", "DirectoryReader reader; AutoCloseable resource;",
        "DirectoryReader reader; AutoCloseable resource; AtomicBoolean state;",
        "BooleanClause.Occur.FILTER;", "Character.isWhitespace(c);",
        "Character.isWhitespace(c); input.substring(0);"))
      assertEquals(Set.of(),CodeFeatures.features(incomplete),incomplete);
    assertEquals(Set.of(),CodeFeatures.features("; ".repeat(100001)+fragment));
    assertThrows(IllegalArgumentException.class,()->CodeFeatures.features(" ".repeat(1_048_577)));
  }
}
''')
p=b/'query'/root/'SearchSecurityTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+r'''  @Test
  void codeHintsRequireTwoTopicsIgnoreDecoysAndPreserveExplicitSyntaxAndAuthorization() throws Exception {
    var auth = new AuthorizationFilter();
    var admin = auth.update("admin", "t", Set.of("r", "hidden"), true);
    auth.update("reader", "t", Set.of("r"), false);
    String pattern = "StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);";
    var request = new SearchEngine.Request("malformed encoding", 10, null, Map.of());
    try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.CODE_MEDIUM)) {
      List<SnapshotManifest.Entry> entries = new ArrayList<>();
      for (var source : Map.of("Real.java", "class Real { void read(){"+pattern+"} }",
          "Quoted.java", "class Quoted { String example=\""+pattern+"\"; }",
          "Comment.java", "//"+pattern,
          "Archive.java", "class Archive { void read(){new ZipInputStream(input);} }").entrySet())
        entries.add(new SnapshotManifest.Entry(source.getKey(), engine.contentStore().put(source.getValue().getBytes(StandardCharsets.UTF_8)),
            "java", "source", List.of(), "", ""));
      engine.ingest(admin,"hints",new SnapshotManifest("t","r","v1",false,entries,Set.of()), GenerationBuilder.Fault.none(),()->false);
      String hiddenHash = engine.contentStore().put(("class Hidden { void read(){"+pattern+"} }").getBytes(StandardCharsets.UTF_8));
      engine.ingest(admin,"hidden",new SnapshotManifest("t","hidden","v1",false,
          List.of(new SnapshotManifest.Entry("Hidden.java",hiddenHash,"java","source",List.of(),"","")),Set.of()), GenerationBuilder.Fault.none(),()->false);
      assertEquals(0,engine.search("reader",request,QueryPlanner.Mode.NO_CODE_FEATURES).totalHits());
      var result=engine.search("reader",request);
      assertEquals(1,result.totalHits());
      assertEquals("Real.java",result.results().getFirst().path());
      assertEquals(Map.of("r",1),result.facets());
      assertTrue(result.results().getFirst().explanation().contains("code_features:"));
      assertFalse(result.toString().contains("Hidden.java"));
      assertEquals(result,engine.search("reader",request));
      for(String explicit:List.of("\"malformed encoding\"","malformed AND encoding","content:malformed OR content:encoding","malformed"))
        assertEquals(0,engine.search("reader",new SearchEngine.Request(explicit,10,null,Map.of())).totalHits(),explicit);
      assertEquals(0,engine.search("reader",new SearchEngine.Request("astronomy telescope",10,null,Map.of())).totalHits());
      auth.update("reader","t",Set.of(),false);
      assertTrue(engine.search("reader",request).results().isEmpty());
    }
    assertTrue(CodeFeatureQuery.plan(null).isEmpty());
    assertTrue(CodeFeatureQuery.plan("malformed malformed").isEmpty());
    assertThrows(IllegalArgumentException.class,()->CodeFeatureQuery.plan("a".repeat(513)));
  }

  @Test
  void cursorScopeCanonicalizationSeparatesAmbiguousCodeUnitsAndPreservesRepositorySetOrder() {
    var clock=Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"),ZoneOffset.UTC);
    var cursor=new SearchCursor(new byte[32],clock);
    var first=new AuthorizationFilter.Access(String.valueOf((char)1)+"#","t",Set.of("a","z"),1,false);
    String token=cursor.encode(first,"q",UUID.randomUUID().toString(),1);
    var reordered=new AuthorizationFilter.Access(first.principal(),"t",new LinkedHashSet<>(List.of("z","a")),1,false);
    assertEquals(1,cursor.decode(token,reordered,"q").offset());
    var collision=new AuthorizationFilter.Access(new String(new char[]{0x12,3}),"t",Set.of("a","z"),1,false);
    assertThrows(IllegalArgumentException.class,()->cursor.decode(token,collision,"q"));
    var surrogate=new AuthorizationFilter.Access(String.valueOf((char)0xd800),"t",Set.of("a"),1,false);
    String opaque=cursor.encode(surrogate,"q",UUID.randomUUID().toString(),1);
    var replacement=new AuthorizationFilter.Access(String.valueOf((char)0xfffd),"t",Set.of("a"),1,false);
    assertThrows(IllegalArgumentException.class,()->cursor.decode(opaque,replacement,"q"));
  }

'''+s[at:];p.write_text(s)
