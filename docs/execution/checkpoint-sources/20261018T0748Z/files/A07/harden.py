from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge');base=Path('src/main/java/io/github/cheshmakzanoon/portfolio/indexforge');tests=Path('src/test/java/io/github/cheshmakzanoon/portfolio/indexforge')
p=root/'service'/base/'SearchController.java';s=p.read_text().replace('      boolean archive,\n      boolean complete,','      Boolean archive,\n      Boolean complete,').replace('      Set<String> deletions) {}','      Set<String> deletions) {public IngestRequest {archive=Boolean.TRUE.equals(archive);complete=Boolean.TRUE.equals(complete);}}');at=s.index('  @GetMapping("/v1/ingestions/{id}")');s=s[:at]+'''  @PostMapping("/v1/ingestions/{id}/resume")
  Map<String,String> resume(@AuthenticationPrincipal Jwt jwt,@PathVariable String id)throws IOException {
    var access=context(jwt,true);engine.resume(access,id,GenerationBuilder.Fault.none(),Thread.currentThread()::isInterrupted);
    return Map.of("id",id,"state","COMPLETE");
  }

'''+s[at:];p.write_text(s)
p=root/'service'/base/'ErrorResponses.java';s=p.read_text().replace('  @ExceptionHandler(IllegalArgumentException.class)','  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)\n  ResponseEntity<Map<String,String>> malformed(){return error(400,"INVALID_REQUEST");}\n\n  @ExceptionHandler(IllegalArgumentException.class)');p.write_text(s)
p=root/'query'/base/'SearchEngine.java';s=p.read_text();start=s.index('    Path done = root.resolve("jobs").resolve(job.id() + ".done");');s=s[:start]+'''    return executeRegistered(a,job,fault,cancelled);
  }

  public synchronized IngestionJob.Registered resume(AuthorizationFilter.Access a,String id,GenerationBuilder.Fault fault,BooleanSupplier cancelled)throws IOException {
    return executeRegistered(a,job(a,id),fault,cancelled);
  }

  private IngestionJob.Registered executeRegistered(AuthorizationFilter.Access a,IngestionJob.Registered job,GenerationBuilder.Fault fault,BooleanSupplier cancelled)throws IOException {
    SnapshotManifest m=job.manifest();
'''+s[start:];p.write_text(s)
p=root/'cli'/base/'IndexForgeCli.java';s=p.read_text().replace('        case "reindex" -> {','        case "resume" -> {engine.resume(access,args[4],GenerationBuilder.Fault.none(),()->false);System.out.println("Resumed job="+args[4]);}\n        case "reindex" -> {');p.write_text(s)
p=root/'ingest'/base/'Digests.java';s=p.read_text();at=s.index('  public static String sha256(String text)');s=s[:at]+'''  public static String sha256(java.nio.file.Path path)throws java.io.IOException {
    try {
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      try(var input=java.nio.file.Files.newInputStream(path)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
      return HexFormat.of().formatHex(digest.digest());
    } catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }

'''+s[at:];p.write_text(s)
p=root/'ingest'/base/'IngestionJob.java';s=p.read_text().replace('SnapshotManifest.read(Files.readAllBytes(p))','load(p)').replace('SnapshotManifest.read(Files.readAllBytes(root.resolve(id)))','load(root.resolve(id))');at=s.index('  public static final class Conflict');s=s[:at]+'''  private SnapshotManifest load(Path path)throws IOException {
    if(Files.size(path)>64L*1024*1024)throw new IOException("manifest metadata byte quota");
    return SnapshotManifest.read(Files.readAllBytes(path));
  }

'''+s[at:];p.write_text(s)
p=root/'index'/base/'GenerationBuilder.java';s=p.read_text().replace('    String hash = manifest.digest();','    String hash = manifest.digest();\n    checkpoints.read(hash); // Validated hints never override the authoritative manifest/commit.');s=s.replace('Digests.sha256(Files.readAllBytes(p))','Digests.sha256(p)');at=s.index('    ByteArrayOutputStream bytes = new ByteArrayOutputStream();');s=s[:at]+'''    long estimate=16;
    for(SourceDocument document:docs){estimate+=160+3L*(document.key().length()+document.revision().length()+document.entry().language().length()+document.entry().kind().length()+document.entry().coordinates().length()+document.entry().version().length());for(String symbol:document.entry().symbols())estimate+=2+3L*symbol.length();}
    if(estimate>64L*1024*1024)throw new IOException("complete snapshot metadata byte quota");
'''+s[at:];p.write_text(s)
p=root/'ingest'/base/'CheckpointStore.java';s=p.read_text().replace('      return OptionalInt.of(Integer.parseInt(parts[1]));','      int completed=Integer.parseInt(parts[1]);if(completed<0||completed>100_000)throw new IOException("checkpoint bounds");return OptionalInt.of(completed);');p.write_text(s)
p=root/'index'/base/'GenerationPublisher.java';s=p.read_text();s=s.replace('Files.readString(root.resolve("generations").resolve(id).resolve("generation.sha256"))','checksumText(root.resolve("generations").resolve(id))').replace('Files.readString(dir.resolve("generation.sha256"))','checksumText(dir)');s=s.replace('SnapshotManifest manifest =\n        SnapshotManifest.read(Files.readAllBytes(dir.resolve("manifest.bin")));','if(Files.size(dir.resolve("manifest.bin"))>64L*1024*1024||Files.size(dir.resolve("snapshot.bin"))>64L*1024*1024)throw new IOException("generation metadata byte quota");\n    SnapshotManifest manifest =\n        SnapshotManifest.read(Files.readAllBytes(dir.resolve("manifest.bin")));');at=s.index('  private Handle open(');s=s[:at]+'''  private String checksumText(Path directory)throws IOException {
    Path path=directory.resolve("generation.sha256");if(Files.size(path)!=64)throw new IOException("checksum metadata bounds");String checksum=Files.readString(path);if(!checksum.matches("[a-f0-9]{64}"))throw new IOException("checksum encoding");return checksum;
  }

'''+s[at:];p.write_text(s)
p=root/'ingest'/tests/'IngestionSafetyTest.java';s=p.read_text().replace('    var store = cas();\n    String bad', '    var store = cas();\n    String exact=store.put(new byte[1048576]);assertEquals(1048576,store.read(exact).length);\n    String bad');p.write_text(s)
p=root/'query'/tests/'SearchSecurityTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test void interruptedJobResumesFromImmutableManifestAfterSourceDisappears() throws Exception {
    var auth=new AuthorizationFilter();var access=auth.update("a","t",Set.of("r"),true);String id;
    try(var engine=new SearchEngine(root,auth)) {
      engine.ingest(access,"old",manifest(engine,"t","r",1,"previous"),GenerationBuilder.Fault.none(),()->false);
      Path sources=root.resolve("source-input");Files.createDirectory(sources);Files.writeString(sources.resolve("Resume.java"),"class Resume { void immutableResumeContent() {} }");
      var m=new SafeSourceReader(sources,SafeSourceReader.Limits.defaults(),engine.contentStore()).directory("t","r","resume",Path.of("."),false,Set.of(),()->false);
      var job=engine.register(access,"interrupted",m);id=job.id();
      assertThrows(IOException.class,()->engine.ingest(access,"interrupted",m,atBoundary->{if(atBoundary==GenerationBuilder.Boundary.BEFORE_COMMIT)throw new IOException("crash before commit");},()->false));
      assertFalse(engine.completed(id));Files.delete(sources.resolve("Resume.java"));Files.delete(sources);
    }
    try(var engine=new SearchEngine(root,auth)) {
      engine.resume(access,id,GenerationBuilder.Fault.none(),()->false);assertTrue(engine.completed(id));
      var result=engine.search("a",new SearchEngine.Request("immutableResumeContent",10,null,Map.of()));assertEquals(1,result.totalHits());assertEquals("Resume.java",result.results().getFirst().path());
      String generation=engine.generations("t").currentId();engine.resume(access,id,GenerationBuilder.Fault.none(),()->false);assertEquals(generation,engine.generations("t").currentId());
    }
  }

'''+s[at:];p.write_text(s)
p=root/'index'/'pom.xml';s=p.read_text().replace('indexforge.CodeIdentifierAnalyzer</param>','indexforge.CodeIdentifierAnalyzer*</param>');p.write_text(s)
