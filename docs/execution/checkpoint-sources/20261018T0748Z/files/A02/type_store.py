from pathlib import Path
p=Path('projects/02-durableflow/workflow-persistence/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow/WorkflowStore.java');s=p.read_text().replace('import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;','import io.github.cheshmakzanoon.portfolio.durableflow.Definition.*;\nimport io.github.cheshmakzanoon.portfolio.durableflow.Data.*;')
s=s.replace('String credential) {}','String credential,String semantics,Type outputType) {}').replace('Map<String, String> values) {}','Map<String, Output> values) {}')
a=s.index('  private Definition definition(');b=s.index('  public String deploy',a)
s=s[:a]+'''  private record Deployment(String body,String semantics){}
  private Deployment deployment(Connection c,String tenant,String hash)throws Exception {try(PreparedStatement s=statement(c,"SELECT body,semantics FROM workflow_definition WHERE tenant=? AND hash=?",tenant,hash);ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("definition missing");return new Deployment(r.getString(1),r.getString(2));}}
  private Definition definition(Connection c,String tenant,String hash)throws Exception {Deployment d=deployment(c,tenant,hash);if(!Interpreter.SEMANTICS.equals(d.semantics()))throw new IllegalArgumentException("definition is pinned to legacy semantics");return json.readValue(d.body(),Definition.class);}
'''+s[b:]
s=s.replace('INSERT INTO workflow_definition(tenant,name,version,hash,body) VALUES(?,?,?,?,?)','INSERT INTO workflow_definition(tenant,name,version,hash,body,semantics) VALUES(?,?,?,?,?,?)').replace('                  encode(definition));','                  encode(definition),Interpreter.SEMANTICS);')
a=s.index('  public UUID start(');b=s.index('          String request = hash',a)
s=s[:a]+'''  public UUID start(String tenant,String hash,String key,Map<String,String> context){return start(tenant,hash,key,ExecutionInput.textContext(context));}
  public UUID start(String tenant,String hash,String key,ExecutionInput input){
    boundedKey(key);if(hash==null||!hash.matches("[a-f0-9]{64}")||input==null)throw new IllegalArgumentException("typed input required");
    return tx(c->{Deployment selected=deployment(c,tenant,hash);String body;
      if(Interpreter.SEMANTICS.equals(selected.semantics())){Definition definition=json.readValue(selected.body(),Definition.class).validate();input.validate(definition);body=encode(input);}
      else if(selected.semantics().equals("1")){Map<String,String> legacy=new TreeMap<>();for(var entry:input.context().entrySet()){if(entry.getValue().type()!=Type.TEXT)throw new IllegalArgumentException("legacy context accepts TEXT only");legacy.put(entry.getKey(),(String)entry.getValue().data());}body=encode(legacy);}
      else throw new IllegalStateException("unsupported pinned semantics");
      if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("input byte cap");
'''+s[b:]
s=s.replace('              Interpreter.SEMANTICS,\n              body,','              selected.semantics(),\n              body,')
a=s.index('  private void append(');b=s.index('    if (value == null)',a)
s=s[:a]+'''  private void append(Connection c,UUID id,EventType type,String task,String value)throws Exception {append(c,id,type,task,value,List.of());}
  private void append(Connection c,UUID id,EventType type,String task,String value,List<Value> result)throws Exception {
    String typed=encode(result);if(typed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException("result byte cap");
'''+s[b:]
s=s.replace('INSERT INTO workflow_history(instance_id,sequence,type,task,value) VALUES(?,?,?,?,?)','INSERT INTO workflow_history(instance_id,sequence,type,task,value,result) VALUES(?,?,?,?,?,?)').replace('        value);\n  }\n\n  private List<Event> history','        value,typed);\n  }\n\n  private List<Event> history')
s=s.replace('SELECT sequence,type,task,value,at FROM workflow_history','SELECT sequence,type,task,value,at,result FROM workflow_history').replace('r.getTimestamp(5).toInstant()));','r.getTimestamp(5).toInstant(),json.readValue(r.getString(6),new TypeReference<List<Value>>(){})));')
a=s.index('    if (!Interpreter.SEMANTICS.equals(semantics))',s.index('  private Result schedule'));b=s.index('    // Cancellation',a)
s=s[:a]+'''    Result result;
    if(Interpreter.SEMANTICS.equals(semantics)) result=interpreter.replay(definition(c,tenant,hash),history(c,id),json.readValue(input,ExecutionInput.class));
    else if(semantics.equals("1")){
      LegacyDefinitionV1 legacy=json.readValue(deployment(c,tenant,hash).body(),LegacyDefinitionV1.class);
      List<LegacyInterpreterV1.Event> events=history(c,id).stream().map(e->new LegacyInterpreterV1.Event(e.sequence(),LegacyInterpreterV1.EventType.valueOf(e.type().name()),e.task(),e.value(),e.at())).toList();
      var old=new LegacyInterpreterV1().replay(legacy,events,json.readValue(input,new TypeReference<Map<String,String>>(){}));
      Map<String,Output> values=new LinkedHashMap<>();old.values().forEach((key,value)->values.put(key,Output.of(List.of(Value.text(value)))));
      TaskInput parameters=new TaskInput(ExecutionInput.textContext(Map.of()),List.of(Value.text("legacy")),Map.of());
      List<Decision> decisions=old.decisions().stream().map(d->new Decision(d.id(),Kind.valueOf(d.kind().name()),d.activity(),d.safety()==null?null:Safety.valueOf(d.safety().name()),d.compensation(),d.due(),d.signal(),parameters,Type.TEXT)).toList();
      result=new Result(State.valueOf(old.state().name()),decisions,values);
    }else throw new IllegalStateException("unsupported pinned semantics");
'''+s[b:]
s=s.replace('result.decisions().stream().anyMatch(d->d.kind()==Kind.ACTIVITY)','result.decisions().stream().anyMatch(choice->choice.kind()==Kind.ACTIVITY)')
s=s.replace("INSERT INTO activity_task(instance_id,logical_id,activity,safety,parameters,status,due_at) VALUES(?,?,?,?,?,'READY',?)", "INSERT INTO activity_task(instance_id,logical_id,activity,safety,parameters,status,due_at,output_type) VALUES(?,?,?,?,?,'READY',?,?)")
s=s.replace('            input,\n            Timestamp.from(choice.due()));','            semantics.equals("1")?input:encode(choice.input()),\n            Timestamp.from(choice.due()),choice.outputType().name());')
s=s.replace('SELECT logical_id,activity,safety,parameters,generation,attempt FROM activity_task','SELECT logical_id,activity,safety,parameters,generation,attempt,output_type FROM activity_task')
s=s.replace('worker,credential));','worker,credential,getSemantics(c,id),Type.valueOf(r.getString(7))));')
s=s.replace('"SELECT 1 FROM activity_task WHERE instance_id=? AND logical_id=? AND generation=? AND attempt=? AND worker=? AND credential=? AND status=\'LEASED\' AND lease_until>clock_timestamp() AND safety=? AND activity=? AND parameters=? FOR UPDATE"','"SELECT 1 FROM activity_task t JOIN workflow_instance i ON i.id=t.instance_id WHERE instance_id=? AND logical_id=? AND generation=? AND attempt=? AND worker=? AND credential=? AND status=\'LEASED\' AND lease_until>clock_timestamp() AND safety=? AND activity=? AND parameters=? AND output_type=? AND i.semantics=? FOR UPDATE OF t"')
s=s.replace('                q.parameters());','                q.parameters(),q.outputType().name(),q.semantics());')
s=s.replace('  public void complete(Claim q, String result) {','''  public void complete(Claim q,String result){complete(q,List.of(Value.text(result)));}
  public void complete(Claim q,List<Value> result) {
    Output.of(result).require(q.outputType());''')
