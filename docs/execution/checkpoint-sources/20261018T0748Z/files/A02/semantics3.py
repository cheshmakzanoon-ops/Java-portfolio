from pathlib import Path
base=Path('projects/02-durableflow')
engine=base/'workflow-engine/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow'
tests=base/'workflow-engine/src/test/java/io/github/cheshmakzanoon/portfolio/durableflow'
s=(engine/'Interpreter.java').read_text();(engine/'LegacyInterpreterV2.java').write_text(s.replace('public final class Interpreter','public final class LegacyInterpreterV2').replace('/** Semantics 2:','/** Frozen semantics 2:'))
for old,new in [('InterpreterTest','LegacyInterpreterV2Test'),('TypedReplayTest','LegacyTypedReplayV2Test'),('ReplayPropertyTest','LegacyTypedReplayV2PropertyTest')]:
 s=(tests/(old+'.java')).read_text().replace('Interpreter','LegacyInterpreterV2').replace('class '+old,'class '+new)
 # InterpreterTest itself was renamed by the global replacement.
 if old=='InterpreterTest':s=s.replace('class LegacyInterpreterV2Test','class '+new)
 (tests/(new+'.java')).write_text(s)
s=(engine/'Interpreter.java').read_text().replace('Semantics 2:', 'Semantics 3:').replace('SEMANTICS = "2"','SEMANTICS = "3"')
s=s.replace('case COMPLETED, SIGNAL -> List.of(Value.text(value));','case COMPLETED, SIGNAL -> List.of(Value.text(value));\n            case CANCELLED -> List.of(Value.integer(0));')
s=s.replace('&& event.type() != EventType.OPERATOR_RESOLVED','&& event.type() != EventType.OPERATOR_RESOLVED\n          && event.type() != EventType.CANCELLED',1)
needle='''      if (event.type() == EventType.CANCELLED) {
'''
s=s.replace(needle,needle+'''        int leased=Data.cancelledForwardLeases(event.result());
        long possible=definition.nodes().stream().filter(n->n.kind()==Kind.ACTIVITY && !outputs.containsKey(n.id()) && !failed.contains(n.id()) && !unknown.contains(n.id()) && enabled(n,outputs)).count();
        if(leased>possible)throw new IllegalArgumentException("impossible cancellation lease evidence");
        boolean undoPending=definition.nodes().stream().anyMatch(n->completionOrder.contains(n.id()) && n.compensation()!=null && !outputs.containsKey("comp-"+n.id()));
        if(!failed.isEmpty() && unknown.isEmpty() && !undoPending && leased==0)
          throw new IllegalArgumentException("terminal failed execution cannot cancel");
''')
(engine/'Interpreter.java').write_text(s)
p=base/'workflow-model/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow/Data.java';s=p.read_text().replace('  public record Value(Type type, Object data) {','''  /** Semantics 3 cancellation records the durable forward-lease barrier as an actual integer. */
  public static int cancelledForwardLeases(List<Value> evidence) {
    if(evidence==null || evidence.size()!=1 || evidence.getFirst()==null || evidence.getFirst().type()!=Type.INTEGER)
      throw new IllegalArgumentException("cancellation lease evidence required");
    long count=(Long)evidence.getFirst().data();
    if(count<0 || count>128)throw new IllegalArgumentException("cancellation lease count bounds");
    return (int)count;
  }

  public record Value(Type type, Object data) {''');p.write_text(s)
p=base/'workflow-persistence/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow/WorkflowStore.java';s=p.read_text();s=s.replace('if (!Interpreter.SEMANTICS.equals(d.semantics()))','if (!Set.of("2",Interpreter.SEMANTICS).contains(d.semantics()))');s=s.replace('if (Interpreter.SEMANTICS.equals(selected.semantics()))','if (Set.of("2",Interpreter.SEMANTICS).contains(selected.semantics()))');needle='''    else if (semantics.equals("1")) {''';s=s.replace(needle,'''    else if(semantics.equals("2")) {
      var old=new LegacyInterpreterV2().replay(definition(c,tenant,hash),history(c,id).stream().map(e->new LegacyInterpreterV2.Event(e.sequence(),LegacyInterpreterV2.EventType.valueOf(e.type().name()),e.task(),e.value(),e.at(),e.result())).toList(),json.readValue(input,ExecutionInput.class));
      result=new Result(State.valueOf(old.state().name()),old.decisions().stream().map(d->new Decision(d.id(),d.kind(),d.activity(),d.safety(),d.compensation(),d.due(),d.signal(),d.input(),d.outputType())).toList(),old.values());
    }
'''+needle)
s=s.replace('q.semantics().equals("2")','Set.of("2",Interpreter.SEMANTICS).contains(q.semantics())').replace('row.getString(4).equals("2")','Set.of("2",Interpreter.SEMANTICS).contains(row.getString(4))')
s=s.replace('''          append(c, id, EventType.CANCELLED, "", "cooperative cancellation");''','''          List<Value> evidence=List.of();
          if(view.semantics().equals(Interpreter.SEMANTICS)) {
            try(PreparedStatement query=statement(c,"SELECT count(*) FROM activity_task WHERE instance_id=? AND status='LEASED' AND logical_id NOT LIKE 'comp-%'",id);ResultSet count=query.executeQuery()) {
              count.next();evidence=List.of(Value.integer(count.getLong(1)));
            }
          }
          append(c, id, EventType.CANCELLED, "", "cooperative cancellation",evidence);''')
p.write_text(s)
p=base/'workflow-worker-sdk/src/main/java/io/github/cheshmakzanoon/portfolio/durableflow/Worker.java';s=p.read_text().replace('claim.semantics().equals("2")','Set.of("2","3").contains(claim.semantics())');p.write_text(s)
p=base/'workflow-verification/src/test/java/io/github/cheshmakzanoon/portfolio/durableflow/PostgresWorkflowIT.java';s=p.read_text().replace('assertEquals("2", store.get(tenant, typed).semantics());','assertEquals("3", store.get(tenant, typed).semantics());');p.write_text(s)
p=base/'contracts/openapi.yaml';s=p.read_text().replace('version: 1.1.0','version: 1.2.0').replace('semantics 2 uses validated typed','semantics 3 uses validated typed').replace('Existing semantics 1 remains pinned.','Existing semantics 1 and 2 remain pinned; semantics 3 cancellation records an INTEGER forward-lease count.').replace("          - '2'\n        outputType:","          - '2'\n          - '3'\n        outputType:");p.write_text(s)
