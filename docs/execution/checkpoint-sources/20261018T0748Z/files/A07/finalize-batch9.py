from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge');base=Path('src/main/java/io/github/cheshmakzanoon/portfolio/indexforge');test=Path('src/test/java/io/github/cheshmakzanoon/portfolio/indexforge')
p=root/'index'/test/'GenerationLifecycleTest.java';s=p.read_text().replace('                + offset.endOffset());\n      assertEquals(List.of("foobar:1:0:6"','                + offset.endOffset());}\n      assertEquals(List.of("foobar:1:0:6"');s=s.replace('        while (tokens.incrementToken()) {\n          values.add','        while (tokens.incrementToken()) {\n          if(values.size()>64)fail("token stream failed to terminate");\n          values.add');p.write_text(s)
p=root/'index'/base/'CodeIdentifierAnalyzer.java';s=p.read_text();a=s.index('    Matcher words = Pattern.compile(');b=s.index(';',a)+1;word=s[a:b];literal=word[word.index('"'):word.rindex('"')+1];a2=s.index('      Matcher pieces =');b2=s.index('      while (pieces.find())',a2);chunk=s[a2:b2];pieces=chunk[chunk.index('"'):chunk.rindex('"')+1];s=s[:a2]+'      Matcher pieces = IDENTIFIER_PIECES.matcher(original);\n'+s[b2:];s=s.replace(word,'    Matcher words = WORDS.matcher(input);');s=s.replace('  public record Part(',f'  private static final Pattern WORDS=Pattern.compile({literal});\n  private static final Pattern IDENTIFIER_PIECES=Pattern.compile({pieces});\n\n  public record Part(',1);p.write_text(s)
p=root/'query'/base/'AuthorizationFilter.java';s=p.read_text().replace('      repositories = Set.copyOf(repositories);','      repositories = Set.copyOf(repositories);\n      for(String repository:repositories)if(!repository.matches("[a-zA-Z0-9_-]{1,64}"))throw new IllegalArgumentException("repository context");');p.write_text(s)
p=root/'query'/test/'SearchSecurityTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test void sourceBindingsCannotContainTraversalRepositoryIdentities() {
    assertThrows(IllegalArgumentException.class,()->new AuthorizationFilter.Access("a","tenant",Set.of("../other"),1,false));
  }

'''+s[at:];p.write_text(s)
p=root/'query'/base/'SearchEngine.java';s=p.read_text().replace('  private final Path root;','  private final QueryPlanner.Mode defaultMode;\n  private final Path root;');s=s.replace('  public SearchEngine(Path root, AuthorizationFilter authorization) throws IOException {','''  public SearchEngine(Path root, AuthorizationFilter authorization)throws IOException {
    this(root,authorization,QueryPlanner.Mode.valueOf(System.getProperty("indexforge.ranking-mode","BASELINE")));
  }

  public SearchEngine(Path root,AuthorizationFilter authorization,QueryPlanner.Mode defaultMode)throws IOException {''');s=s.replace('    this.root = root;','    this.root = root;\n    this.defaultMode=Objects.requireNonNull(defaultMode);',1);s=s.replace('return search(principal, request, QueryPlanner.Mode.SOURCE_AWARE);','return search(principal, request, defaultMode);');p.write_text(s)
p=root/'service'/base/'IndexForgeApplication.java';s=p.read_text().replace('        auth);','        auth, QueryPlanner.Mode.valueOf(env.getProperty("indexforge.ranking-mode","BASELINE")));');p.write_text(s)
# Add cross-tenant source binding and actual positive foreign-index precondition.
p=root/'service'/test/'HttpSecurityIT.java';s=p.read_text();needle='      SearchEngine engine = context.getBean(SearchEngine.class);';s=s.replace(needle,needle+'''
      Files.createDirectories(dir.resolve("sources/other/public/project"));Files.writeString(dir.resolve("sources/other/public/project/Foreign.java"),"class Foreign { void foreignTenantSymbol() {} }");
      String bob=issuer.token("bob","other",Instant.now().plusSeconds(300),"indexforge");
      assertEquals(202,request(client,base,"/v1/ingestions",bob,body).statusCode());
      long foreignDeadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(engine.generations("other").currentId()==null&&System.nanoTime()<foreignDeadline)Thread.sleep(20);
      var foreign=request(client,base,"/v1/search",bob,"{\\"text\\":\\"foreignTenantSymbol\\",\\"pageSize\\":10}");assertEquals(200,foreign.statusCode());assertTrue(foreign.body().contains("Foreign.java"));
      var isolated=request(client,base,"/v1/search",token,"{\\"text\\":\\"foreignTenantSymbol\\",\\"pageSize\\":10}");assertEquals(200,isolated.statusCode());assertFalse(isolated.body().contains("Foreign.java"));assertTrue(isolated.body().contains("\\"totalHits\\":0"));
      String tenantEscape="{\\"repository\\":\\"public\\",\\"revision\\":\\"v2\\",\\"idempotencyKey\\":\\"tenant-escape\\",\\"path\\":\\"../../other/public\\"}";assertEquals(422,request(client,base,"/v1/ingestions",token,tenantEscape).statusCode());
''');p.write_text(s)