s=s.replace('append(c, q.instance(), EventType.COMPLETED, q.task(), result);','append(c,q.instance(),EventType.COMPLETED,q.task(),q.semantics().equals("1")?(String)result.getFirst().data():"completed",result);')
# obligation references original definitions for compatibility
s=s.replace('    Definition d=definition(c,tenant,getHash(c,id));','''    if(getSemantics(c,id).equals("1")){LegacyDefinitionV1 d=json.readValue(deployment(c,tenant,getHash(c,id)).body(),LegacyDefinitionV1.class);for(var n:d.nodes())if(n.id().equals(task)&&n.compensation()!=null)update(c,"INSERT INTO compensation_obligation(instance_id,logical_id,activity,completion_sequence) SELECT id,?,?,version FROM workflow_instance WHERE id=? ON CONFLICT DO NOTHING",n.id(),n.compensation(),id);}else{
    Definition d=definition(c,tenant,getHash(c,id));''')
s=s.replace('    if(task.startsWith("comp-"))update(c,"UPDATE compensation_obligation', '    }\n    if(task.startsWith("comp-"))update(c,"UPDATE compensation_obligation',1)
i=s.index('  private String getHash');s=s[:i]+'''  private String getSemantics(Connection c,UUID id)throws Exception {try(PreparedStatement s=statement(c,"SELECT semantics FROM workflow_instance WHERE id=?",id);ResultSet r=s.executeQuery()){if(!r.next())throw new NoSuchElementException("execution missing");return r.getString(1);}}
'''+s[i:]
# signal typed, preserving raw legacy signal payload for old interpreter
s=s.replace('  public boolean signal(String tenant, UUID id, String key, String signal, String payload) {','''  public boolean signal(String tenant,UUID id,String key,String signal,String payload){return signal(tenant,id,key,signal,Value.text(payload));}
  public boolean signal(String tenant, UUID id, String key, String signal, Value typedPayload) {''')
