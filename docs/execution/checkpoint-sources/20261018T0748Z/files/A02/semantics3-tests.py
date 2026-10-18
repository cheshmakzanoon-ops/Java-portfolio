from pathlib import Path
b=Path('projects/02-durableflow')
p=b/'workflow-model/src/test/java/io/github/cheshmakzanoon/portfolio/durableflow/DataTest.java';s=p.read_text();i=s.rindex('\n}');s=s[:i]+'''
  @Test void cancellationEvidenceIsAnActualBoundedInteger() {
    assertEquals(0,Data.cancelledForwardLeases(List.of(Value.integer(0))));
    assertEquals(128,Data.cancelledForwardLeases(List.of(Value.integer(128))));
    for(long count:new long[]{-1,129})assertThrows(IllegalArgumentException.class,()->Data.cancelledForwardLeases(List.of(Value.integer(count))));
    assertThrows(IllegalArgumentException.class,()->Data.cancelledForwardLeases(null));
    assertThrows(IllegalArgumentException.class,()->Data.cancelledForwardLeases(List.of()));
    assertThrows(IllegalArgumentException.class,()->Data.cancelledForwardLeases(Arrays.asList((Value)null)));
    assertThrows(IllegalArgumentException.class,()->Data.cancelledForwardLeases(List.of(Value.text("0"))));
    assertThrows(IllegalArgumentException.class,()->Data.cancelledForwardLeases(List.of(Value.integer(0),Value.integer(1))));
  }
'''+s[i:];p.write_text(s)
p=b/'workflow-engine/src/test/java/io/github/cheshmakzanoon/portfolio/durableflow/TypedReplayTest.java';s=p.read_text();i=s.rindex('\n}');s=s[:i]+'''
  @Test void terminalFailureCannotBecomeCancellationButRecordedLeaseBarrierCan() {
    Definition one=new Definition("terminal-root",1,List.of(new Node("root",Kind.ACTIVITY,List.of(),"pure",Safety.PURE,null,Type.TEXT,Type.TEXT,0,null,null,null)));
    var start=new Event(1,EventType.STARTED,"","started",Instant.EPOCH);
    var failed=new Event(2,EventType.FAILED,"root","failed",Instant.ofEpochSecond(1));
    assertEquals(State.FAILED,new Interpreter().replay(one,List.of(start,failed),Map.of()).state());
    assertThrows(IllegalArgumentException.class,()->new Interpreter().replay(one,List.of(start,failed,new Event(3,EventType.CANCELLED,"","cancelled",Instant.ofEpochSecond(2))),Map.of()));
    assertThrows(IllegalArgumentException.class,()->new Interpreter().replay(one,List.of(start,failed,new Event(3,EventType.CANCELLED,"","cancelled",Instant.ofEpochSecond(2),List.of(Value.integer(1)))),Map.of()));
    assertThrows(IllegalArgumentException.class,()->new Interpreter().replay(one,List.of(start,new Event(2,EventType.CANCELLED,"","cancelled",Instant.ofEpochSecond(2),List.of())),Map.of()));
    Definition parallel=new Definition("failed-parallel",1,List.of(new Node("root",Kind.TIMER,List.of(),null,null,null,Type.TEXT,Type.TEXT,0,null,null,null),new Node("left",Kind.ACTIVITY,List.of("root"),"pure",Safety.PURE,null,Type.TEXT,Type.TEXT,0,null,null,null),new Node("right",Kind.ACTIVITY,List.of("root"),"reserve",Safety.IDEMPOTENT_WITH_KEY,"refund",Type.TEXT,Type.TEXT,0,null,null,null)));
    List<Event> h=new ArrayList<>(List.of(start,new Event(2,EventType.TIMER_FIRED,"root","timer",Instant.ofEpochSecond(1)),new Event(3,EventType.FAILED,"left","failed",Instant.ofEpochSecond(2)),new Event(4,EventType.CANCELLED,"","cancelled",Instant.ofEpochSecond(3),List.of(Value.integer(1))),new Event(5,EventType.COMPLETED,"right","real",Instant.ofEpochSecond(4))));
    assertEquals(State.COMPENSATING,new Interpreter().replay(parallel,h,Map.of()).state());
    h.add(new Event(6,EventType.COMPLETED,"comp-right","undone",Instant.ofEpochSecond(5)));
    assertEquals(State.CANCELLED,new Interpreter().replay(parallel,h,Map.of()).state());
    var terminalFailed=new ArrayList<>(h);terminalFailed.remove(3);
    // Renumber the uncancelled failure/compensation history, then reject a later cancellation.
    List<Event> renumbered=new ArrayList<>();long seq=0;for(Event e:terminalFailed)renumbered.add(new Event(++seq,e.type(),e.task(),e.value(),e.at(),e.result()));
    assertEquals(State.FAILED,new Interpreter().replay(parallel,renumbered,Map.of()).state());
    renumbered.add(new Event(++seq,EventType.CANCELLED,"","cancelled",Instant.ofEpochSecond(6)));
    assertThrows(IllegalArgumentException.class,()->new Interpreter().replay(parallel,renumbered,Map.of()));
  }
'''+s[i:];p.write_text(s)
p=b/'workflow-verification/src/test/java/io/github/cheshmakzanoon/portfolio/durableflow/PostgresWorkflowIT.java';s=p.read_text();i=s.index('  String rawClaimsJwt(');s=s[:i]+'''
  @Test void persistedTypedTwoCancellationRemainsPinnedAcrossEvidenceThree()throws Exception {
    Definition old=new Definition("typed-cancel-pin",1,List.of(node("root",Kind.ACTIVITY,List.of(),Safety.PURE,null,0),node("left",Kind.ACTIVITY,List.of("root"),Safety.PURE,null,0),node("right",Kind.ACTIVITY,List.of("root"),Safety.IDEMPOTENT_WITH_KEY,"refund",0)));
    String oldHash=old.contentHash();
    try(Connection c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());PreparedStatement insert=c.prepareStatement("INSERT INTO workflow_definition(tenant,name,version,hash,body,semantics) VALUES(?,?,?,?,?,'2')")) {
      insert.setString(1,tenant);insert.setString(2,old.name());insert.setInt(3,old.version());insert.setString(4,oldHash);insert.setString(5,new ObjectMapper().writeValueAsString(old));insert.executeUpdate();
    }
    Definition upgraded=new Definition(old.name(),2,old.nodes());
    Path log=Files.createTempDirectory("typed-cancel-pin").resolve("effects.log");
    try(FakeProvider provider=new FakeProvider(0,log);Worker worker=new Worker(store,URI.create("http://127.0.0.1:"+provider.port()+"/effects"))) {
      for(String pin:List.of("2","3")) {
        String hash=pin.equals("2")?oldHash:store.deploy(tenant,upgraded);
        UUID id=store.start(tenant,hash,"pin-"+pin,Map.of());
        store.complete(claim(),"root-result");
        var parallel=store.claim(tenant,"parallel-pin",2,60);
        WorkflowStore.Claim left=parallel.stream().filter(q->q.task().equals("left")).findFirst().orElseThrow(),right=parallel.stream().filter(q->q.task().equals("right")).findFirst().orElseThrow();
        var actualEffect=worker.execute(right,"normal");
        for(int attempt=1;attempt<=4;attempt++) {
          store.fail(left,true,"pure activity rejected");
          if(attempt<4){makeReady(id);left=claim();}
        }
        assertEquals(State.CANCEL_REQUESTED,store.get(tenant,id).state());
        store.cancel(tenant,id);
        assertEquals(State.CANCEL_REQUESTED,store.get(tenant,id).state());
        var cancelled=store.history(tenant,id).getLast();
        assertEquals(EventType.CANCELLED,cancelled.type());
        assertEquals(pin.equals("2")?List.of():List.of(Data.Value.integer(1)),cancelled.result());
        if(pin.equals("2")) {
          var before=store.history(tenant,id);
          store.deploy(tenant,upgraded);
          assertEquals("2",store.get(tenant,id).semantics());
          assertEquals(oldHash,store.get(tenant,id).definitionHash());
          assertEquals(before,store.history(tenant,id));
        }
        store.complete(right,actualEffect);
        assertEquals(State.COMPENSATING,store.get(tenant,id).state());
        var undo=claim();assertEquals("comp-right",undo.task());
        store.complete(undo,worker.execute(undo,"normal"));
        assertEquals(State.CANCELLED,store.get(tenant,id).state());
        assertEquals(pin,store.get(tenant,id).semantics());
        if(pin.equals("3"))assertEquals(State.CANCELLED,new Interpreter().replay(upgraded,store.history(tenant,id),Map.of()).state());
      }
      assertEquals(4,provider.effectCount());
    }
    UUID terminal=store.start(tenant,store.deploy(tenant,new Definition("terminal-cancel",1,List.of(node("root",Kind.ACTIVITY,List.of(),Safety.PURE,null,0)))),"terminal",Map.of());
    var root=claim();
    for(int attempt=1;attempt<=4;attempt++){store.fail(root,true,"rejected");if(attempt<4){makeReady(terminal);root=claim();}}
    assertEquals(State.FAILED,store.get(tenant,terminal).state());
    var history=store.history(tenant,terminal);long version=store.get(tenant,terminal).version();
    assertThrows(IllegalArgumentException.class,()->store.cancel(tenant,terminal));
    assertEquals(version,store.get(tenant,terminal).version());assertEquals(history,store.history(tenant,terminal));
  }

'''+s[i:];p.write_text(s)
