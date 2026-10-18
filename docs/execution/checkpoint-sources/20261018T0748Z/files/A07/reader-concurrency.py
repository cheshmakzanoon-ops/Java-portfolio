# Apply only after current certifying invocation exits.
from pathlib import Path
root=Path('/workspace/Java-portfolio/projects/07-indexforge');base=Path('src/main/java/io/github/cheshmakzanoon/portfolio/indexforge');tests=Path('src/test/java/io/github/cheshmakzanoon/portfolio/indexforge')
p=root/'query'/base/'SearchEngine.java';s=p.read_text();s=s.replace('  private final Path root;','  private final Map<String,java.util.concurrent.locks.ReentrantLock> writers=new ConcurrentHashMap<>();\n  private final Path root;');s=s.replace('public synchronized IngestionJob.Registered ingest(', 'public IngestionJob.Registered ingest(').replace('public synchronized IngestionJob.Registered resume(','public IngestionJob.Registered resume(').replace('public synchronized void reindex(','public void reindex(')
# Method bodies will be Google formatted after current run, locate stable statements rather than spacing.
start=s.index('    SnapshotManifest m = job.manifest();',s.index('private IngestionJob.Registered executeRegistered'))
end=s.index('\n  public IngestionJob.Registered job(',start)
body=s[start:end]
body=body.replace('    SnapshotManifest m = job.manifest();','''    var writer=writers.computeIfAbsent(a.tenant(),ignored->new java.util.concurrent.locks.ReentrantLock());
    try {writer.lockInterruptibly();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("cancelled",interrupted);}
    try {
    SnapshotManifest m = job.manifest();''',1)
last=body.rfind('  }');body=body[:last]+'''    } finally {writer.unlock();}
'''+body[last:]
s=s[:start]+body+s[end:]
# Reindex acquires same reentrant tenant lock before snapshot capture.
start=s.index('  public void reindex(');end=s.index('\n  @Override',start);body=s[start:end];openbrace=body.index('{')+1
body=body[:openbrace]+'''
    var writer=writers.computeIfAbsent(a.tenant(),ignored->new java.util.concurrent.locks.ReentrantLock());writer.lock();try {
'''+body[openbrace:];last=body.rfind('  }');body=body[:last]+'''    }finally{writer.unlock();}
'''+body[last:];s=s[:start]+body+s[end:];p.write_text(s)
p=root/'query'/tests/'SearchSecurityTest.java';s=p.read_text();at=s.index('  @Test');s=s[:at]+'''  @Test void replacementBuildDoesNotBlockSearchesOnCommittedGeneration() throws Exception {
    var auth=new AuthorizationFilter();var access=auth.update("a","t",Set.of("r"),true);
    try(var engine=new SearchEngine(root,auth);var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
      engine.ingest(access,"old",manifest(engine,"t","r",1,"old"),GenerationBuilder.Fault.none(),()->false);
      String old=engine.generations("t").currentId();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
      var next=manifest(engine,"t","r",1,"new");
      var build=pool.submit(()->{engine.ingest(access,"new",next,boundary->{if(boundary==GenerationBuilder.Boundary.BEFORE_COMMIT){entered.countDown();try{if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("build test release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}},()->false);return null;});
      try{assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));var search=pool.submit(()->engine.search("a",new SearchEngine.Request("shared",10,null,Map.of())));assertEquals(old,search.get(5,java.util.concurrent.TimeUnit.SECONDS).generationId());}finally{release.countDown();}
      build.get(20,java.util.concurrent.TimeUnit.SECONDS);assertNotEquals(old,engine.generations("t").currentId());assertEquals(2,engine.search("a",new SearchEngine.Request("shared",10,null,Map.of())).totalHits());
    }
  }

'''+s[at:];p.write_text(s)
