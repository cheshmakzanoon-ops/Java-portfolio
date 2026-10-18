from pathlib import Path
b=Path('projects/07-indexforge');pkg=Path('io/github/cheshmakzanoon/portfolio/indexforge')
p=b/'ingest/src/main/java'/pkg/'DurableFiles.java';s=p.read_text().replace('FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)', '''FileChannel.open(tmp, java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
              Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")
                ? new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}
                : new java.nio.file.attribute.FileAttribute<?>[0])''');p.write_text(s)
p=b/'ingest/src/main/java'/pkg/'SafeSourceReader.java';s=p.read_text().replace('    if (Files.isSymbolicLink(selected)) throw new IOException("symlinks prohibited");\n','');p.write_text(s)
p=b/'service/src/main/java'/pkg/'SearchController.java';s=p.read_text().replace('        workers.execute(task);','        try{workers.execute(task);}catch(RejectedExecutionException full){states.put(job.id(),"REGISTERED");throw full;}');p.write_text(s)
p=b/'ingest/src/test/java'/pkg/'IngestionSafetyTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test
  void durablePublicationUsesOwnerOnlyPosixPermissionsFromCreation() throws Exception {
    Path output=dir.resolve("private/key");DurableFiles.atomicWrite(output,"first secret".getBytes(StandardCharsets.UTF_8));
    var permissions=java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");assertEquals(permissions,Files.getPosixFilePermissions(output));
    Files.setPosixFilePermissions(output,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
    DurableFiles.atomicWrite(output,"replacement secret".getBytes(StandardCharsets.UTF_8));assertEquals(permissions,Files.getPosixFilePermissions(output));assertEquals("replacement secret",Files.readString(output));
    try(var files=Files.list(output.getParent())){assertEquals(List.of(output),files.toList());}
  }

'''+s[at:];p.write_text(s)
p=b/'index/src/test/java'/pkg/'DeclarationScannerTest.java';s=p.read_text().replace('while (stream.incrementToken())\n        tokens.add(', 'while (stream.incrementToken()) {\n        if(tokens.size()>64)fail("prose token stream failed to terminate");\n        tokens.add(')
s=s.replace("                + offset.endOffset());\n      stream.end();", "                + offset.endOffset());\n      }\n      stream.end();",1)
p.write_text(s)
p=b/'index/src/test/java'/pkg/'GenerationLifecycleTest.java';s=p.read_text();needle='      String smaller =';a=s.index(needle);s=s[:a]+'''      var unicode=new SnapshotManifest.Entry("src/Unicode.java",cas.put("α".repeat(100).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"java","source",List.of(),"","");
      var unicodeGrowth=new SnapshotManifest("t","r","unicode",false,List.of(unicode),Set.of());
      assertThrows(IOException.class,()->builder.build(unicodeGrowth,publisher.sources(),GenerationBuilder.Fault.none(),()->false));
'''+s[a:];p.write_text(s)
p=b/'service/src/test/java'/pkg/'HttpSecurityIT.java';s=p.read_text();a=s.index('  @Test');s=s[:a]+'''  @Test
  void realHttpQueueRejectsEleventhJobAndKeepsRejectedRegistrationHonest() throws Exception {
    Files.createDirectories(dir.resolve("sources/demo/public/project"));Files.writeString(dir.resolve("sources/demo/public/project/Queue.java"),"class Queue { void queueCapacitySymbol() {} }");
    SpringApplication app=new SpringApplication(IndexForgeApplication.class);app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","0","spring.profiles.active","dev","indexforge.issuer-port","0","indexforge.data",dir.resolve("data").toString(),"indexforge.sources",dir.resolve("sources").toString(),"spring.main.banner-mode","off"));
    try(var context=app.run();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
      String base="http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port");var client=HttpClient.newHttpClient();var issuer=context.getBean(LocalOidcIssuer.class);String token=issuer.token("alice","demo",Instant.now().plusSeconds(300),"indexforge");var engine=context.getBean(SearchEngine.class);var access=context.getBean(AuthorizationFilter.class).current("alice");
      var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
      var entry=new SnapshotManifest.Entry("Held.java",engine.contentStore().put("class Held { void heldBuildSymbol() {} }".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"java","source",List.of("heldBuildSymbol"),"","");
      var held=pool.submit(()->{engine.ingest(access,"held",new SnapshotManifest("demo","public","held",false,List.of(entry),Set.of()),boundary->{if(boundary==GenerationBuilder.Boundary.BEFORE_COMMIT){entered.countDown();try{if(!release.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("held build timeout");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}}},()->false);return null;});
      List<String> accepted=new ArrayList<>();
      try {
        assertTrue(entered.await(15,java.util.concurrent.TimeUnit.SECONDS));
        for(int i=0;i<11;i++) {
          String key="capacity-"+i;String body="{\\"repository\\":\\"public\\",\\"revision\\":\\"queue\\",\\"idempotencyKey\\":\\""+key+"\\",\\"path\\":\\"project\\"}";
          var response=request(client,base,"/v1/ingestions",token,body);assertEquals(i<10?202:429,response.statusCode(),response.body());
          String id=Digests.sha256("demo\\npublic\\n"+key);
          if(i<10)accepted.add(id);else {var status=request(client,base,"/v1/ingestions/"+id,token,null);assertEquals(200,status.statusCode());assertTrue(status.body().contains("REGISTERED"),status.body());assertFalse(status.body().contains("QUEUED"));}
        }
      } finally {release.countDown();}
      held.get(30,java.util.concurrent.TimeUnit.SECONDS);long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();while(accepted.stream().anyMatch(id->!engine.completed(id))&&System.nanoTime()<deadline)Thread.sleep(20);assertTrue(accepted.stream().allMatch(engine::completed));assertEquals(1,engine.search("alice",new SearchEngine.Request("queueCapacitySymbol",10,null,Map.of())).totalHits());
    }
  }

'''+s[a:];p.write_text(s)
p=b/'evaluation/src/main/java'/pkg/'RelevanceEvaluator.java';s=p.read_text().replace('            "SourceProseAnalyzer",','            "RelevanceEvaluator",\n            "SourceProseAnalyzer",').replace('"corpus-" + Digests.sha256(Files.readAllBytes(fixtures.resolve("corpus.tsv")))','"evaluation-" + Digests.sha256(Digests.sha256(Files.readAllBytes(fixtures.resolve("corpus.tsv"))) + "\\n" + modelFingerprint())');p.write_text(s)