a=s.index('    if(signal==null');b=s.index('    return tx(',a);s=s[:a]+'''    if(signal==null||!signal.matches("[a-z][a-z0-9-]{0,63}")||typedPayload==null)throw new IllegalArgumentException("signal bounds");
'''+s[b:]
needle='          View view = lock(c, tenant, id);';a=s.index(needle,s.index('  public boolean signal('));s=s[:a]+s[a:].replace(needle,needle+'''
          String payload;
          if(view.semantics().equals("1")){if(typedPayload.type()!=Type.TEXT)throw new IllegalArgumentException("legacy signal requires TEXT");payload=(String)typedPayload.data();}
          else {Definition d=definition(c,tenant,view.definitionHash());Node target=d.nodes().stream().filter(n->n.kind()==Kind.SIGNAL&&signal.equals(n.signal())).findFirst().orElseThrow(()->new IllegalArgumentException("unknown signal"));if(typedPayload.type()!=target.outputType())throw new IllegalArgumentException("signal type mismatch");payload=encode(typedPayload);}
''',1)
s=s.replace('if (accepted) append(c, id, EventType.SIGNAL, choice.id(), payload);','if (accepted) append(c,id,EventType.SIGNAL,choice.id(),view.semantics().equals("1")?payload:"signal received",List.of(typedPayload));')
# resolve overload: v2 API calls typed result; existing Java TEXT convenience never coerces
s=s.replace('''  public void resolve(
      String tenant, UUID id, String task, String decision, String operator, String reason) {''','''  public void resolve(String tenant,UUID id,String task,String decision,String operator,String reason){resolve(tenant,id,task,decision,operator,reason,"APPLIED".equals(decision)?List.of(Value.text("resolved")):List.of());}
  public void resolve(String tenant, UUID id, String task, String decision, String operator, String reason,List<Value> result) {''')
needle='          lock(c, tenant, id);';a=s.index(needle,s.index('  public void resolve('));s=s[:a]+s[a:].replace(needle,needle+'''
          try(PreparedStatement expected=statement(c,"SELECT output_type FROM activity_task WHERE instance_id=? AND logical_id=?",id,task);ResultSet row=expected.executeQuery()){if(!row.next())throw new IllegalArgumentException("task missing");if("APPLIED".equals(decision))Output.of(result).require(Type.valueOf(row.getString(1)));else if(result==null||!result.isEmpty())throw new IllegalArgumentException("NOT_APPLIED has no result");}
''',1)
s=s.replace('append(c, id, EventType.OPERATOR_RESOLVED, task, decision);','append(c,id,EventType.OPERATOR_RESOLVED,task,decision,result);')
p.write_text(s)
