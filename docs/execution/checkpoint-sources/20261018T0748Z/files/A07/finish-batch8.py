from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge');base=Path('src/main/java/io/github/cheshmakzanoon/portfolio/indexforge');test=Path('src/test/java/io/github/cheshmakzanoon/portfolio/indexforge')
p=root/'query'/base/'SearchEngine.java';s=p.read_text();start=s.index('  public void reindex(');end=s.index('\n  @Override',start);s=s[:start]+'''  public IngestionJob.Registered registerReindex(AuthorizationFilter.Access a)throws IOException {
    if(!a.administrator()||!authorization.current(a))throw new SecurityException("access denied");
    var first=generations(a.tenant()).sources().stream().filter(source->a.repositories().contains(source.repository())).findFirst().orElseThrow(()->new IllegalArgumentException("nothing to reindex"));
    // Empty partial input requests an exact rebuild of committed state; source revisions stay unchanged.
    SnapshotManifest manifest=new SnapshotManifest(a.tenant(),first.repository(),"reindex-request",false,List.of(),Set.of());
    return register(a,"reindex-"+UUID.randomUUID(),manifest);
  }

  public void reindex(AuthorizationFilter.Access a)throws IOException {
    var job=registerReindex(a);resume(a,job.id(),GenerationBuilder.Fault.none(),()->false);
  }
'''+s[end:];p.write_text(s)
p=root/'service'/base/'SearchController.java';s=p.read_text();start=s.index('    synchronized (futures) {',s.index('  @PostMapping("/v1/ingestions")'));end=s.index('  @GetMapping("/v1/ingestions/{id}")',start);s=s[:start]+'''    return enqueue(a,job);
  }

  private ResponseEntity<Map<String,String>> enqueue(AuthorizationFilter.Access access,IngestionJob.Registered job) {
    synchronized(futures){
      futures.entrySet().removeIf(entry->entry.getValue().isDone());
      if(!futures.containsKey(job.id())&&!engine.completed(job.id())){
        FutureTask<Void> task=new FutureTask<>(()->{states.put(job.id(),"RUNNING");try{engine.resume(access,job.id(),GenerationBuilder.Fault.none(),Thread.currentThread()::isInterrupted);states.put(job.id(),"COMPLETE");}catch(Exception failed){states.put(job.id(),Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED");failures.incrementAndGet();}return null;});
        states.put(job.id(),"QUEUED");workers.execute(task);futures.put(job.id(),task);
      }
    }
    return ResponseEntity.accepted().body(Map.of("id",job.id(),"state",engine.completed(job.id())?"COMPLETE":states.getOrDefault(job.id(),"REGISTERED")));
  }

  @PostMapping("/v1/ingestions/{id}/resume")
  ResponseEntity<Map<String,String>> resume(@AuthenticationPrincipal Jwt jwt,@PathVariable String id)throws IOException {
    var access=context(jwt,true);return enqueue(access,engine.job(access,id));
  }

'''+s[end:];start=s.index('  @PostMapping("/v1/reindexes")');end=s.index('  @GetMapping("/v1/metrics")',start);s=s[:start]+'''  @PostMapping("/v1/reindexes")
  ResponseEntity<Map<String,String>> reindex(@AuthenticationPrincipal Jwt jwt)throws IOException {
    var access=context(jwt,true);return enqueue(access,engine.registerReindex(access));
  }

'''+s[end:];p.write_text(s)
p=root/'service'/base/'AdmissionFilter.java';s=p.read_text().replace('response.sendError(429);','reject(response,429,"REQUEST_CAPACITY");').replace('response.sendError(413);','reject(response,413,"BODY_TOO_LARGE");');at=s.index('  private static final class SetLike');s=s[:at]+'''  private void reject(HttpServletResponse response,int status,String code)throws IOException {
    response.setStatus(status);response.setContentType("application/json");response.getWriter().write("{\\"error\\":\\""+code+"\\"}");
  }

'''+s[at:];p.write_text(s)
p=root/'query'/test/'SearchSecurityTest.java';s=p.read_text().replace('      String old = e.generations("t").currentId();\n      e.reindex(a);','      String old = e.generations("t").currentId();\n      var before=e.generations("t").sources();\n      e.reindex(a);\n      assertEquals(before,e.generations("t").sources());');p.write_text(s)
p=root/'index'/test/'GenerationLifecycleTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test void identifierStreamHasExactSynonymPositionsAndSourceOffsets()throws Exception {
    try(var analyzer=new CodeIdentifierAnalyzer();var stream=analyzer.tokenStream("content","fooBar baz")) {
      var term=stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
      var offset=stream.addAttribute(org.apache.lucene.analysis.tokenattributes.OffsetAttribute.class);
      var position=stream.addAttribute(org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute.class);
      stream.reset();List<String> actual=new ArrayList<>();while(stream.incrementToken())actual.add(term.toString()+":"+position.getPositionIncrement()+":"+offset.startOffset()+":"+offset.endOffset());
      assertEquals(List.of("foobar:1:0:6","foo:0:0:3","bar:0:3:6","baz:1:7:10"),actual);
      stream.end();assertEquals(10,offset.startOffset());assertEquals(10,offset.endOffset());assertEquals(0,position.getPositionIncrement());
    }
  }

'''+s[at:];p.write_text(s)
