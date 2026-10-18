# Staged for a later explicitly resumed batch. Do not execute during a frozen build/quiet hold.
from pathlib import Path
b=Path('projects/07-indexforge')
p=b/'query/src/main/java/io/github/cheshmakzanoon/portfolio/indexforge/SearchCursor.java';s=p.read_text().replace('+ a.principal()','+ principalScope(a)').replace('!p[1].equals(a.principal())','!p[1].equals(principalScope(a))');at=s.index('  private byte[] sign(');s=s[:at]+'''  private static String principalScope(AuthorizationFilter.Access a) {
    // Hex UTF16 code units are unambiguous even for unusual local principal strings.
    StringBuilder identity = new StringBuilder();
    for (int i = 0; i < a.principal().length(); i++)
      identity.append(Integer.toHexString(a.principal().charAt(i))).append(',');
    identity.append('\\n').append(String.join("\\n", new TreeSet<>(a.repositories())))
        .append('\\n').append(a.administrator());
    return Digests.sha256(identity.toString());
  }

'''+s[at:];p.write_text(s)
p=b/'query/src/main/java/io/github/cheshmakzanoon/portfolio/indexforge/SearchEngine.java';s=p.read_text();s=s.replace('''    String digest =
''','''    String scoringVersion = mode == QueryPlanner.Mode.BASELINE ? "bm25-content-v1"
        : QueryPlanner.VERSION + "-" + rankingProfile.name();
    String digest =
''',1).replace('''                + mode);
''','''                + mode
                + "\\n" + scoringVersion);
''',1).replace('''                mode == QueryPlanner.Mode.BASELINE
                    ? "bm25-content-v1"
                    : QueryPlanner.VERSION + "-" + rankingProfile.name(),''','''                scoringVersion,''');p.write_text(s)
p=b/'query/src/test/java/io/github/cheshmakzanoon/portfolio/indexforge/SearchSecurityTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test
  void cursorRejectsChangedRankingProfileAcrossRestartButSameProfileContinues() throws Exception {
    var auth = new AuthorizationFilter();
    var access = auth.update("reader", "t", Set.of("r"), true);
    var request = new SearchEngine.Request("shared", 1, null, Map.of());
    String cursor, first;
    try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_DEFAULT)) {
      engine.ingest(access, "cursor", manifest(engine, "t", "r", 2, "method"),
          GenerationBuilder.Fault.none(), () -> false);
      var page = engine.search("reader", request);
      cursor = page.cursor(); first = page.results().getFirst().key();
      assertNotNull(cursor);
    }
    var next = new SearchEngine.Request("shared", 1, cursor, Map.of());
    try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_DEFAULT)) {
      var continuation = engine.search("reader", next);
      assertEquals(1, continuation.results().size());
      assertNotEquals(first, continuation.results().getFirst().key());
    }
    try (var engine = new SearchEngine(root, auth, QueryPlanner.Mode.SOURCE_AWARE, RankingProfile.BM25_NO_LENGTH)) {
      assertThrows(IllegalArgumentException.class, () -> engine.search("reader", next));
    }
  }

  @Test
  void cursorBindsActualScopeDespiteRestartVersionReuseAndSupportsNewlinePrincipals() {
    var clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    var cursor = new SearchCursor(new byte[32], clock);
    var access = new AuthorizationFilter.Access("line\\nprincipal", "t", Set.of("r"), 1, false);
    String generation = UUID.randomUUID().toString();
    String token = cursor.encode(access, "query", generation, 1);
    assertEquals(new SearchCursor.Position(generation, 1), cursor.decode(token, access, "query"));
    assertThrows(IllegalArgumentException.class, () -> cursor.decode(token,
        new AuthorizationFilter.Access("line\\nprincipal", "t", Set.of("other"), 1, false), "query"));
    assertThrows(IllegalArgumentException.class, () -> cursor.decode(token,
        new AuthorizationFilter.Access("line\\nprincipal", "t", Set.of("r"), 1, true), "query"));
  }

'''+s[at:];p.write_text(s)
